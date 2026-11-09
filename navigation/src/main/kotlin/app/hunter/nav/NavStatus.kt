package app.hunter.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Estado da navegação visível para a UI, o serviço e o Pod (mesmo processo → singleton). */
data class NavUiState(
    val active: Boolean = false,
    val destination: Place? = null,
    /** Paradas intermediárias da rota (ex.: posto de combustível), na ordem. */
    val via: List<LatLon> = emptyList(),
    val route: Route? = null,
    val progress: NavProgress? = null,
    val rerouting: Boolean = false,
    val arrived: Boolean = false,
    /** Mudou a cada novo traçado (rota inicial ou recalculada): a UI redesenha a linha. */
    val routeVersion: Int = 0,
)

object NavStatus {
    private val _state = MutableStateFlow(NavUiState())
    val state: StateFlow<NavUiState> = _state.asStateFlow()

    /** Instruções por voz ligadas (a UI restaura/persiste a preferência). */
    private val _voice = MutableStateFlow(true)
    val voice: StateFlow<Boolean> = _voice.asStateFlow()
    fun setVoice(on: Boolean) { _voice.value = on }

    fun start(route: Route, destination: Place, via: List<LatLon> = emptyList()) = _state.update {
        NavUiState(active = true, destination = destination, via = via, route = route, routeVersion = it.routeVersion + 1)
    }

    fun setRoute(route: Route) = _state.update {
        it.copy(route = route, rerouting = false, routeVersion = it.routeVersion + 1)
    }

    fun setProgress(p: NavProgress) = _state.update { if (it.active) it.copy(progress = p, arrived = p.arrived) else it }
    fun setRerouting(v: Boolean) = _state.update { if (it.active) it.copy(rerouting = v) else it }
    fun stop() = _state.update { NavUiState(routeVersion = it.routeVersion + 1) }
}

/** Serviços de rede compartilhados (um cliente só). */
object NavServices {
    val router: ValhallaClient by lazy { ValhallaClient() }
    val geocoder: PhotonGeocoder by lazy { PhotonGeocoder() }
    val fuelStations: FuelStationFinder by lazy { FuelStationFinder() }
}
