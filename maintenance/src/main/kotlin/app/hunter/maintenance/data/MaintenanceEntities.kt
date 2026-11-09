package app.hunter.maintenance.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.hunter.model.Fuelup
import app.hunter.model.MaintenanceItem

@Entity(tableName = "fuelups")
data class FuelupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateMs: Long,
    val odometerKm: Double,
    val liters: Double,
    val totalCost: Double,
    val fullTank: Boolean,
) {
    fun toModel() = Fuelup(id, dateMs, odometerKm, liters, totalCost, fullTank)
}

@Entity(tableName = "maintenance_items")
data class MaintenanceItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val intervalKm: Int?,
    val intervalDays: Int?,
    val lastDoneKm: Double? = null,
    val lastDoneAtMs: Long? = null,
) {
    fun toModel() = MaintenanceItem(id, name, intervalKm, intervalDays, lastDoneKm, lastDoneAtMs)
}

/**
 * Hodômetro da moto = [offsetKm] + soma dos trajetos gravados. Ao informar o valor do painel, o app
 * calcula o deslocamento para que a soma bata (ver [app.hunter.maintenance.Odometer]).
 */
@Entity(tableName = "odometer")
data class OdometerEntity(
    @PrimaryKey val id: Int = 1,
    val offsetKm: Double = 0.0,
    /** Capacidade do tanque (L), usada na autonomia estimada. Hunter 350 ≈ 13 L. */
    val tankLiters: Double = 13.0,
)
