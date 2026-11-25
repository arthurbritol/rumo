package app.hunter.app.sync

import app.hunter.maintenance.data.FuelupEntity
import app.hunter.maintenance.data.MaintenanceItemEntity
import app.hunter.maintenance.data.OdometerEntity
import app.hunter.maintenance.data.VeiculoEntity
import app.hunter.sync.Kind
import app.hunter.sync.OdometerDoc
import app.hunter.sync.SyncDoc
import app.hunter.sync.UserProfile
import app.hunter.sync.VEICULO_LEGADO_ID
import app.hunter.trips.data.TripEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class VeiculoMappingTest {
    @Test
    fun `veiculo vai e volta pelo documento sem perder campos`() {
        val e = VeiculoEntity(
            id = "v1", tipo = "CARRO", apelido = "Gol", marca = "VW", modelo = "Gol 1.0", ano = 2015,
            tanqueLitros = 55.0, consumoPadraoKmL = 12.5, criadoEm = 10, removidoEm = null, updatedAtMs = 99, dirty = true,
        )
        val d = e.toDoc()
        assertEquals(Kind.VEICULO, d.kind)
        assertEquals("v1", d.gid)
        assertEquals(99L, d.updatedAtMs)
        assertFalse(d.deleted)
        assertEquals(e.copy(dirty = false), veiculoFromDoc(d))
    }

    @Test
    fun `veiculo removido logicamente leva removidoEm no documento`() {
        val e = VeiculoEntity(id = "v1", tipo = "MOTO", apelido = "x", tanqueLitros = 13.0, removidoEm = 777, updatedAtMs = 5)
        assertEquals(777L, e.toDoc().fields["removidoEm"])
        assertEquals(777L, veiculoFromDoc(e.toDoc()).removidoEm)
    }

    @Test
    fun `abastecimento manutencao e viagem levem veiculoId e o recuperam`() {
        val f = FuelupEntity(dateMs = 1, odometerKm = 2.0, liters = 3.0, totalCost = 4.0, fullTank = true, gid = "g1", veiculoId = "v9")
        assertEquals("v9", f.toDoc().fields["veiculoId"])
        assertEquals("v9", fuelupFromDoc(f.toDoc(), null).veiculoId)

        val m = MaintenanceItemEntity(name = "Oleo", intervalKm = 5000, intervalDays = null, gid = "g2", veiculoId = "v9")
        assertEquals("v9", m.toDoc().fields["veiculoId"])
        assertEquals("v9", itemFromDoc(m.toDoc(), null).veiculoId)

        val t = TripEntity(startedAtMs = 1, endedAtMs = 2, gid = "g3", veiculoId = "v9")
        assertEquals("v9", t.toDocOrNull()!!.fields["veiculoId"])
        assertEquals("v9", tripFromDoc(t.toDocOrNull()!!, null).veiculoId)
    }

    @Test
    fun `documento sem veiculoId cai no veiculo migrado e viagem fica sem veiculo`() {
        val semCampo = mapOf("dateMs" to 1L, "odometerKm" to 2.0, "liters" to 3.0, "totalCost" to 4.0, "fullTank" to true)
        assertEquals(VEICULO_LEGADO_ID, fuelupFromDoc(SyncDoc(Kind.FUELUP, "g", 1, false, semCampo), null).veiculoId)
        assertEquals(VEICULO_LEGADO_ID, itemFromDoc(SyncDoc(Kind.MAINTENANCE, "g", 1, false, mapOf("name" to "x")), null).veiculoId)
        assertNull(tripFromDoc(SyncDoc(Kind.TRIP, "g", 1, false, mapOf("startedAtMs" to 1L)), null).veiculoId)
    }

    @Test
    fun `hodometro e uma linha por veiculo`() {
        val o = OdometerEntity(veiculoId = "v2", offsetKm = 10.0, tankLiters = 45.0, updatedAtMs = 7, dirty = true)
        assertEquals(OdometerDoc(10.0, 45.0, 7, "v2"), o.toDoc())
        assertEquals(o.copy(dirty = false), odometerFromDoc(o.toDoc()))
        assertEquals(VEICULO_LEGADO_ID, odometerFromDoc(OdometerDoc(1.0, 2.0, 3)).veiculoId)
    }

    @Test
    fun `mapa do hodometro so leva veiculoId quando existe e le pelo caminho se o campo faltar`() {
        assertEquals(setOf("offsetKm", "tankLiters", "updatedAtMs"), odometerToMap(OdometerDoc(1.0, 2.0, 3)).keys)
        assertEquals("v1", odometerToMap(OdometerDoc(1.0, 2.0, 3, "v1"))["veiculoId"])
        val dados = mapOf("offsetKm" to 1.0, "tankLiters" to 2.0, "updatedAtMs" to 3L)
        assertEquals("do-caminho", mapToOdometer(dados, "do-caminho")!!.veiculoId)
        assertNull(mapToOdometer(dados)!!.veiculoId)
        assertEquals("campo", mapToOdometer(dados + ("veiculoId" to "campo"), "do-caminho")!!.veiculoId)
    }

    @Test
    fun `perfil leva veiculoAtivoId so quando existe e o le de volta`() {
        val comAtivo = UserProfile("Ana", "Honda", "Hunter", 2022, true, veiculoAtivoId = "v1")
        assertEquals("v1", profileToMap(comAtivo, 5)["veiculoAtivoId"])
        assertEquals("v1", mapToProfile(profileToMap(comAtivo, 5))!!.veiculoAtivoId)
        assertFalse("veiculoAtivoId" in profileToMap(comAtivo.copy(veiculoAtivoId = null), 5))
        assertFalse("veiculoAtivoId" in profileToMap(comAtivo.copy(veiculoAtivoId = "x".repeat(65)), 5))
        assertNull(mapToProfile(mapOf("displayName" to "Ana"))!!.veiculoAtivoId)
    }

    @Test
    fun `documento do veiculo cabe no limite de campos das regras`() {
        val e = VeiculoEntity(id = "v", tipo = "MOTO", apelido = "x", tanqueLitros = 13.0)
        val map = app.hunter.app.sync.docToMap(e.toDoc())
        assertEquals(setOf("tipo", "apelido", "marca", "modelo", "ano", "tanqueLitros", "consumoPadraoKmL", "criadoEm", "removidoEm", "updatedAtMs", "deleted"), map.keys)
    }

    @Test
    fun `documento do veiculo e saneado para caber nas regras`() {
        val e = VeiculoEntity(
            id = "v", tipo = "TRATOR", apelido = "a".repeat(80), marca = "  " + "m".repeat(70), modelo = "ok", ano = 1800,
            tanqueLitros = 500.0, consumoPadraoKmL = 0.0,
        )
        val f = e.toDoc().fields
        assertEquals("MOTO", f["tipo"])
        assertEquals(60, (f["apelido"] as String).length)
        assertEquals(60, (f["marca"] as String).length)
        assertNull(f["ano"])
        assertEquals(200.0, f["tanqueLitros"])
        assertNull(f["consumoPadraoKmL"])
    }
}
