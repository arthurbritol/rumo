package app.hunter.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.hunter.app.data.PlaceEntity
import app.hunter.app.sync.ProfilePrefs
import app.hunter.maintenance.Odometer
import app.hunter.maintenance.data.FuelupEntity
import app.hunter.maintenance.data.MaintenanceItemEntity
import app.hunter.maintenance.data.OdometerEntity
import app.hunter.model.DueState
import app.hunter.model.DueStatus
import app.hunter.model.FuelAdvice
import app.hunter.model.FuelMath
import app.hunter.model.FuelPlanner
import app.hunter.model.FuelState
import app.hunter.model.FuelSummary
import app.hunter.model.Fuelup
import app.hunter.model.MaintenanceItem
import app.hunter.model.MaintenanceMath
import app.hunter.model.TrackPoint
import app.hunter.model.TripSummary
import app.hunter.nav.LatLon
import app.hunter.nav.NavServices
import app.hunter.nav.NavStatus
import app.hunter.nav.Place
import app.hunter.nav.Route
import app.hunter.nav.RouteProfile
import app.hunter.podble.PodStatus
import app.hunter.trips.recording.AndroidLocationSource
import app.hunter.trips.recording.TrackingStatus
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import app.hunter.app.account.AuthState
import app.hunter.app.account.SeedDecision
import app.hunter.app.account.maintenanceSeedDecision
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MaintenanceRow(val item: MaintenanceItem, val status: DueStatus)

/** Aviso de autonomia para a rota em pré-visualização, com postos próximos do ponto de parada sugerido. */
data class FuelAdviceUi(
    val advice: FuelAdvice,
    val state: FuelState,
    val stations: List<Place> = emptyList(),
    val loadingStations: Boolean = false,
    val stationsError: String? = null,
)

/** Intervalo mínimo entre consultas automáticas de versão nova (a API do GitHub aceita 60 por hora sem chave). */
const val UPDATE_CHECK_INTERVAL_MS = 15 * 60_000L

/** Estado da busca de atualização: o Perfil mostra o resultado depois do toque em "Buscar atualização". */
enum class UpdateCheck { IDLE, CHECKING, UP_TO_DATE, FAILED, FOUND }

/** Aviso de versão nova: [progress] vai de 0 a 1 (ou -1 se o tamanho é desconhecido) durante o download. */
data class UpdateUi(
    val update: app.hunter.nav.AppUpdate,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val message: String? = null,
)

