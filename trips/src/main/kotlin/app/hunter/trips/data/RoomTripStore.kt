package app.hunter.trips.data

import app.hunter.model.TrackPoint
import app.hunter.model.TripStats
import app.hunter.model.TripSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomTripStore(private val dao: TripDao) : TripStore {

    override suspend fun createTrip(startedAtMs: Long, title: String?): Long =
        dao.insertTrip(TripEntity(startedAtMs = startedAtMs, title = title))

    override suspend fun renameTrip(tripId: Long, title: String?) {
        val t = dao.getTrip(tripId) ?: return
        dao.updateTrip(t.copy(title = title?.takeIf { it.isNotBlank() }))
    }

    override suspend fun appendPoint(tripId: Long, index: Int, point: TrackPoint) =
        dao.insertPoint(TrackPointEntity.from(tripId, index, point))

    override suspend fun updateStats(tripId: Long, stats: TripStats) {
        val t = dao.getTrip(tripId) ?: return
        dao.updateTrip(t.withStats(stats))
    }

    override suspend fun finishTrip(tripId: Long, endedAtMs: Long, stats: TripStats) {
        val t = dao.getTrip(tripId) ?: return
        dao.updateTrip(t.withStats(stats).copy(endedAtMs = endedAtMs))
    }

    override suspend fun deleteTrip(tripId: Long) = dao.deleteTrip(tripId)

    override suspend fun activeTrip(): ActiveTrip? = dao.getActiveTrip()?.let { ActiveTrip(it.id, it.startedAtMs) }

    override suspend fun points(tripId: Long): List<TrackPoint> = dao.getPoints(tripId).map { it.toPoint() }

    override fun observeTrips(): Flow<List<TripSummary>> = dao.observeTrips().map { list -> list.map { it.toSummary() } }

    override fun observePoints(tripId: Long): Flow<List<TrackPoint>> =
        dao.observePoints(tripId).map { list -> list.map { it.toPoint() } }
}
