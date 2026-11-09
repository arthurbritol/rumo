package app.hunter.sync

import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeLocalStore : LocalStore {
    val docs = LinkedHashMap<Pair<Kind, String>, SyncDoc>()
    val dirtyKeys = mutableSetOf<Pair<Kind, String>>()
    var odo: OdometerDoc? = null
    var prof: UserProfile? = null
    var odoDirty = false
    var profDirty = false
    var cleared = false

    fun put(d: SyncDoc, dirty: Boolean) {
        docs[d.kind to d.gid] = d
        if (dirty) dirtyKeys += d.kind to d.gid
    }

    override suspend fun all() = docs.values.toList()
    override suspend fun dirty() = dirtyKeys.mapNotNull { docs[it] }
    override suspend fun apply(docs: List<SyncDoc>) = docs.forEach { put(it, false); dirtyKeys -= it.kind to it.gid }
    /** Como o contrato: só limpa se a versão (updatedAtMs) da linha ainda é a do doc passado. */
    override suspend fun markClean(docs: List<SyncDoc>) {
        docs.forEach { if (this.docs[it.kind to it.gid]?.updatedAtMs == it.updatedAtMs) dirtyKeys -= it.kind to it.gid }
    }
    override suspend fun odometer() = odo
    override suspend fun applyOdometer(o: OdometerDoc, dirty: Boolean) { odo = o; odoDirty = dirty }
    override suspend fun profile() = prof
    override suspend fun applyProfile(p: UserProfile) { prof = p; profDirty = false }
    var afterDirtyCheck: (() -> Unit)? = null
    override suspend fun hasDirtyOdometerOrProfile(): Boolean {
        val r = odoDirty || profDirty
        afterDirtyCheck?.let { afterDirtyCheck = null; it() }
        return r
    }
    override suspend fun clearAll() {
        check(dirtyKeys.isEmpty() && !odoDirty && !profDirty) { "há linha suja" }
        docs.clear(); dirtyKeys.clear(); odo = null; prof = null; cleared = true }
}

class FakeRemoteStore : RemoteStore {
    val docs = LinkedHashMap<Pair<Kind, String>, SyncDoc>()
    var odo: OdometerDoc? = null
    var prof: UserProfile? = null
    var pending = false
    var fetchError: IOException? = null
    var failDeleteOnce = false
    val pushed = mutableListOf<SyncDoc>()
    val events = mutableListOf<String>()

    override suspend fun fetchAll(): RemoteSnapshot {
        fetchError?.let { throw it }
        return RemoteSnapshot(docs.values.toList(), odo, prof)
    }

    var pushError: IOException? = null
    var pushOdoError: IOException? = null
    var onPush: (() -> Unit)? = null

    override suspend fun push(docs: List<SyncDoc>) {
        pushError?.let { throw it }
        onPush?.invoke()
        docs.forEach { this.docs[it.kind to it.gid] = it }
        pushed += docs
    }

    override suspend fun pushOdometer(o: OdometerDoc) { pushOdoError?.let { throw it }; odo = o }
    var pushProfileError: IOException? = null
    override suspend fun pushProfile(p: UserProfile) { pushProfileError?.let { throw it }; prof = p }
    override suspend fun markDeletionPending() { pending = true; events += "pending" }
    override suspend fun deletionPending() = pending
    override suspend fun deleteAllUserData() {
        events += "delete"
        docs.clear(); odo = null; prof = null
        if (failDeleteOnce) { failDeleteOnce = false; throw IOException("falha no meio") }
        // A marca é permanente: nunca sai.
    }
}

class SyncEngineTest {
    private val now = 1_000_000_000L
    private val local = FakeLocalStore()
    private val remote = FakeRemoteStore()
    private val engine = SyncEngine(local, remote) { now }

    private fun fuel(gid: String, at: Long = 100, liters: Double = 10.0, date: Long = 5_000L) =
        SyncDoc(
            Kind.FUELUP, gid, at, false,
            mapOf("dateMs" to date, "odometerKm" to 1000.0, "liters" to liters, "totalCost" to 60.0),
        )

    private val perfil = UserProfile("Ana", "Honda", "Hunter 350", 2024)

