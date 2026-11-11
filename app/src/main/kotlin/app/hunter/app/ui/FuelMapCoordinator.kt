package app.hunter.app.ui

import app.hunter.app.fuel.FuelPriceRepository
import app.hunter.app.fuel.PriceSource
import app.hunter.app.fuel.ResolvedFuelStation
import app.hunter.model.Geo
import app.hunter.model.GeoBox
import app.hunter.nav.FuelStationFinder
import app.hunter.nav.LatLon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coordenador reativo da camada de postos e marcadores de preço de combustível no mapa.
 *
 * Implementa Review Focus 5:
 * Atualizações de Bounding Box durante o arrasto da câmera no mapa (60 FPS) são agrupadas
 * e atrasadas por [debounceMs] (padrão 200ms) e executadas no [ioDispatcher] (padrão Dispatchers.IO),
 * garantindo que consultas espaciais ao Room nunca bloqueiem a thread principal da interface.
 *
 * Postos já existentes no Room local aparecem imediatamente com 0ms de espera.
 * Se a região enquadrada não possuir postos locais, dispara busca rápida online em background
 * (via Komoot Photon / Overpass), enriquecendo-os com as médias ANP e salvando-os no Room
 * para que fiquem gravados permanentemente e apareçam instantaneamente nas próximas consultas.
 */
