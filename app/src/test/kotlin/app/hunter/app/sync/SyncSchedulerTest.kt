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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
        override suspend fun applyOdometer(o: OdometerDoc, dirty: Boolean) {}
        override suspend fun profile(): UserProfile? = null
        override suspend fun applyProfile(p: UserProfile) {}
        override suspend fun hasDirtyOdometerOrProfile() = false
        override suspend fun clearAll() {}
    }

    private class FakeRemote(var fetchDelayMs: Long = 0, var hang: Boolean = false) : RemoteStore {
        var fetches = 0
        var active = 0
        var maxActive = 0
        var failWith: Throwable? = null
        override suspend fun fetchAll(): RemoteSnapshot {
            fetches++
            active++
            maxActive = maxOf(maxActive, active)
            try {
                if (hang) awaitCancellation()
                if (fetchDelayMs > 0) delay(fetchDelayMs)
            } finally {
                active--
            }
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
    fun onSyncedSoAposSincronizacaoBemSucedida() = runTest {
        val remote = FakeRemote().apply { failWith = IOException("sem rede") }
        var synced = 0
        val sched = SyncScheduler(
            SyncEngine(FakeLocal(), remote) { 100L }, backgroundScope, 0, 0, onSynced = { synced++ },
        ) { true }
        sched.requestSync(); advanceTimeBy(10); runCurrent()
        assertEquals(0, synced)
        remote.failWith = null
        sched.requestSync(); advanceTimeBy(10); runCurrent()
        assertEquals(1, synced)
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

    private fun engine(remote: FakeRemote) = SyncEngine(FakeLocal(listOf(doc)), remote) { 100L }

    @Test
    fun pedidoDuranteSincronizacaoEmAndamentoGeraExatamenteMais1() = runTest {
        val remote = FakeRemote(fetchDelayMs = 3_000)
        val sched = SyncScheduler(engine(remote), backgroundScope, 1_000) { true }
        sched.requestSync()
        advanceTimeBy(1_500) // janela acabou, sincronização em andamento
        assertEquals(1, remote.fetches)
        sched.requestSync()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(2, remote.fetches)
        assertEquals(1, remote.maxActive)
    }

    @Test
    fun duasSincronizacoesNuncaSeSobrepoem() = runTest {
        val remote = FakeRemote(fetchDelayMs = 5_000)
        val sched = SyncScheduler(engine(remote), backgroundScope, 100, 0) { true }
        sched.requestSync()
        advanceTimeBy(200)
        sched.syncOnConnectivity()
        sched.requestSync()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, remote.maxActive)
    }

    @Test
    fun cincoSinaisDeConexaoSeguidosViramUmaSincronizacao() = runTest {
        val remote = FakeRemote()
        val sched = SyncScheduler(engine(remote), backgroundScope, 1_000) { true }
        repeat(5) { sched.syncOnConnectivity(); advanceTimeBy(1_000) }
        runCurrent()
        assertEquals(1, remote.fetches)
        advanceTimeBy(20_000) // passou o espaçamento mínimo
        sched.syncOnConnectivity()
        runCurrent()
        assertEquals(2, remote.fetches)
    }

    @Test
    fun falhaVaiParaOnErrorECancelamentoNao() = runTest {
        val errors = mutableListOf<Throwable>()
        val remote = FakeRemote().apply { failWith = IOException("x") }
        val sched = SyncScheduler(engine(remote), backgroundScope, 1_000, onError = { errors += it }) { true }
        sched.syncOnConnectivity()
        runCurrent()
        assertEquals(1, errors.size)
        assertTrue(errors[0] is IOException)

        remote.failWith = CancellationException("cancelado")
        advanceTimeBy(20_000)
        sched.syncOnConnectivity()
        runCurrent()
        assertEquals(1, errors.size)
    }

    @Test
    fun cancelarOEscopoParaLimpoSemReportarErro() = runTest {
        val errors = mutableListOf<Throwable>()
        val remote = FakeRemote(hang = true)
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val sched = SyncScheduler(engine(remote), scope, 1_000, onError = { errors += it }) { true }
        sched.syncOnConnectivity()
        runCurrent()
        assertEquals(1, remote.active)
        scope.cancel()
        runCurrent()
        assertEquals(0, remote.active)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun sincronizacaoPenduradaEstouraEmSessentaSegundosEVaiParaOnError() = runTest {
        val errors = mutableListOf<Throwable>()
        val remote = FakeRemote(hang = true)
        val sched = SyncScheduler(engine(remote), backgroundScope, 1_000, onError = { errors += it }) { true }
        sched.syncOnConnectivity()
        advanceTimeBy(59_000)
        assertTrue(errors.isEmpty())
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, errors.size)
        assertTrue(errors[0] is kotlinx.coroutines.TimeoutCancellationException)
        assertEquals(0, remote.active)
    }

    @Test
    fun mudancaDeContaNoMeioDaSincronizacaoAbortaSemEnviarParaOutroUsuario() = runTest {
        var uid: String? = "a"
        val pushed = mutableListOf<String>()
        val errors = mutableListOf<Throwable>()
        class Rec(val id: String) : RemoteStore {
            override suspend fun fetchAll(): RemoteSnapshot { delay(1_000); return RemoteSnapshot(emptyList(), null, null) }
            override suspend fun push(docs: List<SyncDoc>) { pushed += id }
            override suspend fun pushOdometer(o: OdometerDoc) { pushed += id }
            override suspend fun pushProfile(p: UserProfile) { pushed += id }
            override suspend fun markDeletionPending() {}
            override suspend fun deletionPending() = false
            override suspend fun deleteAllUserData() {}
        }
        val gate = SyncGate({ uid })
        val remote = PerUserRemoteStore({ uid }, { gate.pinnedUid }) { Rec(it) }
        val local = FakeLocal(listOf(doc))
        val sched = SyncScheduler(SyncEngine(local, remote) { 100L }, backgroundScope, 0, 0, { errors += it }, gate) { true }
        sched.syncOnConnectivity()
        advanceTimeBy(500)
        uid = "b" // troca de conta com a sincronização da conta "a" em andamento
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(pushed.isEmpty(), "enviou para $pushed")
        assertEquals(1, errors.size)
        assertTrue(errors[0] is IllegalStateException)
        assertEquals(listOf(doc), local.dirty()) // continua sujo: tenta de novo depois
    }

    @Test
    fun sairEsperaASincronizacaoEmAndamentoENuncaSeIntercala() = runTest {
        val log = mutableListOf<String>()
        val gate = SyncGate()
        val remote = object : RemoteStore {
            override suspend fun fetchAll(): RemoteSnapshot { log += "sync-inicio"; delay(5_000); log += "sync-fim"; return RemoteSnapshot(emptyList(), null, null) }
            override suspend fun push(docs: List<SyncDoc>) {}
            override suspend fun pushOdometer(o: OdometerDoc) {}
            override suspend fun pushProfile(p: UserProfile) {}
            override suspend fun markDeletionPending() {}
            override suspend fun deletionPending() = false
            override suspend fun deleteAllUserData() {}
        }
        val sched = SyncScheduler(SyncEngine(FakeLocal(), remote) { 100L }, backgroundScope, 0, 0, {}, gate) { true }
        sched.syncOnConnectivity()
        runCurrent()
        val signOut = backgroundScope.launch { gate.withLock { log += "sair" } } // o que AuthRepository.signOut faz
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(listOf("sync-inicio"), log) // sair ainda espera
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(listOf("sync-inicio", "sync-fim", "sair"), log)
        assertTrue(signOut.isCompleted)
    }
}
