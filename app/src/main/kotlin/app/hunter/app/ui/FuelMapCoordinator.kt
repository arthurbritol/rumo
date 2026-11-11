package app.hunter.app.ui

import app.hunter.app.fuel.FuelPriceRepository
import app.hunter.app.fuel.ResolvedFuelStation
import app.hunter.model.Geo
import app.hunter.model.GeoBox
import app.hunter.nav.FuelStationFinder
import app.hunter.nav.LatLon
import kotlinx.coroutines.CoroutineDispatcher
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
        fetchStations = { box -> repository.getStationsInBox(box) },
        searchNearbyStations = if (stationFinder != null) { center ->
            val places = try {
                stationFinder.near(center, radiusM = 5_000, limit = 25)
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

    private val _visibleFuelStations = MutableStateFlow<List<ResolvedFuelStation>>(emptyList())
    val visibleFuelStations: StateFlow<List<ResolvedFuelStation>> = _visibleFuelStations.asStateFlow()

    private val _isSearchingOnline = MutableStateFlow(false)
    val isSearchingOnline: StateFlow<Boolean> = _isSearchingOnline.asStateFlow()

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
                    if (localStations.isEmpty() && searchNearbyStations != null) {
                        val center = LatLon((box.minLat + box.maxLat) / 2.0, (box.minLon + box.maxLon) / 2.0)
                        val alreadySearched = synchronized(searchedCenters) {
                            searchedCenters.any { Geo.distanceM(it.lat, it.lon, center.lat, center.lon) < 4_000.0 }
                        }
                        if (!alreadySearched && (searchJob == null || searchJob?.isActive == false)) {
                            synchronized(searchedCenters) {
                                searchedCenters.add(center)
                                if (searchedCenters.size > 50) searchedCenters.removeAt(0)
                            }
                            searchJob = scope.launch(ioDispatcher) {
                                _isSearchingOnline.value = true
                                try {
                                    val fetched = searchNearbyStations.invoke(center)
                                    val currentBox = _boundingBox.value ?: box
                                    val refreshed = fetchStations(currentBox)
                                    _visibleFuelStations.value = refreshed.ifEmpty { fetched }
                                } catch (_: Exception) {
                                    // Falha silenciosa de rede
                                } finally {
                                    _isSearchingOnline.value = false
                                }
                            }
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

    fun searchCurrentArea() {
        val box = _boundingBox.value ?: return
        if (searchNearbyStations == null || !_fuelLayerEnabled.value) return
        searchJob?.cancel()
        val center = LatLon((box.minLat + box.maxLat) / 2.0, (box.minLon + box.maxLon) / 2.0)
        searchJob = scope.launch(ioDispatcher) {
            _isSearchingOnline.value = true
            try {
                val fetched = searchNearbyStations.invoke(center)
                val currentBox = _boundingBox.value ?: box
                val refreshed = fetchStations(currentBox)
                _visibleFuelStations.value = refreshed.ifEmpty { fetched }
            } catch (_: Exception) {
            } finally {
                _isSearchingOnline.value = false
            }
        }
    }
}