@OptIn(FlowPreview::class)
class FuelMapCoordinator(
    private val scope: CoroutineScope,
    private val fetchStations: suspend (GeoBox) -> List<ResolvedFuelStation>,
    private val searchNearbyStations: (suspend (LatLon) -> List<ResolvedFuelStation>)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMs: Long = 200L,
) {
    constructor(
        scope: CoroutineScope,
        repository: FuelPriceRepository,
        stationFinder: FuelStationFinder? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        debounceMs: Long = 200L,
    ) : this(
        scope = scope,
        fetchStations = { box -> repository.getMapStations(box) },
        searchNearbyStations = if (stationFinder != null) { center ->
            val places = try {
                stationFinder.near(center, radiusM = 8_000, limit = 30)
            } catch (_: Exception) {
                emptyList()
            }
            repository.enrichAndSavePlaces(places)
        } else null,
        ioDispatcher = ioDispatcher,
        debounceMs = debounceMs,
    )

    private val _fuelLayerEnabled = MutableStateFlow(false)
    val fuelLayerEnabled: StateFlow<Boolean> = _fuelLayerEnabled.asStateFlow()

    private val _selectedFuelStation = MutableStateFlow<ResolvedFuelStation?>(null)
    val selectedFuelStation: StateFlow<ResolvedFuelStation?> = _selectedFuelStation.asStateFlow()

    private val _boundingBox = MutableStateFlow<GeoBox?>(null)
    val boundingBox: StateFlow<GeoBox?> = _boundingBox.asStateFlow()

    private val _visibleFuelStations = MutableStateFlow<List<ResolvedFuelStation>>(emptyList())
    val visibleFuelStations: StateFlow<List<ResolvedFuelStation>> = _visibleFuelStations.asStateFlow()

    private val _isSearchingOnline = MutableStateFlow(false)
    val isSearchingOnline: StateFlow<Boolean> = _isSearchingOnline.asStateFlow()

    /** Resultado da última busca online (null = nada a avisar), para a tela mostrar o motivo em vez de ficar muda. */
    private val _searchStatus = MutableStateFlow<String?>(null)
    val searchStatus: StateFlow<String?> = _searchStatus.asStateFlow()

    private var searchJob: Job? = null
    private val searchedCenters = mutableListOf<LatLon>()

    init {
        scope.launch {
            combine(_fuelLayerEnabled, _boundingBox.debounce(debounceMs)) { enabled, box ->
                enabled to box
            }.collectLatest { (enabled, box) ->
                if (!enabled || box == null) {
                    _visibleFuelStations.value = emptyList()
                    _isSearchingOnline.value = false
                } else {
                    // 1. Carrega dados locais do Room imediatamente
                    val localStations = withContext(ioDispatcher) {
                        fetchStations(box)
                    }
                    if (localStations.isNotEmpty()) {
                        _visibleFuelStations.value = localStations
                    }

                    // 2. Se não há postos na área visível, dispara busca online em background
                    if (localStations.isEmpty() && searchNearbyStations != null && !isTooWide(box)) {
                        val center = LatLon((box.minLat + box.maxLat) / 2.0, (box.minLon + box.maxLon) / 2.0)
                        val alreadySearched = synchronized(searchedCenters) {
                            searchedCenters.any { Geo.distanceM(it.lat, it.lon, center.lat, center.lon) < 4_000.0 }
                        }
                        if (!alreadySearched && (searchJob == null || searchJob?.isActive == false)) {
                            synchronized(searchedCenters) {
                                searchedCenters.add(center)
                                if (searchedCenters.size > 50) searchedCenters.removeAt(0)
                            }
                            searchJob = launchSearch(center, box)
                        }
                    }
                }
            }
        }
    }

    fun toggleFuelLayer() {
        val next = !_fuelLayerEnabled.value
        _fuelLayerEnabled.value = next
        if (!next) {
            _selectedFuelStation.value = null
            _visibleFuelStations.value = emptyList()
            _isSearchingOnline.value = false
            _searchStatus.value = null
            searchJob?.cancel()
        } else {
            // Ao ligar, exibe imediatamente os postos locais caso a caixa já esteja disponível
            val box = _boundingBox.value
            if (box != null) {
                scope.launch(ioDispatcher) {
                    val local = fetchStations(box)
                    if (local.isNotEmpty()) {
                        _visibleFuelStations.value = local
                    }
                }
            }
        }
    }

    fun selectFuelStation(station: ResolvedFuelStation?) {
        _selectedFuelStation.value = station
    }

    fun updateMapBoundingBox(box: GeoBox) {
        _boundingBox.value = box
    }

    fun refreshVisibleStations(stations: List<ResolvedFuelStation>) {
        _visibleFuelStations.value = stations
    }

    fun updateStationPriceInVisible(
        stationId: String,
        gc: Double?,
        ga: Double?,
        et: Double?,
        date: String = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.forLanguageTag("pt-BR")).format(java.util.Date()),
    ) {
        _visibleFuelStations.value = _visibleFuelStations.value.map { st ->
            if (st.id == stationId) {
                st.copy(
                    precoGasolina = gc,
                    precoGasolinaAdit = ga,
                    precoEtanol = et,
                    fonte = PriceSource.POSTO_ANP,
                    dataRef = date,
                )
            } else st
        }
        val sel = _selectedFuelStation.value
        if (sel != null && sel.id == stationId) {
            _selectedFuelStation.value = sel.copy(
                precoGasolina = gc,
                precoGasolinaAdit = ga,
                precoEtanol = et,
                fonte = PriceSource.POSTO_ANP,
                dataRef = date,
            )
        }
    }

    fun searchCurrentArea() {
        val box = _boundingBox.value ?: return
        if (searchNearbyStations == null || !_fuelLayerEnabled.value) return
        if (isTooWide(box)) {
            _searchStatus.value = "Aproxime o mapa para ver os postos."
            return
        }
        searchJob?.cancel()
        val center = LatLon((box.minLat + box.maxLat) / 2.0, (box.minLon + box.maxLon) / 2.0)
        searchJob = launchSearch(center, box)
    }

    /** Busca online com tempo máximo; sempre termina e deixa em [searchStatus] o que aconteceu. */
    private fun launchSearch(center: LatLon, box: GeoBox): Job = scope.launch(ioDispatcher) {
        val search = searchNearbyStations ?: return@launch
        _isSearchingOnline.value = true
        _searchStatus.value = null
        try {
            val fetched = withTimeout(SEARCH_TIMEOUT_MS) { search(center) }
            val currentBox = _boundingBox.value ?: box
            val refreshed = fetchStations(currentBox)
            val shown = refreshed.ifEmpty { fetched }
            _visibleFuelStations.value = shown
            _searchStatus.value = if (fetched.isEmpty()) {
                "Nenhum posto com preço da ANP aqui. Aproxime o mapa ou tente de novo."
            } else {
                "${shown.size} postos no mapa · toque para buscar de novo"
            }
        } catch (e: TimeoutCancellationException) {
            _searchStatus.value = "Os servidores de mapa não responderam. Toque para tentar de novo."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _searchStatus.value = "Falha na busca de postos (${e.javaClass.simpleName}). Toque para tentar de novo."
        } finally {
            _isSearchingOnline.value = false
        }
    }

    private companion object {
        const val SEARCH_TIMEOUT_MS = 25_000L

        /** Área larga demais (mais de ~40 km): é a vista do estado, em que se mostra a média e não postos. */
        fun isTooWide(box: GeoBox): Boolean {
            val midLat = Math.toRadians((box.minLat + box.maxLat) / 2.0)
            return (box.maxLon - box.minLon) * 111.32 * Math.cos(midLat) > 40.0
        }
    }
}
