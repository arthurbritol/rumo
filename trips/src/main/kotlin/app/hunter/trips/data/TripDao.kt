package app.hunter.trips.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Insert
    suspend fun insertTrip(trip: TripEntity): Long

    @Update
    suspend fun updateTripRaw(trip: TripEntity)

    /** Alteração feita pelo app/usuário: marca como suja, com data de agora. */
    @Transaction
    suspend fun updateTrip(trip: TripEntity) = updateTripRaw(trip.touch())

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun getTrip(id: Long): TripEntity?

    @Query("SELECT * FROM trips WHERE endedAtMs IS NULL ORDER BY startedAtMs DESC LIMIT 1")
    suspend fun getActiveTrip(): TripEntity?

    @Query("SELECT * FROM trips ORDER BY startedAtMs DESC")
    fun observeTrips(): Flow<List<TripEntity>>

    /** Só viagem concluída já pode ter ido para a nuvem; a gravando não deixa lápide. */
    @Query("INSERT OR REPLACE INTO tombstones (kind, gid, deletedAtMs, dirty) SELECT 'TRIP', gid, :now, 1 FROM trips WHERE id = :id AND endedAtMs IS NOT NULL")
    suspend fun tombstoneTrip(id: Long, now: Long)

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun deleteTripRow(id: Long)

    @Transaction
    suspend fun deleteTrip(id: Long) {
        tombstoneTrip(id, System.currentTimeMillis())
        deleteTripRow(id)
    }

    @Insert
    suspend fun insertPoint(point: TrackPointEntity)

    @Query("SELECT * FROM track_points WHERE tripId = :tripId ORDER BY idx")
    suspend fun getPoints(tripId: Long): List<TrackPointEntity>

    @Query("SELECT * FROM track_points WHERE tripId = :tripId ORDER BY idx")
    fun observePoints(tripId: Long): Flow<List<TrackPointEntity>>
}
