package app.hunter.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VeiculosSyncTest {
    private val now = 1_000_000_000L

    private fun veiculo(gid: String, tipo: String = "MOTO", criadoEm: Long = 100, removidoEm: Long? = null, at: Long = 100, apelido: String = "v") =
        SyncDoc(
            Kind.VEICULO, gid, at, false,
            mapOf(
                "tipo" to tipo, "apelido" to apelido, "marca" to "", "modelo" to "", "ano" to null,
                "tanqueLitros" to 13.0, "consumoPadraoKmL" to null, "criadoEm" to criadoEm, "removidoEm" to removidoEm,
            ),
        )

    private fun abastecimento(gid: String, veiculoId: String? = null) = SyncDoc(
        Kind.FUELUP, gid, 100, false,
        buildMap {
            put("dateMs", 5_000L); put("odometerKm", 1000.0); put("liters", 10.0); put("totalCost", 60.0); put("fullTank", true)
            if (veiculoId != null) put("veiculoId", veiculoId)
        },
    )

    private fun viagem(gid: String, veiculoId: String?, distanceM: Double, started: Long) = SyncDoc(
        Kind.TRIP, gid, 100, false,
        buildMap {
            put("startedAtMs", started); put("endedAtMs", started + 10); put("distanceM", distanceM)
            if (veiculoId != null) put("veiculoId", veiculoId)
        },
    )

    // ---------- veículo legado ----------

    @Test
    fun `sem nenhum veiculo o legado e o id fixo da migracao`() {
        assertEquals(VEICULO_LEGADO_ID, idDoVeiculoLegado(emptyList()))
    }

    @Test
    fun `o veiculo migrado tem prioridade sobre outras motos`() {
        val v = listOf(veiculo("a", criadoEm = 1), veiculo(VEICULO_LEGADO_ID, criadoEm = 500))
        assertEquals(VEICULO_LEGADO_ID, idDoVeiculoLegado(v))
    }

    @Test
    fun `sem o migrado vale a moto nao removida mais antiga e carro nao conta`() {
        val v = listOf(
            veiculo("carro", tipo = "CARRO", criadoEm = 1),
            veiculo("removida", criadoEm = 2, removidoEm = 50),
            veiculo("nova", criadoEm = 300),
            veiculo("velha", criadoEm = 200),
        )
        assertEquals("velha", idDoVeiculoLegado(v))
    }

    @Test
    fun `so motos removidas ainda servem de ultimo recurso antes do id fixo`() {
        assertEquals("r", idDoVeiculoLegado(listOf(veiculo("r", removidoEm = 5), veiculo("c", tipo = "CARRO"))))
    }

    @Test
    fun `documentos antigos sem veiculoId ganham o veiculo legado sem perder campos`() {
        val snap = RemoteSnapshot(
            listOf(
                veiculo("m1", criadoEm = 10),
                abastecimento("f-antigo"),
                abastecimento("f-novo", veiculoId = "outro"),
                viagem("t1", null, 1000.0, 1),
                SyncDoc(Kind.FUELUP, "morto", 100, true, emptyMap()),
            ),
            listOf(OdometerDoc(10.0, 13.0, 100, veiculoId = null)),
            null,
        )
        val r = atribuirVeiculoLegado(snap)
        val porGid = r.docs.associateBy { it.gid }
        assertEquals("m1", porGid["f-antigo"]!!.fields["veiculoId"])
        assertEquals(1000.0, porGid["f-antigo"]!!.fields["odometerKm"])
        assertEquals("outro", porGid["f-novo"]!!.fields["veiculoId"])
        assertEquals("m1", porGid["t1"]!!.fields["veiculoId"])
        assertTrue(porGid["morto"]!!.fields.isEmpty(), "lapide continua sem campos")
        assertEquals(listOf(OdometerDoc(10.0, 13.0, 100, "m1")), r.odometers)
    }

    @Test
    fun `hodometro antigo e novo do mesmo veiculo ficam so com o mais recente`() {
        val snap = RemoteSnapshot(
            emptyList(),
            listOf(OdometerDoc(1.0, 13.0, 100, null), OdometerDoc(2.0, 13.0, 200, VEICULO_LEGADO_ID)),
            null,
        )
        assertEquals(listOf(OdometerDoc(2.0, 13.0, 200, VEICULO_LEGADO_ID)), atribuirVeiculoLegado(snap).odometers)
    }

    // ---------- motor ----------

    @Test
    fun `veiculos sincronizam nos dois sentidos pelo ultimo a escrever`() = runTest {
        val local = FakeLocalStore()
        val remote = FakeRemoteStore()
        local.put(veiculo("a", apelido = "local-novo", at = 300), dirty = true)
        remote.docs[Kind.VEICULO to "a"] = veiculo("a", apelido = "remoto-velho", at = 200)
        remote.docs[Kind.VEICULO to "b"] = veiculo("b", tipo = "CARRO", at = 150)
        SyncEngine(local, remote) { now }.syncNow()
        assertEquals("local-novo", remote.docs[Kind.VEICULO to "a"]!!.fields["apelido"])
        assertNotNull(local.docs[Kind.VEICULO to "b"])
        assertTrue(local.dirty().isEmpty())
    }

    @Test
    fun `veiculo removido logicamente vai com removidoEm`() = runTest {
        val local = FakeLocalStore()
        val remote = FakeRemoteStore()
        remote.docs[Kind.VEICULO to "a"] = veiculo("a", at = 100)
        local.put(veiculo("a", removidoEm = 400, at = 400), dirty = true)
        SyncEngine(local, remote) { now }.syncNow()
        assertEquals(400L, remote.docs[Kind.VEICULO to "a"]!!.fields["removidoEm"])
    }

    @Test
    fun `hodometros de veiculos diferentes sao independentes`() = runTest {
        val local = MultiOdoLocal()
        val remote = MultiOdoRemote()
        local.odos += OdometerDoc(100.0, 13.0, 500, "moto")
        local.odos += OdometerDoc(5000.0, 45.0, 400, "carro")
        remote.odos += OdometerDoc(90.0, 13.0, 300, "moto") // local mais novo vence
        remote.odos += OdometerDoc(7000.0, 50.0, 900, "carro") // remoto mais novo vence
        SyncEngine(local, remote) { now }.syncNow()
        assertEquals(OdometerDoc(100.0, 13.0, 500, "moto"), remote.odos.first { it.veiculoId == "moto" })
        assertEquals(OdometerDoc(7000.0, 50.0, 900, "carro"), local.odos.first { it.veiculoId == "carro" })
    }

    @Test
    fun `viagens de outro veiculo nao descontam o hodometro`() = runTest {
        val local = MultiOdoLocal()
        val remote = MultiOdoRemote()
        local.odos += OdometerDoc(100.0, 13.0, 5_000, "moto")
        remote.docs[Kind.TRIP to "t"] = viagem("t", "carro", 20_000.0, 1_000)
        SyncEngine(local, remote) { now }.syncNow()
        assertEquals(OdometerDoc(100.0, 13.0, 5_000, "moto"), remote.odos.single())
    }

    @Test
    fun `viagem trazida do mesmo veiculo desconta do hodometro`() = runTest {
        val local = MultiOdoLocal()
        val remote = MultiOdoRemote()
        local.odos += OdometerDoc(1000.0, 13.0, 5_000, "moto")
        remote.docs[Kind.TRIP to "t"] = viagem("t", "moto", 20_000.0, 1_000)
        SyncEngine(local, remote) { now }.syncNow()
        assertEquals(980.0, remote.odos.single().offsetKm)
    }

    @Test
    fun `perfil leva o veiculo ativo`() = runTest {
        val local = FakeLocalStore()
        val remote = FakeRemoteStore()
        local.prof = UserProfile("Ana", null, null, null, true, veiculoAtivoId = "v1")
        local.profDirty = true
        SyncEngine(local, remote) { now }.syncNow()
        assertEquals("v1", remote.prof!!.veiculoAtivoId)
    }
}

