package app.hunter.app

import android.app.Application
import androidx.room.Room
import app.hunter.app.data.AppDatabase
import app.hunter.app.account.AccountConfig
import app.hunter.app.data.PlaceDao
import app.hunter.app.data.SyncDao
import app.hunter.maintenance.data.MaintenanceDao
import app.hunter.trips.data.RoomTripStore
import app.hunter.trips.data.TripDao
import app.hunter.trips.data.TripStore
import app.hunter.trips.data.TripsGraph

class HunterApp : Application(), TripsGraph {

    private val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "hunter.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .build()
    }
    val placeDao: PlaceDao by lazy { database.placeDao() }
    val syncDao: SyncDao by lazy { database.syncDao() }
    val tripDao: TripDao by lazy { database.tripDao() }

    /** Conta e sincronização (uma por processo). */
    val account: AccountGraph by lazy { AccountGraph(database, this) }

    override val tripStore: TripStore by lazy { RoomTripStore(database.tripDao()) }

    override fun onCreate() {
        super.onCreate()
        ReminderWorker.schedule(this)
        if (AccountConfig.isAvailable(this)) account.start()
    }
    val maintenanceDao: MaintenanceDao by lazy { database.maintenanceDao() }
}
