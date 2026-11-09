package app.hunter.trips.data

import app.hunter.model.TrackPoint
import app.hunter.model.TripStats
import app.hunter.model.TripSummary
import kotlinx.coroutines.flow.Flow

/** Trajeto que ficou aberto (gravando) — usado para retomar após o processo ser morto. */
data class ActiveTrip(val id: Long, val startedAtMs: Long)

/**
 * Persistência de trajetos, independente de Room para o [app.hunter.trips.recording.TripRecorder]
 * ser testável na JVM com um fake.
 */
interface TripStore {
    suspend fun createTrip(startedAtMs: Long, title: String? = null): Long
    suspend fun renameTrip(tripId: Long, title: String?)
    suspend fun appendPoint(tripId: Long, index: Int, point: TrackPoint)
    suspend fun updateStats(tripId: Long, stats: TripStats)
    suspend fun finishTrip(tripId: Long, endedAtMs: Long, stats: TripStats)
    suspend fun deleteTrip(tripId: Long)

    /** Trajeto ainda aberto, se houver (no máximo um). */
    suspend fun activeTrip(): ActiveTrip?
    suspend fun points(tripId: Long): List<TrackPoint>

    /** Todos os trajetos, mais recentes primeiro (o ativo, se houver, vem junto com endedAtMs = null). */
    fun observeTrips(): Flow<List<TripSummary>>
    fun observePoints(tripId: Long): Flow<List<TrackPoint>>
}

/** Implementada pela [android.app.Application] do app para o serviço achar o [TripStore]. */
interface TripsGraph {
    val tripStore: TripStore
}
