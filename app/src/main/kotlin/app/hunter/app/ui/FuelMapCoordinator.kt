package app.hunter.app.ui

import app.hunter.app.fuel.FuelPriceRepository
import app.hunter.app.fuel.ResolvedFuelStation
import app.hunter.model.GeoBox
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
 */
@OptIn(FlowPreview::class)
class FuelMapCoordinator(
    private val scope: CoroutineScope,
    private val fetchStations: suspend (GeoBox) -> List<ResolvedFuelStation>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMs: Long = 300L,
) {
    constructor(
        scope: CoroutineScope,
        repository: FuelPriceRepository,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        debounceMs: Long = 300L,
    ) : this(
        scope = scope,
        fetchStations = { box -> repository.getStationsInBox(box) },
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

    init {
        scope.launch {
            combine(_fuelLayerEnabled, _boundingBox.debounce(debounceMs)) { enabled, box ->
                enabled to box
            }.collectLatest { (enabled, box) ->
                if (!enabled || box == null) {
                    _visibleFuelStations.value = emptyList()
                } else {
                    val stations = withContext(ioDispatcher) {
                        fetchStations(box)
                    }
                    _visibleFuelStations.value = stations
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
        }
    }

    fun selectFuelStation(station: ResolvedFuelStation?) {
        _selectedFuelStation.value = station
    }

    fun updateMapBoundingBox(box: GeoBox) {
        _boundingBox.value = box
    }
}