    @Test
    fun `primeiro login em aparelho com dados e conta com dados une sem duplicar`() = runTest {
        local.put(fuel("a", date = 1), dirty = true)
        local.put(fuel("dup-b", date = 2), dirty = true)
        remote.docs[Kind.FUELUP to "dup-a"] = fuel("dup-a", date = 2)
        remote.docs[Kind.FUELUP to "r"] = fuel("r", date = 3)

        engine.firstSync()

        val vivos = local.all().filter { !it.deleted }.map { it.gid }.sorted()
        assertEquals(listOf("a", "dup-a", "r"), vivos)
        val vivosRemoto = remote.docs.values.filter { !it.deleted }.map { it.gid }.sorted()
        assertEquals(vivos, vivosRemoto)
        assertTrue(local.dirty().isEmpty())
    }

    @Test
    fun `primeiro login em aparelho vazio traz tudo da conta`() = runTest {
        remote.docs[Kind.FUELUP to "r"] = fuel("r")
        remote.odo = OdometerDoc(10.0, 5.0, 100)
        remote.prof = perfil

        engine.firstSync()

        assertEquals(listOf(fuel("r")), local.all())
        assertEquals(remote.odo, local.odometer())
        assertEquals(perfil, local.profile())
        assertTrue(remote.pushed.isEmpty())
    }

    @Test
    fun `primeiro login só com perfil local sobe o perfil e com os dois o remoto vence`() = runTest {
        local.prof = perfil
        engine.firstSync()
        assertEquals(perfil, remote.prof)

        val outro = perfil.copy(displayName = "Bia")
        local.prof = perfil
        remote.prof = outro
        engine.firstSync()
        assertEquals(outro, local.profile())
    }

    @Test
    fun `primeiro login com perfil local sujo mantém o local`() = runTest {
        local.prof = perfil
        local.profDirty = true
        remote.prof = perfil.copy(displayName = "Bia")
        engine.firstSync()
        assertEquals(perfil, remote.prof)
        assertFalse(local.hasDirtyOdometerOrProfile())
    }

    @Test
    fun `syncNow sobe só o sujo e marca limpo`() = runTest {
        val limpo = fuel("c", date = 1)
        val sujo = fuel("d", date = 2)
        local.put(limpo, dirty = false)
        local.put(sujo, dirty = true)
        remote.docs[Kind.FUELUP to "c"] = limpo
        remote.docs[Kind.FUELUP to "r"] = fuel("r", date = 3)

        engine.syncNow()

        assertEquals(listOf(sujo), remote.pushed)
        assertTrue(local.dirty().isEmpty())
        assertEquals(setOf("c", "d", "r"), local.all().map { it.gid }.toSet())
    }

    @Test
    fun `syncNow sobe hodômetro e perfil sujos`() = runTest {
        local.odo = OdometerDoc(1.0, 2.0, 100); local.odoDirty = true
        local.prof = perfil; local.profDirty = true
        engine.syncNow()
        assertEquals(OdometerDoc(1.0, 2.0, 100), remote.odo)
        assertEquals(perfil, remote.prof)
        assertFalse(local.hasDirtyOdometerOrProfile())
    }

    @Test
    fun `canSignOut é falso com registro sujo, hodômetro sujo ou perfil sujo`() = runTest {
        assertTrue(engine.canSignOut())
        local.put(fuel("a"), dirty = true)
        assertFalse(engine.canSignOut())
        local.dirtyKeys.clear()
        local.odoDirty = true
        assertFalse(engine.canSignOut())
        local.odoDirty = false
        local.profDirty = true
        assertFalse(engine.canSignOut())
    }

    @Test
    fun `signOutWipe limpa o local e não toca no remoto`() = runTest {
        local.put(fuel("a"), dirty = false)
        remote.docs[Kind.FUELUP to "a"] = fuel("a")
        engine.signOutWipe()
        assertTrue(local.cleared)
        assertTrue(local.all().isEmpty())
        assertEquals(1, remote.docs.size)
        assertTrue(remote.events.isEmpty() && remote.pushed.isEmpty())
    }

    @Test
    fun `signOutWipe com pendência lança`() = runTest {
        local.put(fuel("a"), dirty = true)
        assertFailsWith<IllegalStateException> { engine.signOutWipe() }
        assertFalse(local.cleared)
        assertEquals(1, local.all().size)
    }

