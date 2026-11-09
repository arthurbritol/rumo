package app.hunter.maintenance.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.hunter.model.Fuelup
import app.hunter.model.MaintenanceItem
import java.util.UUID

@Entity(tableName = "fuelups", indices = [Index("gid", unique = true)])
data class FuelupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateMs: Long,
    val odometerKm: Double,
    val liters: Double,
    val totalCost: Double,
    val fullTank: Boolean,
    /** Id global (igual em todos os aparelhos); o [id] acima é só local. */
    @ColumnInfo(defaultValue = "''") val gid: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "0") val updatedAtMs: Long = System.currentTimeMillis(),
    /** true = alteração ainda não enviada à nuvem. */
    @ColumnInfo(defaultValue = "1") val dirty: Boolean = true,
) {
    fun toModel() = Fuelup(id, dateMs, odometerKm, liters, totalCost, fullTank)

    fun touch() = copy(updatedAtMs = System.currentTimeMillis(), dirty = true)
}

@Entity(tableName = "maintenance_items", indices = [Index("gid", unique = true)])
data class MaintenanceItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val intervalKm: Int?,
    val intervalDays: Int?,
    val lastDoneKm: Double? = null,
    val lastDoneAtMs: Long? = null,
    /** Id global (igual em todos os aparelhos); o [id] acima é só local. */
    @ColumnInfo(defaultValue = "''") val gid: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "0") val updatedAtMs: Long = System.currentTimeMillis(),
    /** true = alteração ainda não enviada à nuvem. */
    @ColumnInfo(defaultValue = "1") val dirty: Boolean = true,
) {
    fun toModel() = MaintenanceItem(id, name, intervalKm, intervalDays, lastDoneKm, lastDoneAtMs)

    fun touch() = copy(updatedAtMs = System.currentTimeMillis(), dirty = true)
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
    @ColumnInfo(defaultValue = "0") val updatedAtMs: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "1") val dirty: Boolean = true,
) {
    fun touch() = copy(updatedAtMs = System.currentTimeMillis(), dirty = true)
}
