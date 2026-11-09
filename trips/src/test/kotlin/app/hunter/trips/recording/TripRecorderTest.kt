package app.hunter.trips.recording

import app.hunter.model.TrackPoint
import app.hunter.model.TripStats
import app.hunter.model.TripSummary
import app.hunter.trips.data.ActiveTrip
import app.hunter.trips.data.TripStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeStore : TripStore {
    class Trip(val id: Long, val startedAtMs: Long, var endedAtMs: Long? = null, var stats: TripStats = TripStats())

    val trips = mutableListOf<Trip>()
    val points = mutableMapOf<Long, MutableList<Pair<Int, TrackPoint>>>()
    var statsUpdates = 0
    private var nextId = 1L

    override suspend fun createTrip(startedAtMs: Long): Long = nextId++.also { trips += Trip(it, startedAtMs) }
    override suspend fun appendPoint(tripId: Long, index: Int, point: TrackPoint) {
        points.getOrPut(tripId) { mutableListOf() } += index to point
    }
    override suspend fun updateStats(tripId: Long, stats: TripStats) {
        statsUpdates++
        trips.first { it.id == tripId }.stats = stats
    }
    override suspend fun finishTrip(tripId: Long, endedAtMs: Long, stats: TripStats) {
        trips.first { it.id == tripId }.also { it.endedAtMs = endedAtMs; it.stats = stats }
    }
    override suspend fun deleteTrip(tripId: Long) {
        trips.removeAll { it.id == tripId }
        points.remove(tripId)
    }
    override suspend fun activeTrip() = trips.lastOrNull { it.endedAtMs == null }?.let { ActiveTrip(it.id, it.startedAtMs) }
    override suspend fun points(tripId: Long) = points[tripId].orEmpty().sortedBy { it.first }.map { it.second }
    override fun observeTrips(): Flow<List<TripSummary>> =
        MutableStateFlow(trips.map { TripSummary(it.id, it.startedAtMs, it.endedAtMs, it.stats) })
    override fun observePoints(tripId: Long): Flow<List<TrackPoint>> = MutableStateFlow(points[tripId].orEmpty()).map { l -> l.map { it.second } }
}

class TripRecorderTest {

    private fun north(m: Double) = m / 111_194.9
    private fun pt(tSec: Long, northM: Double, speed: Float? = 10f, acc: Float? = 5f) =
        TrackPoint(tSec * 1000, -8.05 + north(northM), -34.9, speedMps = speed, accuracyM = acc)

    @Test
    fun `cria o trajeto, grava so pontos aceitos e finaliza com estatisticas`() = runTest {
        val store = FakeStore()
        val states = mutableListOf<RecordingState>()
        val rec = TripRecorder(store, clock = { 1_000_000L }, statsFlushEvery = 3, onState = { states += it })

        val id = rec.startOrResume()
        assertTrue(rec.isRecording)
        for (t in 0..10L) rec.onPoint(pt(t, t * 10.0))
        rec.onPoint(pt(11, 110.0, acc = 90f)) // precisão ruim: ignorado

        assertEquals(11, store.points(id).size)
        assertEquals(listOf(0, 1, 2), store.points[id]!!.take(3).map { it.first })
        assertTrue(store.statsUpdates >= 3, "atualiza estatísticas periodicamente")
        assertEquals(id, states.last().tripId)

        val stats = rec.finish()
        assertNotNull(stats)
        assertEquals(100.0, stats.distanceM, 1.0)
        assertEquals(1_000_000L, store.trips.single().endedAtMs)
        assertEquals(stats, store.trips.single().stats)
        assertEquals(RecordingState(), states.last())
        assertTrue(!rec.isRecording)
    }

    @Test
    fun `trajeto sem nenhum ponto aceito e descartado`() = runTest {
        val store = FakeStore()
        val rec = TripRecorder(store)
        rec.startOrResume()
        rec.onPoint(pt(0, 0.0, acc = 200f))
        assertNull(rec.finish())
        assertTrue(store.trips.isEmpty())
    }

    @Test
    fun `retoma o trajeto aberto continuando indices e estatisticas`() = runTest {
        val store = FakeStore()
        val first = TripRecorder(store)
        val id = first.startOrResume()
        for (t in 0..10L) first.onPoint(pt(t, t * 10.0))
        // "processo morto": o recorder some, o trajeto fica aberto no store

        val second = TripRecorder(store)
        assertEquals(id, second.startOrResume())
        for (t in 11..20L) second.onPoint(pt(t, t * 10.0))
        val stats = second.finish()!!

        assertEquals(1, store.trips.size)
        assertEquals((0..20).toList(), store.points[id]!!.map { it.first })
        assertEquals(200.0, stats.distanceM, 2.0)
    }

    @Test
    fun `startOrResume duas vezes seguidas nao cria outro trajeto`() = runTest {
        val store = FakeStore()
        val rec = TripRecorder(store)
        val a = rec.startOrResume()
        val b = rec.startOrResume()
        assertEquals(a, b)
        assertEquals(1, store.trips.size)
    }

    @Test
    fun `pontos antes de iniciar sao ignorados`() = runTest {
        val store = FakeStore()
        val rec = TripRecorder(store)
        rec.onPoint(pt(0, 0.0))
        assertTrue(store.points.isEmpty())
    }
}
