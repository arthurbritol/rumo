package app.hunter.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import app.hunter.maintenance.data.FuelupEntity
import app.hunter.maintenance.data.MaintenanceDao
import app.hunter.maintenance.data.MaintenanceItemEntity
import app.hunter.maintenance.data.OdometerEntity
import app.hunter.trips.data.TrackPointEntity
import app.hunter.trips.data.TripDao
import app.hunter.trips.data.TripEntity

/**
 * Banco único do app: entidades dos módulos `:trips` e `:maintenance` agregadas aqui, para permitir
 * consultas entre trajetos, abastecimentos e manutenção.
 */
@Database(
    entities = [
        TripEntity::class, TrackPointEntity::class,
        FuelupEntity::class, MaintenanceItemEntity::class, OdometerEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun maintenanceDao(): MaintenanceDao
}