    @Test
    fun `deleteAccountData marca pendente antes de apagar e a marca fica para sempre`() = runTest {
        remote.docs[Kind.FUELUP to "a"] = fuel("a")
        engine.deleteAccountData()
        assertEquals(listOf("pending", "delete"), remote.events)
        assertTrue(remote.docs.isEmpty())
        assertTrue(remote.deletionPending())
        engine.deleteAccountData() // idempotente
        assertTrue(remote.docs.isEmpty())
        assertTrue(remote.deletionPending())
    }

    @Test
    fun `conta apagada nunca volta a receber envio de outro aparelho`() = runTest {
        engine.deleteAccountData()
        // aparelho B, ainda logado com a mesma conta, com tudo sujo
        local.put(fuel("b"), dirty = true)
        local.odo = OdometerDoc(1.0, 2.0, 100); local.odoDirty = true
        local.prof = perfil; local.profDirty = true
        repeat(3) {
            assertFailsWith<DeletionPendingException> { engine.syncNow() }
            assertFailsWith<DeletionPendingException> { engine.firstSync() }
        }
        assertTrue(remote.pushed.isEmpty() && remote.docs.isEmpty())
        assertNull(remote.odo)
        assertNull(remote.prof)
        assertTrue(remote.deletionPending())
    }

    @Test
    fun `exclusão interrompida (falha no meio) é retomada por resumePendingDeletion`() = runTest {
        remote.docs[Kind.FUELUP to "a"] = fuel("a")
        remote.failDeleteOnce = true
        assertFailsWith<IOException> { engine.deleteAccountData() }
        assertTrue(remote.deletionPending())

        assertTrue(engine.resumePendingDeletion())
        assertTrue(remote.deletionPending())
        assertTrue(remote.docs.isEmpty())
        assertTrue(engine.resumePendingDeletion()) // marca permanente: continua pendente (idempotente)
    }

    @Test
    fun `firstSync falha de rede não altera o local`() = runTest {
        local.put(fuel("a"), dirty = true)
        remote.fetchError = IOException("sem rede")
        assertFailsWith<IOException> { engine.firstSync() }
        assertEquals(listOf(fuel("a")), local.all())
        assertEquals(1, local.dirty().size)
        assertNull(local.profile())
        assertTrue(remote.pushed.isEmpty())
    }

    @Test
    fun `syncNow não sobrescreve edição remota mais nova com edição local mais antiga`() = runTest {
        local.put(fuel("d", at = 100, liters = 11.0), dirty = true)
        remote.docs[Kind.FUELUP to "d"] = fuel("d", at = 200, liters = 12.0)
        engine.syncNow()
        assertTrue(remote.pushed.isEmpty())
        assertEquals(fuel("d", at = 200, liters = 12.0), remote.docs[Kind.FUELUP to "d"])
        assertEquals(listOf(fuel("d", at = 200, liters = 12.0)), local.all())
        assertTrue(local.dirty().isEmpty())
    }

    @Test
    fun `sync com exclusão pendente conclui a exclusão, não sobe nada e avisa`() = runTest {
        local.put(fuel("a"), dirty = true)
        remote.docs[Kind.FUELUP to "x"] = fuel("x")
        remote.pending = true
        assertFailsWith<DeletionPendingException> { engine.syncNow() }
        assertTrue(remote.docs.isEmpty() && remote.pushed.isEmpty())
        assertTrue(remote.pending)
        assertFailsWith<DeletionPendingException> { engine.firstSync() }
        assertTrue(remote.docs.isEmpty() && remote.pushed.isEmpty())
        assertEquals(1, local.dirty().size)
    }

    @Test
    fun `signOutWipe propaga a falha se uma edição chegou depois de canSignOut`() = runTest {
        local.put(fuel("a"), dirty = false)
        local.afterDirtyCheck = { local.put(fuel("a", at = 500), dirty = true) }
        assertFailsWith<IllegalStateException> { engine.signOutWipe() }
        assertFalse(local.cleared)
        assertEquals(listOf(fuel("a", at = 500)), local.all())
    }

    @Test
    fun `edição concorrente durante o syncNow continua suja`() = runTest {
        local.put(fuel("d", at = 100), dirty = true)
        remote.onPush = { local.put(fuel("d", at = 200, liters = 99.0), dirty = true) }
        engine.syncNow()
        assertEquals(listOf(fuel("d", at = 200, liters = 99.0)), local.dirty())
    }