private class MultiOdoLocal : LocalStore {
    val odos = mutableListOf<OdometerDoc>()
    override suspend fun all() = emptyList<SyncDoc>()
    override suspend fun dirty() = emptyList<SyncDoc>()
    override suspend fun apply(docs: List<SyncDoc>) {}
    override suspend fun markClean(docs: List<SyncDoc>) {}
    override suspend fun odometers() = odos.toList()
    override suspend fun applyOdometer(o: OdometerDoc, dirty: Boolean) {
        odos.removeAll { it.veiculoId == o.veiculoId }
        odos += o
    }
    override suspend fun profile(): UserProfile? = null
    override suspend fun applyProfile(p: UserProfile) {}
    override suspend fun hasDirtyOdometerOrProfile() = false
    override suspend fun clearAll() {}
}

private class MultiOdoRemote : RemoteStore {
    val odos = mutableListOf<OdometerDoc>()
    val docs = LinkedHashMap<Pair<Kind, String>, SyncDoc>()
    override suspend fun fetchAll() = RemoteSnapshot(docs.values.toList(), odos.toList(), null)
    override suspend fun push(docs: List<SyncDoc>) { docs.forEach { this.docs[it.kind to it.gid] = it } }
    override suspend fun pushOdometer(o: OdometerDoc) {
        odos.removeAll { it.veiculoId == o.veiculoId }
        odos += o
    }
    override suspend fun pushProfile(p: UserProfile) {}
    override suspend fun markDeletionPending() {}
    override suspend fun deletionPending() = false
    override suspend fun deleteAllUserData() {}
}
