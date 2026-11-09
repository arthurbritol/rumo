package app.hunter.app.sync

import app.hunter.sync.Kind
import app.hunter.sync.LocalStore
import app.hunter.sync.OdometerDoc
import app.hunter.sync.RemoteSnapshot
import app.hunter.sync.RemoteStore
import app.hunter.sync.SyncDoc
import app.hunter.sync.SyncEngine
import app.hunter.sync.UserProfile
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SyncSchedulerTest {
    private class FakeLocal(var dirtyDocs: List<SyncDoc> = emptyList()) : LocalStore {
        override suspend fun all() = dirtyDocs
        override suspend fun dirty() = dirtyDocs
        override suspend fun apply(docs: List<SyncDoc>) {}
        override suspend fun markClean(docs: List<SyncDoc>) { dirtyDocs = dirtyDocs - docs.toSet() }
        override suspend fun odometer(): OdometerDoc? = null
        override suspend fun applyOdometer(o: OdometerDoc) {}
        override suspend fun profile(): UserProfile? = null
        override suspend fun applyProfile(p: UserProfile) {}
        override suspend fun hasDirtyOdometerOrProfile() = false
        override suspend fun clearAll() {}
    }

    private class FakeRemote : RemoteStore {
        var fetches = 0
        var failWith: Exception? = null
        override suspend fun fetchAll(): RemoteSnapshot {
            fetches++
            failWith?.let { throw it }
            return RemoteSnapshot(emptyList(), null, null)
        }
        override suspend fun push(docs: List<SyncDoc>) {}
        override suspend fun pushOdometer(o: OdometerDoc) {}
        override suspend fun pushProfile(p: UserProfile) {}
        override suspend fun markDeletionPending() {}
        override suspend fun deletionPending() = false
        override suspend fun deleteAllUserData() {}
    }

    private val doc = SyncDoc(Kind.FUELUP, "g1", 10L, false, mapOf("liters" to 5.0))

    @Test
    fun variasChamadasSeguidasViramUmaSincronizacao() = runTest {
        val remote = FakeRemote()
        val sched = SyncScheduler(SyncEngine(FakeLocal(listOf(doc)), remote) { 100L }, backgroundScope, 5_000) { true }
        repeat(5) { sched.requestSync(); advanceTimeBy(500) }
        assertEquals(0, remote.fetches)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, remote.fetches)
    }

    @Test
    fun erroDeRedeMantemSujoEReTentaQuandoAConexaoVolta() = runTest {
        val local = FakeLocal(listOf(doc))
        val remote = FakeRemote().apply { failWith = IOException("sem rede") }
        val sched = SyncScheduler(SyncEngine(local, remote) { 100L }, backgroundScope, 1_000) { true }
        sched.requestSync()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, remote.fetches) // sem laço de retentativas
        assertEquals(listOf(doc), local.dirty())
        remote.failWith = null
        sched.syncOnConnectivity()
        runCurrent()
        assertEquals(2, remote.fetches)
        assertTrue(local.dirty().isEmpty())
    }

    @Test
    fun naoSincronizaSemUsuarioLogado() = runTest {
        val remote = FakeRemote()
        val sched = SyncScheduler(SyncEngine(FakeLocal(listOf(doc)), remote), backgroundScope, 1_000) { false }
        sched.requestSync()
        sched.syncOnConnectivity()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(0, remote.fetches)
    }
}