    @Test
    fun `falha no push deixa o local intacto e a nova tentativa converge`() = runTest {
        local.put(fuel("a"), dirty = true)
        remote.docs[Kind.FUELUP to "r"] = fuel("r", date = 3)
        remote.pushError = IOException("sem rede")
        assertFailsWith<IOException> { engine.syncNow() }
        assertEquals(listOf(fuel("a")), local.all())
        assertEquals(1, local.dirty().size)
        remote.pushError = null
        engine.syncNow()
        assertTrue(local.dirty().isEmpty())
        assertEquals(setOf("a", "r"), local.all().map { it.gid }.toSet())
        assertEquals(setOf("a", "r"), remote.docs.keys.map { it.second }.toSet())
    }

    @Test
    fun `falha no pushOdometer deixa o local intacto e a nova tentativa converge`() = runTest {
        local.odo = OdometerDoc(1.0, 2.0, 100); local.odoDirty = true
        remote.pushOdoError = IOException("sem rede")
        assertFailsWith<IOException> { engine.syncNow() }
        assertTrue(local.hasDirtyOdometerOrProfile())
        remote.pushOdoError = null
        engine.syncNow()
        assertFalse(local.hasDirtyOdometerOrProfile())
        assertEquals(OdometerDoc(1.0, 2.0, 100), remote.odo)
    }

    @Test
    fun `perfil recusado pelo remoto não impede aplicar e limpar os registros, e o erro chega ao fim`() = runTest {
        local.put(fuel("a", date = 1), dirty = true)
        remote.docs[Kind.FUELUP to "r"] = fuel("r", date = 2)
        local.prof = perfil; local.profDirty = true
        remote.pushProfileError = IOException("recusado pelas regras")
        assertFailsWith<IOException> { engine.syncNow() }
        assertEquals(setOf("a", "r"), local.all().map { it.gid }.toSet()) // o remoto foi aplicado
        assertTrue(local.dirty().isEmpty()) // o que subiu foi marcado limpo
        assertEquals(setOf("a", "r"), remote.docs.keys.map { it.second }.toSet())
        assertTrue(local.profDirty) // o perfil continua pendente
        remote.pushProfileError = null
        engine.syncNow()
        assertEquals(perfil, remote.prof)
        assertFalse(local.hasDirtyOdometerOrProfile())
    }

    @Test
    fun `hodômetro recusado pelo remoto não impede o perfil nem os registros`() = runTest {
        local.put(fuel("a"), dirty = true)
        local.odo = OdometerDoc(1.0, 2.0, 100); local.odoDirty = true
        local.prof = perfil; local.profDirty = true
        remote.pushOdoError = IOException("sem rede")
        assertFailsWith<IOException> { engine.syncNow() }
        assertTrue(local.dirty().isEmpty())
        assertEquals(perfil, remote.prof)
        assertFalse(local.profDirty)
        assertTrue(local.odoDirty)
    }

    // ---------- hodômetro x viagens trazidas pela mesclagem ----------

    private fun trip(gid: String, ended: Long, km: Double, at: Long = 10) = SyncDoc(
        Kind.TRIP, gid, at, false,
        mapOf("startedAtMs" to ended - 1_000, "endedAtMs" to ended, "distanceM" to km * 1000),
    )

    /** O que a Garagem mostra: deslocamento + soma das viagens vivas do aparelho. */
    private suspend fun shownKm(): Double =
        local.odometer()!!.offsetKm + local.all().filter { it.kind == Kind.TRIP && !it.deleted }
            .sumOf { (it.fields["distanceM"] as Number).toDouble() / 1000 }

    @Test
    fun `reinstalar como visitante e depois entrar - hodometro mostrado igual ao painel`() = runTest {
        // nuvem: 3 viagens antigas e o hodômetro antigo
        remote.docs[Kind.TRIP to "t1"] = trip("t1", 1_000, 100.0)
        remote.docs[Kind.TRIP to "t2"] = trip("t2", 2_000, 200.0)
        remote.docs[Kind.TRIP to "t3"] = trip("t3", 3_000, 300.0)
        remote.odo = OdometerDoc(9_400.0, 13.0, 5_000) // 9400 + 600 = 10000 no painel
        // aparelho reinstalado: cadastro curto com o painel 10500, sem viagens, mais novo
        local.odo = OdometerDoc(10_500.0, 13.0, 900_000); local.odoDirty = true

        engine.firstSync()

        assertEquals(10_500.0, shownKm(), 1e-9)
        assertEquals(local.odometer(), remote.odo)
        assertEquals(now, remote.odo!!.updatedAtMs)
        assertFalse(local.hasDirtyOdometerOrProfile())
        // idempotente: um segundo sync não muda nada
        engine.syncNow()
        assertEquals(10_500.0, shownKm(), 1e-9)
        assertEquals(local.odometer(), remote.odo)
    }

