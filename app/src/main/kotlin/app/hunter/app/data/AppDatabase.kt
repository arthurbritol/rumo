package app.hunter.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.hunter.app.data.anp.AnpCityStatsEntity
import app.hunter.app.data.anp.AnpFuelDao
import app.hunter.app.data.anp.AnpStateStatsEntity
import app.hunter.app.data.anp.AnpStationEntity
import app.hunter.maintenance.data.FuelupEntity
import app.hunter.maintenance.data.MaintenanceDao
import app.hunter.maintenance.data.MaintenanceItemEntity
import app.hunter.maintenance.data.OdometerEntity
import app.hunter.trips.data.TrackPointEntity
import app.hunter.trips.data.TripDao
import app.hunter.trips.data.TripEntity

/**
 * Banco único do app: entidades dos módulos `:trips` e `:maintenance` mais os destinos salvos, para permitir
 * consultas cruzadas entre viagens, abastecimentos e manutenção.
 */
@Database(
    entities = [
        TripEntity::class, TrackPointEntity::class,
        FuelupEntity::class, MaintenanceItemEntity::class, OdometerEntity::class,
        PlaceEntity::class, TombstoneEntity::class,
        AnpStationEntity::class, AnpCityStatsEntity::class, AnpStateStatsEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun maintenanceDao(): MaintenanceDao
    abstract fun placeDao(): PlaceDao
    abstract fun syncDao(): SyncDao
    abstract fun anpFuelDao(): AnpFuelDao

    companion object {
        /** v1 → v2: nome da viagem e destinos salvos. Preserva tudo que já foi gravado. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN title TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS places (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, subtitle TEXT, " +
                        "lat REAL NOT NULL, lon REAL NOT NULL, favorite INTEGER NOT NULL, " +
                        "lastUsedMs INTEGER NOT NULL, useCount INTEGER NOT NULL)",
                )
            }
        }

        /** v2 → v3: id global, data de alteração, marca de "sujo" e lápides para a sincronização com a nuvem. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Migration2To3.statements.forEach { db.execSQL(it) }
            }
        }

        /** v3 → v4: cache local de postos e médias de preços de combustíveis da ANP. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Migration3To4.statements.forEach { db.execSQL(it) }
            }
        }
    }
}