data class SearchUi(
    val query: String = "",
    val results: List<Place> = emptyList(),
    /** Favoritos e recentes que combinam com o texto: aparecem na hora, antes da rede. */
    val local: List<Place> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** Pré-visualização da rota até um destino, antes de iniciar a navegação. */
data class PreviewUi(
    val place: Place,
    val profile: RouteProfile = RouteProfile.FASTEST,
    val routes: List<Route> = emptyList(),
    val selected: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
    /** Posto escolhido como parada intermediária. */
    val via: Place? = null,
    val fuel: FuelAdviceUi? = null,
    /** Rota longa e sem tanque cheio registrado: não dá para estimar a autonomia. */
    val noFuelData: Boolean = false,
) {
    val route: Route? get() = routes.getOrNull(selected)
}

@OptIn(FlowPreview::class)
class HunterViewModel(app: Application) : AndroidViewModel(app) {
    private val hunter = app as HunterApp
    private val store = hunter.tripStore
    private val dao = hunter.maintenanceDao
    private val places = hunter.placeDao
    private val prefs = app.getSharedPreferences("app", 0)
    private val account get() = hunter.account

    /** Pede uma sincronização (agrupada e fora da thread da interface); sem conta não faz nada. */
    private fun syncSoon() = account.scheduler.requestSync()

    /** O perfil sincronizado pode mudar `voice` (primeira sincronização, outro aparelho): aplica na hora. */
    private val voiceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        if (key == ProfilePrefs.VOICE) NavStatus.setVoice(p.getBoolean(ProfilePrefs.VOICE, true))
    }

    val recording = TrackingStatus.state
    val pod = PodStatus.state
    val nav = NavStatus.state
    val voice = NavStatus.voice

    private fun <T> Flow<T>.hot(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    // ---------- localização para o mapa ----------

    private val _live = MutableStateFlow<TrackPoint?>(null)
    /** Última posição conhecida (do GPS enquanto a tela do mapa está aberta). */
    val live: StateFlow<TrackPoint?> = _live.asStateFlow()
    private var locationJob: Job? = null

    // ---------- conexão ----------

    private val _online = MutableStateFlow(true)
    /** Há internet agora? (rotas e busca precisam; o mapa baixado e a gravação não). */
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private fun watchConnection() {
        val cm = getApplication<Application>().getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        _online.value = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        runCatching {
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    val was = _online.value
                    _online.value = true
                    // Voltou a internet com uma rota falhada na tela: tenta de novo sozinho.
                    if (!was) viewModelScope.launch { if (_preview.value?.error != null) retryRoutes() }
                    if (!was) checkUpdate()
                    if (!was) {
                        // Voltou a internet: sincroniza já e também agenda um pedido (um sinal de conexão que chega
                        // durante uma sincronização em andamento é descartado pelo agendador).
                        account.scheduler.syncOnConnectivity()
                        syncSoon()
                    }
                }

                override fun onLost(network: android.net.Network) { _online.value = false }
            })
        }
    }

    // ---------- atualização do app ----------

    private val updater = AppUpdater(app)
    private val _update = MutableStateFlow<UpdateUi?>(null)
    /** Versão nova disponível (e o andamento do download), ou `null`. */
    val update: StateFlow<UpdateUi?> = _update.asStateFlow()
    private var downloadJob: Job? = null

    private val _updateCheck = MutableStateFlow(UpdateCheck.IDLE)
    /** Resultado da última consulta (mostrado no Perfil, em "Buscar atualização"). */
    val updateCheck: StateFlow<UpdateCheck> = _updateCheck.asStateFlow()

    /**
     * Consulta o GitHub toda vez que o app volta para a frente, com intervalo mínimo de [UPDATE_CHECK_INTERVAL_MS]
     * (ou sempre, com [force], que também mostra de novo uma versão já dispensada). Falha de rede é silenciosa,
     * a não ser numa consulta manual.
     */
    fun checkUpdate(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (_update.value != null) { if (force) _updateCheck.value = UpdateCheck.FOUND; return }
        if (!force && now - prefs.getLong("update_checked_at", 0L) < UPDATE_CHECK_INTERVAL_MS) return
        viewModelScope.launch {
            if (force) _updateCheck.value = UpdateCheck.CHECKING
            val found = try {
                app.hunter.nav.UpdateChecker().check(updater.installedCode())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (force) _updateCheck.value = UpdateCheck.FAILED
                return@launch
            }
            prefs.edit().putLong("update_checked_at", now).apply()
            if (found != null && (force || found.versionCode != prefs.getInt("update_skip_build", -1))) {
                _update.value = UpdateUi(found)
                _updateCheck.value = UpdateCheck.FOUND
            } else {
                _updateCheck.value = if (found != null) UpdateCheck.FOUND else UpdateCheck.UP_TO_DATE
            }
        }
    }

    /** "Agora não": esconde o aviso desta build; só volta numa build ainda mais nova. */
    fun dismissUpdate() {
        val u = _update.value ?: return
        if (u.downloading) return
        prefs.edit().putInt("update_skip_build", u.update.versionCode).apply()
        _update.value = null
    }

    fun installUpdate() {
        val u = _update.value ?: return
        if (u.downloading) return
        downloadJob = viewModelScope.launch {
            _update.update { it?.copy(downloading = true, progress = 0f, message = null) }
            try {
                val apk = updater.download(u.update) { p -> _update.update { it?.copy(progress = p) } }
                val opened = updater.install(apk)
                _update.update {
                    it?.copy(downloading = false, message = if (opened) null else "Permita que o Rumo instale apps e toque em Atualizar de novo.")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _update.update { it?.copy(downloading = false, message = "Não foi possível baixar. Verifique a internet e tente de novo.") }
            }
        }
    }

    fun startLocation() {
        if (locationJob?.isActive == true) return
        locationJob = viewModelScope.launch {
            try {
                AndroidLocationSource(getApplication(), includeNetwork = true).updates().collect { _live.value = it }
            } catch (e: SecurityException) {
                // sem permissão: o mapa fica sem a posição até o usuário permitir
            }
        }
    }

    fun stopLocation() {
        locationJob?.cancel()
        locationJob = null
    }

    // ---------- viagens ----------

    /** Todos os trajetos, incluindo o que está gravando agora. */
    private val allTrips: StateFlow<List<TripSummary>> = store.observeTrips().hot(emptyList())
    val finishedTrips: StateFlow<List<TripSummary>> = allTrips.map { l -> l.filter { !it.isActive } }.hot(emptyList())

    fun deleteTrip(id: Long) = viewModelScope.launch { store.deleteTrip(id) }
    fun renameTrip(id: Long, title: String) = viewModelScope.launch { store.renameTrip(id, title) }
    suspend fun points(tripId: Long): List<TrackPoint> = store.points(tripId)

    // ---------- combustível, manutenção, hodômetro ----------

    val fuelups: StateFlow<List<Fuelup>> = dao.observeFuelups().map { l -> l.map { it.toModel() } }.hot(emptyList())
    val fuelSummary: StateFlow<FuelSummary> = fuelups.map { FuelMath.summarize(it) }.hot(FuelMath.summarize(emptyList()))

    private val odometerEntity: StateFlow<OdometerEntity?> = dao.observeOdometer().hot(null)
    val tankLiters: StateFlow<Double> = odometerEntity.map { it?.tankLiters ?: DEFAULT_TANK_L }.hot(DEFAULT_TANK_L)

    private val tripsDistanceM: StateFlow<Double> = allTrips.map { l -> l.sumOf { it.stats.distanceM } }.hot(0.0)

    /** Hodômetro da moto (km): deslocamento informado + soma das viagens (inclui a que está gravando). */
    val odometerKm: StateFlow<Double> =
        combine(odometerEntity, tripsDistanceM) { o, m -> Odometer.currentKm(o?.offsetKm ?: 0.0, m) }.hot(0.0)

    val maintenance: StateFlow<List<MaintenanceRow>> =
        combine(dao.observeItems(), odometerKm) { items, km ->
            val now = System.currentTimeMillis()
            items.map { MaintenanceRow(it.toModel(), MaintenanceMath.status(it.toModel(), km, now)) }
                .sortedBy { order(it.status.state) }
        }.hot(emptyList())

    /** Itens vencidos ou perto de vencer (alerta na tela inicial). */
    val attention: StateFlow<List<MaintenanceRow>> =
        maintenance.map { l -> l.filter { it.status.state == DueState.OVERDUE || it.status.state == DueState.SOON } }.hot(emptyList())

    // ---------- destinos salvos ----------

    val favorites: StateFlow<List<Place>> = places.observeFavorites().map { l -> l.map { it.toPlace() } }.hot(emptyList())
    val recents: StateFlow<List<Place>> = places.observeRecents().map { l -> l.map { it.toPlace() } }.hot(emptyList())

    fun isFavorite(p: Place, favs: List<Place>) = favs.any { same(it, p) }

    fun toggleFavorite(p: Place) = viewModelScope.launch {
        val e = places.find(p.lat, p.lon)
        if (e == null) {
            places.insert(PlaceEntity(name = p.name, subtitle = p.subtitle, lat = p.lat, lon = p.lon, favorite = true, lastUsedMs = System.currentTimeMillis(), useCount = 0))
        } else {
            if (e.favorite) places.unfavorite(e) else places.update(e.copy(favorite = true))
        }
        syncSoon()
    }

    private suspend fun rememberPlace(p: Place) {
        val e = places.find(p.lat, p.lon)
        val now = System.currentTimeMillis()
        if (e == null) places.insert(PlaceEntity(name = p.name, subtitle = p.subtitle, lat = p.lat, lon = p.lon, favorite = false, lastUsedMs = now, useCount = 1))
        else places.updateLocalOnly(e.copy(lastUsedMs = now, useCount = e.useCount + 1))
    }

    private fun same(a: Place, b: Place) = kotlin.math.abs(a.lat - b.lat) < 0.0001 && kotlin.math.abs(a.lon - b.lon) < 0.0001

    // ---------- busca de destino ----------

    private val _search = MutableStateFlow(SearchUi())
    val search: StateFlow<SearchUi> = _search.asStateFlow()
    private val queryFlow = MutableStateFlow("")

    fun onQuery(q: String) {
        val t = q.trim().lowercase()
        val local = if (t.length < 2) emptyList() else (favorites.value + recents.value)
            .distinctBy { "${it.lat},${it.lon}" }
            .filter { it.name.lowercase().contains(t) || it.subtitle.orEmpty().lowercase().contains(t) }
            .take(4)
        _search.update { it.copy(query = q, local = local, error = null, loading = t.length >= 3) }
        queryFlow.value = q
    }

    fun clearSearch() {
        _search.value = SearchUi()
        queryFlow.value = ""
    }

    // ---------- pré-visualização de rota e navegação ----------

    private val _preview = MutableStateFlow<PreviewUi?>(null)
    val preview: StateFlow<PreviewUi?> = _preview.asStateFlow()
    private var routeJob: Job? = null

    fun previewTo(place: Place) {
        clearSearch()
        _preview.value = PreviewUi(place)
        loadRoutes()
    }

    fun setProfile(profile: RouteProfile) {
        _preview.update { it?.copy(profile = profile, routes = emptyList(), selected = 0, loading = true, error = null) }
        loadRoutes()
    }

    fun selectRoute(i: Int) {
        _preview.update { it?.copy(selected = i) }
        refreshFuelAdvice()
    }

    fun cancelPreview() {
        routeJob?.cancel()
        adviceJob?.cancel()
        _preview.value = null
    }

    /** Insere um posto como parada: a rota é recalculada passando por ele. */
    fun chooseStation(station: Place) {
        _preview.update { it?.copy(via = station, routes = emptyList(), selected = 0, loading = true, error = null, fuel = null) }
        loadRoutes()
    }

    fun clearVia() {
        _preview.update { it?.copy(via = null, routes = emptyList(), selected = 0, loading = true, error = null, fuel = null) }
        loadRoutes()
    }

    private var adviceJob: Job? = null

    /** Estima a autonomia (último tanque cheio + consumo) e, se a rota não cabe, procura postos no ponto de parada sugerido. */
    private fun refreshFuelAdvice() {
        val p = _preview.value ?: return
        val route = p.route ?: return
        adviceJob?.cancel()
        adviceJob = viewModelScope.launch {
            val fuelups = dao.observeFuelups().first().map { it.toModel() }
            val od = dao.getOdometer()
            val tripsM = store.observeTrips().first().sumOf { it.stats.distanceM }
            val odo = Odometer.currentKm(od?.offsetKm ?: 0.0, tripsM)
            val state = FuelPlanner.state(od?.tankLiters ?: DEFAULT_TANK_L, FuelMath.summarize(fuelups).avgKmPerL, odo, fuelups)
            if (state == null) {
                _preview.update { cur -> if (cur != null && cur.route === route) cur.copy(fuel = null, noFuelData = route.distanceM > 60_000) else cur }
                return@launch
            }
            val advice = FuelPlanner.advise(route.distanceM / 1000.0, state.rangeKm)
            val base = FuelAdviceUi(advice, state, loadingStations = advice.needsStop && p.via == null)
            _preview.update { cur -> if (cur != null && cur.route === route) cur.copy(fuel = base, noFuelData = false) else cur }
            if (!advice.needsStop || p.via != null) return@launch

            val at = route.pointAt((advice.stopAtKm ?: 0.0) * 1000.0)
            val result = try {
                val st = NavServices.fuelStations.near(at)
                base.copy(stations = st, loadingStations = false, stationsError = if (st.isEmpty()) "Nenhum posto encontrado perto do km ${(advice.stopAtKm ?: 0.0).toInt()}." else null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                base.copy(loadingStations = false, stationsError = "Não foi possível buscar postos (sem internet?).")
            }
            _preview.update { cur -> if (cur != null && cur.route === route) cur.copy(fuel = result) else cur }
        }
    }

    fun retryRoutes() {
        _preview.update { it?.copy(loading = true, error = null) }
        loadRoutes()
    }

    private fun loadRoutes() {
        val p = _preview.value ?: return
        routeJob?.cancel()
        routeJob = viewModelScope.launch {
            // Espera até 20 s pela primeira posição (GPS ou rede) em vez de desistir de cara.
            val from = _live.value ?: kotlinx.coroutines.withTimeoutOrNull(20_000) { _live.filterNotNull().first() }
            if (from == null) {
                _preview.update { it?.copy(loading = false, error = "Sem sinal de localização. Vá para um local aberto e toque em Tentar de novo.") }
                return@launch
            }
            try {
                val routes = NavServices.router.route(
                    LatLon(from.latitude, from.longitude), p.place.latLon, p.profile, alternates = 2,
                    via = listOfNotNull(p.via?.latLon),
                )
                _preview.update { cur ->
                    if (cur == null || cur.place != p.place || cur.profile != p.profile || cur.via != p.via) cur
                    else cur.copy(routes = routes, selected = 0, loading = false, error = if (routes.isEmpty()) "Nenhuma rota encontrada." else null)
                }
                refreshFuelAdvice()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _preview.update {
                    it?.copy(
                        loading = false,
                        error = if (_online.value) "O servidor de rotas não respondeu. Tente de novo em instantes." else "Sem internet para calcular a rota.",
                    )
                }
            }
        }
    }

    /** Confirma a rota escolhida e marca a navegação como ativa. A UI então liga o serviço. */
    fun commitNavigation(): Boolean {
        val p = _preview.value ?: return false
        val route = p.route ?: return false
        viewModelScope.launch { rememberPlace(p.place) }
        NavStatus.start(route, p.place, via = listOfNotNull(p.via?.latLon))
        adviceJob?.cancel()
        _preview.value = null
        return true
    }

    /** Garante que a navegação termina na UI mesmo se o serviço já tiver parado. */
    fun stopNavigationNow() = NavStatus.stop()

    fun toggleVoice() {
        val on = !NavStatus.voice.value
        NavStatus.setVoice(on)
        prefs.edit().putBoolean("voice", on).apply()
        ProfilePrefs.markDirty(prefs)
        syncSoon()
    }

    /** Toque longo no mapa: usa o endereço mais próximo como destino. */
    fun previewAt(p: LatLon) {
        viewModelScope.launch {
            val found = try { NavServices.geocoder.reverse(p) } catch (e: Exception) { null }
            previewTo(found?.copy(lat = p.lat, lon = p.lon) ?: Place("Local no mapa", "%.4f, %.4f".format(p.lat, p.lon), p.lat, p.lon))
        }
    }

    init {
        NavStatus.setVoice(prefs.getBoolean("voice", true))
        prefs.registerOnSharedPreferenceChangeListener(voiceListener)
        PodStatus.restore(app)
        watchConnection()
        checkUpdate()

        // Busca com debounce; cancela a anterior quando o texto muda.
        viewModelScope.launch {
            queryFlow.debounce(180).distinctUntilChanged().collectLatest { q ->
                if (q.trim().length < 3) {
                    _search.update { it.copy(results = emptyList(), loading = false) }
                    return@collectLatest
                }
                try {
                    val near = _live.value?.let { LatLon(it.latitude, it.longitude) }
                    val res = NavServices.geocoder.search(q, near)
                    _search.update { if (it.query == q) it.copy(results = res, loading = false, error = if (res.isEmpty() && it.local.isEmpty()) "Nada encontrado para \"${q.trim()}\"." else null) else it }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _search.update { it.copy(loading = false, error = "Sem conexão para buscar endereços.") }
                }
            }
        }

        // Sugestões iniciais de manutenção, uma única vez (conferir com o manual da moto). Logado, espera a primeira
        // sincronização da conta: os itens dela (com histórico) chegam antes e a semente não vira duplicata.
        if (!prefs.getBoolean("seeded", false)) {
            viewModelScope.launch {
                combine(account.gateway.state, account.syncedUid) { s, synced ->
                    val uid = (s as? AuthState.SignedIn)?.uid
                    (uid != null) to (uid != null && synced == uid)
                }.first { (signedIn, synced) ->
                    when (maintenanceSeedDecision(dao.itemCount(), signedIn, synced)) {
                        SeedDecision.WAIT -> false
                        SeedDecision.MARK_ONLY -> true
                        SeedDecision.SEED -> {
                            MaintenanceMath.defaultItems.forEach { (n, km, days) ->
                                dao.insertItem(MaintenanceItemEntity(name = n, intervalKm = km, intervalDays = days))
                            }
                            true
                        }
                    }
                }
                prefs.edit().putBoolean("seeded", true).apply()
            }
        }
    }

    // ---------- ações de garagem ----------

    fun addFuelup(odometerKm: Double, liters: Double, totalCost: Double, full: Boolean) = viewModelScope.launch {
        dao.insertFuelup(FuelupEntity(dateMs = System.currentTimeMillis(), odometerKm = odometerKm, liters = liters, totalCost = totalCost, fullTank = full))
        // Abastecer em hodômetro maior que o calculado é um bom sinal de que o painel mudou: ajusta o deslocamento.
        if (odometerKm > this@HunterViewModel.odometerKm.value) setOdometer(odometerKm)
        syncSoon()
    }

    fun deleteFuelup(id: Long) = viewModelScope.launch { dao.deleteFuelup(id); syncSoon() }

    fun setOdometer(panelKm: Double) = viewModelScope.launch {
        val cur = dao.getOdometer() ?: OdometerEntity()
        dao.putOdometer(cur.copy(offsetKm = Odometer.offsetFor(panelKm, tripsDistanceM.value)))
        syncSoon()
    }

    fun setTank(liters: Double) = viewModelScope.launch {
        val cur = dao.getOdometer() ?: OdometerEntity()
        dao.putOdometer(cur.copy(tankLiters = liters))
        syncSoon()
    }

    fun addMaintenanceItem(name: String, km: Int?, days: Int?) = viewModelScope.launch {
        dao.insertItem(MaintenanceItemEntity(name = name, intervalKm = km, intervalDays = days))
        syncSoon()
    }

    fun markDone(item: MaintenanceItem) = viewModelScope.launch {
        // Parte da linha existente: refazer a entidade do zero trocaria o gid (id global) do item.
        val cur = dao.getItem(item.id) ?: return@launch
        dao.updateItem(cur.copy(lastDoneKm = odometerKm.value, lastDoneAtMs = System.currentTimeMillis()))
        syncSoon()
    }

    fun deleteItem(id: Long) = viewModelScope.launch { dao.deleteItem(id); syncSoon() }

    private fun order(s: DueState) = when (s) {
        DueState.OVERDUE -> 0; DueState.SOON -> 1; DueState.UNKNOWN -> 2; DueState.OK -> 3
    }

    override fun onCleared() {
        prefs.unregisterOnSharedPreferenceChangeListener(voiceListener)
        super.onCleared()
    }

    companion object {
        const val DEFAULT_TANK_L = 13.0
    }
}
