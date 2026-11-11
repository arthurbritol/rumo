package app.hunter.app.ui

import app.hunter.app.fuel.FuelPriceRepository
import app.hunter.app.fuel.ResolvedFuelStation
import app.hunter.model.GeoBox
import app.hunter.nav.FuelStationFinder
import app.hunter.nav.LatLon
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
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
 * e atrasadas por [debounceMs] (padrão 300ms) e executadas no [ioDispatcher] (padrão Dispatchers.IO),
 * garantindo que consultas espaciais ao Room nunca bloqueiem a thread principal da interface.
 *
 * Se não houver postos cadastrados no Room para a região enquadrada, dispara busca sob demanda
 * de postos reais via [searchNearbyStations] (Overpass / OSM), enriquecendo-os com as médias ANP
 * e salvando-os no Room para operação offline contínua.
 */
@OptIn(FlowPreview::class)
class FuelMapCoordinator(
    private val scope: CoroutineScope,
    private val fetchStations: suspend (GeoBox) -> List<ResolvedFuelStation>,
    private val searchNearbyStations: (suspend (LatLon) -> List<ResolvedFuelStation>)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMs: Long = 300L,
) {
    constructor(
        scope: CoroutineScope,
        repository: FuelPriceRepository,
        stationFinder: FuelStationFinder? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        debounceMs: Long = 300L,
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

    init {
        scope.launch {
            combine(_fuelLayerEnabled, _boundingBox.debounce(debounceMs)) { enabled, box ->
                enabled to box
            }.collectLatest { (enabled, box) ->
                if (!enabled || box == null) {
                    _visibleFuelStations.value = emptyList()
                    _isSearchingOnline.value = false
                } else {
                    val localStations = withContext(ioDispatcher) {
                        fetchStations(box)
                    }
                    if (localStations.isNotEmpty()) {
                        _visibleFuelStations.value = localStations
                        _isSearchingOnline.value = false
                    } else if (searchNearbyStations != null) {
                        // Se não há postos na área visível, busca no OSM/Overpass e enriquece com a ANP
                        _isSearchingOnline.value = true
                        val center = LatLon((box.minLat + box.maxLat) / 2.0, (box.minLon + box.maxLon) / 2.0)
                        val fetched = withContext(ioDispatcher) {
                            try {
                                searchNearbyStations.invoke(center)
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                        _isSearchingOnline.value = false
                        if (fetched.isNotEmpty()) {
                            val refreshed = withContext(ioDispatcher) {
                                fetchStations(box)
                            }
                            _visibleFuelStations.value = refreshed.ifEmpty { fetched }
                        } else {
                            _visibleFuelStations.value = emptyList()
                        }
                    } else {
                        _visibleFuelStations.value = emptyList()
                        _isSearchingOnline.value = false
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
        scope.launch {
            _isSearchingOnline.value = true
            val center = LatLon((box.minLat + box.maxLat) / 2.0, (box.minLon + box.maxLon) / 2.0)
            withContext(ioDispatcher) {
                try {
                    searchNearbyStations.invoke(center)
                } catch (_: Exception) {}
            }
            _isSearchingOnline.value = false
            val refreshed = withContext(ioDispatcher) { fetchStations(box) }
            _visibleFuelStations.value = refreshed
        }
    }
}
