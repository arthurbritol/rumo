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
    override suspend fun markClean(docs: List<SyncDoc>) { docs.forEach { dirtyKeys -= it.kind to it.gid } }
    override suspend fun odometer() = odo
    override suspend fun applyOdometer(o: OdometerDoc) { odo = o; odoDirty = false }
    override suspend fun profile() = prof
    override suspend fun applyProfile(p: UserProfile) { prof = p; profDirty = false }
    override suspend fun hasDirtyOdometerOrProfile() = odoDirty || profDirty
    override suspend fun clearAll() { docs.clear(); dirtyKeys.clear(); odo = null; prof = null; cleared = true }
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

    override suspend fun push(docs: List<SyncDoc>) {
        docs.forEach { this.docs[it.kind to it.gid] = it }
        pushed += docs
    }

    override suspend fun pushOdometer(o: OdometerDoc) { odo = o }
    override suspend fun pushProfile(p: UserProfile) { prof = p }
    override suspend fun markDeletionPending() { pending = true; events += "pending" }
    override suspend fun deletionPending() = pending
    override suspend fun deleteAllUserData() {
        events += "delete"
        docs.clear(); odo = null; prof = null
        if (failDeleteOnce) { failDeleteOnce = false; throw IOException("falha no meio") }
        pending = false
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
    fun `deleteAccountData marca pendente antes de apagar`() = runTest {
        remote.docs[Kind.FUELUP to "a"] = fuel("a")
        engine.deleteAccountData()
        assertEquals(listOf("pending", "delete"), remote.events)
        assertTrue(remote.docs.isEmpty())
        assertFalse(remote.deletionPending())
        engine.deleteAccountData() // idempotente
        assertTrue(remote.docs.isEmpty())
    }

    @Test
    fun `exclusão interrompida (falha no meio) é retomada por resumePendingDeletion`() = runTest {
        remote.docs[Kind.FUELUP to "a"] = fuel("a")
        remote.failDeleteOnce = true
        assertFailsWith<IOException> { engine.deleteAccountData() }
        assertTrue(remote.deletionPending())

        assertTrue(engine.resumePendingDeletion())
        assertFalse(remote.deletionPending())
        assertTrue(remote.docs.isEmpty())
        assertFalse(engine.resumePendingDeletion())
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
}
