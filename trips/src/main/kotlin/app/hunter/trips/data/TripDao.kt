package app.hunter.trips.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Insert
    suspend fun insertTrip(trip: TripEntity): Long

    @Update
    suspend fun updateTrip(trip: TripEntity)

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun getTrip(id: Long): TripEntity?

    @Query("SELECT * FROM trips WHERE endedAtMs IS NULL ORDER BY startedAtMs DESC LIMIT 1")
    suspend fun getActiveTrip(): TripEntity?

    @Query("SELECT * FROM trips ORDER BY startedAtMs DESC")
    fun observeTrips(): Flow<List<TripEntity>>

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun deleteTrip(id: Long)

    @Insert
    suspend fun insertPoint(point: TrackPointEntity)

    @Query("SELECT * FROM track_points WHERE tripId = :tripId ORDER BY idx")
    suspend fun getPoints(tripId: Long): List<TrackPointEntity>

    @Query("SELECT * FROM track_points WHERE tripId = :tripId ORDER BY idx")
    fun observePoints(tripId: Long): Flow<List<TrackPointEntity>>
}
