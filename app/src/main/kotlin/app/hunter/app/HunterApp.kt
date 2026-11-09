package app.hunter.app

import android.app.Application
import androidx.room.Room
import app.hunter.app.data.AppDatabase
import app.hunter.maintenance.data.MaintenanceDao
import app.hunter.trips.data.RoomTripStore
import app.hunter.trips.data.TripStore
import app.hunter.trips.data.TripsGraph

class HunterApp : Application(), TripsGraph {

    private val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "hunter.db").build()
    }

    override val tripStore: TripStore by lazy { RoomTripStore(database.tripDao()) }
    val maintenanceDao: MaintenanceDao by lazy { database.maintenanceDao() }
}
