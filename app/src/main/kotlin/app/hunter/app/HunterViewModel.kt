package app.hunter.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.hunter.maintenance.Odometer
import app.hunter.maintenance.data.FuelupEntity
import app.hunter.maintenance.data.MaintenanceItemEntity
import app.hunter.maintenance.data.OdometerEntity
import app.hunter.model.DueState
import app.hunter.model.DueStatus
import app.hunter.model.FuelMath
import app.hunter.model.FuelSummary
import app.hunter.model.Fuelup
import app.hunter.model.MaintenanceItem
import app.hunter.model.MaintenanceMath
import app.hunter.model.TrackPoint
import app.hunter.model.TripSummary
import app.hunter.podble.PodStatus
import app.hunter.trips.recording.TrackingStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MaintenanceRow(val item: MaintenanceItem, val status: DueStatus)

class HunterViewModel(app: Application) : AndroidViewModel(app) {
    private val hunter = app as HunterApp
    private val store = hunter.tripStore
    private val dao = hunter.maintenanceDao

    val recording = TrackingStatus.state
    val pod = PodStatus.state

    private fun <T> kotlinx.coroutines.flow.Flow<T>.hot(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    /** Todos os trajetos, incluindo o que está gravando agora. */
    private val allTrips: StateFlow<List<TripSummary>> = store.observeTrips().hot(emptyList())
    val finishedTrips: StateFlow<List<TripSummary>> = allTrips.map { l -> l.filter { !it.isActive } }.hot(emptyList())

    val fuelups: StateFlow<List<Fuelup>> = dao.observeFuelups().map { l -> l.map { it.toModel() } }.hot(emptyList())
    val fuelSummary: StateFlow<FuelSummary> = fuelups.map { FuelMath.summarize(it) }.hot(FuelMath.summarize(emptyList()))

    private val odometerEntity: StateFlow<OdometerEntity?> = dao.observeOdometer().hot(null)
    val tankLiters: StateFlow<Double> = odometerEntity.map { it?.tankLiters ?: DEFAULT_TANK_L }.hot(DEFAULT_TANK_L)

    private val tripsDistanceM: StateFlow<Double> = allTrips.map { l -> l.sumOf { it.stats.distanceM } }.hot(0.0)

    /** Hodômetro da moto (km): deslocamento informado + soma dos pedais (inclui o que está gravando). */
    val odometerKm: StateFlow<Double> =
        combine(odometerEntity, tripsDistanceM) { o, m -> Odometer.currentKm(o?.offsetKm ?: 0.0, m) }.hot(0.0)

    val maintenance: StateFlow<List<MaintenanceRow>> =
        combine(dao.observeItems(), odometerKm) { items, km ->
            val now = System.currentTimeMillis()
            items.map { MaintenanceRow(it.toModel(), MaintenanceMath.status(it.toModel(), km, now)) }
                .sortedBy { order(it.status.state) }
        }.hot(emptyList())

    /** Itens vencidos ou perto de vencer (para o alerta na tela inicial). */
    val attention: StateFlow<List<MaintenanceRow>> =
        maintenance.map { l -> l.filter { it.status.state == DueState.OVERDUE || it.status.state == DueState.SOON } }.hot(emptyList())

    init {
        // Sugestões iniciais de manutenção, uma única vez (conferir com o manual da moto).
        val prefs = app.getSharedPreferences("app", 0)
        if (!prefs.getBoolean("seeded", false)) {
            viewModelScope.launch {
                if (dao.itemCount() == 0) {
                    MaintenanceMath.defaultItems.forEach { (n, km, days) ->
                        dao.insertItem(MaintenanceItemEntity(name = n, intervalKm = km, intervalDays = days))
                    }
                }
                prefs.edit().putBoolean("seeded", true).apply()
            }
        }
    }

    // --- ações
    fun addFuelup(odometerKm: Double, liters: Double, totalCost: Double, full: Boolean) = viewModelScope.launch {
        dao.insertFuelup(FuelupEntity(dateMs = System.currentTimeMillis(), odometerKm = odometerKm, liters = liters, totalCost = totalCost, fullTank = full))
        // Abastecer em hodômetro maior que o calculado é um bom sinal de que o painel mudou: ajusta o deslocamento.
        if (odometerKm > this@HunterViewModel.odometerKm.value) setOdometer(odometerKm)
    }

    fun deleteFuelup(id: Long) = viewModelScope.launch { dao.deleteFuelup(id) }

    fun setOdometer(panelKm: Double) = viewModelScope.launch {
        val cur = dao.getOdometer() ?: OdometerEntity()
        dao.putOdometer(cur.copy(offsetKm = Odometer.offsetFor(panelKm, tripsDistanceM.value)))
    }

    fun setTank(liters: Double) = viewModelScope.launch {
        val cur = dao.getOdometer() ?: OdometerEntity()
        dao.putOdometer(cur.copy(tankLiters = liters))
    }

    fun addMaintenanceItem(name: String, km: Int?, days: Int?) = viewModelScope.launch {
        dao.insertItem(MaintenanceItemEntity(name = name, intervalKm = km, intervalDays = days))
    }

    fun markDone(item: MaintenanceItem) = viewModelScope.launch {
        dao.updateItem(
            MaintenanceItemEntity(item.id, item.name, item.intervalKm, item.intervalDays, odometerKm.value, System.currentTimeMillis()),
        )
    }

    fun deleteItem(id: Long) = viewModelScope.launch { dao.deleteItem(id) }

    fun deleteTrip(id: Long) = viewModelScope.launch { store.deleteTrip(id) }

    suspend fun points(tripId: Long): List<TrackPoint> = store.points(tripId)

    private fun order(s: DueState) = when (s) {
        DueState.OVERDUE -> 0; DueState.SOON -> 1; DueState.UNKNOWN -> 2; DueState.OK -> 3
    }

    companion object {
        const val DEFAULT_TANK_L = 13.0
    }
}