    @Test
    fun `os dois lados com viagens - cada vencedor desconta só as do outro lado`() = runTest {
        local.put(trip("l", 1_000, 10.0), dirty = true)
        remote.docs[Kind.TRIP to "r"] = trip("r", 1_100, 20.0)
        // local vence: o painel (510) foi lido com a viagem l -> deslocamento 500
        local.odo = OdometerDoc(500.0, 13.0, 2_000); local.odoDirty = true
        remote.odo = OdometerDoc(100.0, 13.0, 1_500)
        engine.syncNow()
        assertEquals(510.0, shownKm(), 1e-9)

        // remoto vence: o painel (900) foi lido no outro aparelho com a viagem r
        local.docs.clear(); local.dirtyKeys.clear(); remote.docs.clear()
        local.put(trip("l2", 1_000, 10.0), dirty = true)
        remote.docs[Kind.TRIP to "r2"] = trip("r2", 1_100, 20.0)
        local.odo = OdometerDoc(1.0, 13.0, 1_000); local.odoDirty = false
        remote.odo = OdometerDoc(880.0, 13.0, 3_000)
        engine.syncNow()
        assertEquals(900.0, shownKm(), 1e-9)
    }

    @Test
    fun `viagem do outro lado terminada depois da leitura do painel soma normalmente`() = runTest {
        remote.docs[Kind.TRIP to "r"] = trip("r", 5_000, 20.0) // rodou depois da leitura local
        local.odo = OdometerDoc(500.0, 13.0, 2_000); local.odoDirty = true
        engine.syncNow()
        assertEquals(520.0, shownKm(), 1e-9)
        assertEquals(OdometerDoc(500.0, 13.0, 2_000), remote.odo) // sem ajuste
    }

    @Test
    fun `sem viagens de um lado o hodometro nao muda`() = runTest {
        local.put(trip("l", 1_000, 10.0), dirty = true)
        local.odo = OdometerDoc(500.0, 13.0, 2_000); local.odoDirty = true
        remote.odo = OdometerDoc(100.0, 13.0, 1_500)
        engine.syncNow()
        assertEquals(OdometerDoc(500.0, 13.0, 2_000), remote.odo)
        assertEquals(510.0, shownKm(), 1e-9)
    }

    @Test
    fun `viagem duplicada (outro gid) nao e descontada nem contada duas vezes`() = runTest {
        local.put(trip("dup-b", 1_000, 10.0), dirty = true)
        remote.docs[Kind.TRIP to "dup-a"] = trip("dup-a", 1_000, 10.0)
        remote.docs[Kind.TRIP to "r"] = trip("r", 1_100, 20.0)
        local.odo = OdometerDoc(500.0, 13.0, 2_000); local.odoDirty = true
        engine.syncNow()
        assertEquals(510.0, shownKm(), 1e-9)
        engine.syncNow()
        assertEquals(510.0, shownKm(), 1e-9)
    }

    @Test
    fun `ajuste que nao sobe fica sujo no aparelho e sobe depois`() = runTest {
        remote.docs[Kind.TRIP to "r"] = trip("r", 1_100, 20.0)
        local.odo = OdometerDoc(500.0, 13.0, 2_000); local.odoDirty = true
        remote.pushOdoError = IOException("sem rede")
        assertFailsWith<IOException> { engine.syncNow() }
        assertEquals(500.0, shownKm(), 1e-9) // a viagem trazida já entrou e o ajuste também
        assertTrue(local.odoDirty)
        remote.pushOdoError = null
        engine.syncNow()
        assertEquals(500.0, shownKm(), 1e-9)
        assertEquals(local.odometer(), remote.odo)
        assertFalse(local.odoDirty)
    }

    @Test
    fun `marcar exclusao pendente e consultar passam pelo remoto`() = runTest {
        assertFalse(engine.isDeletionPending())
        engine.markDeletionPending()
        assertTrue(remote.pending)
        assertTrue(engine.isDeletionPending())
    }
}
