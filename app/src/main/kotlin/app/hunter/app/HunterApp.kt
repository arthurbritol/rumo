package app.hunter.app

import android.app.Application
import androidx.room.Room
import app.hunter.app.data.AppDatabase
import app.hunter.app.account.AccountConfig
import app.hunter.app.data.PlaceDao
import app.hunter.app.data.SyncDao
import app.hunter.app.fuel.FuelPriceRepository
import app.hunter.app.fuel.FuelPriceSyncWorker
import app.hunter.maintenance.data.MaintenanceDao
import app.hunter.trips.data.RoomTripStore
import app.hunter.trips.data.TripDao
import app.hunter.trips.data.TripStore
import app.hunter.trips.data.TripsGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class HunterApp : Application(), TripsGraph {

    private val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "hunter.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .build()
    }
    val placeDao: PlaceDao by lazy { database.placeDao() }
    val syncDao: SyncDao by lazy { database.syncDao() }
    val tripDao: TripDao by lazy { database.tripDao() }
    val fuelPriceRepository: FuelPriceRepository by lazy {
        FuelPriceRepository(
            dao = database.anpFuelDao(),
            assetLoader = { name ->
                try {
                    assets.open(name)
                } catch (_: Exception) {
                    null
                }
            },
        )
    }

    /** Conta e sincronização (uma por processo). */
    val account: AccountGraph by lazy { AccountGraph(database, this) }

    override val tripStore: TripStore by lazy { RoomTripStore(database.tripDao()) }

    override fun onCreate() {
        super.onCreate()
        ReminderWorker.schedule(this)
        if (AccountConfig.isAvailable(this)) account.start()
        FuelPriceSyncWorker.scheduleWeeklySync(this)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                fuelPriceRepository.ensureSeedLoaded()
            } catch (_: Exception) {
                // Falha no carregamento inicial da semente é não fatal
            }
        }
    }
    val maintenanceDao: MaintenanceDao by lazy { database.maintenanceDao() }
}
