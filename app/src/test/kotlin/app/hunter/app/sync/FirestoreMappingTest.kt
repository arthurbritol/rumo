package app.hunter.app.sync

import app.hunter.sync.Kind
import app.hunter.sync.OdometerDoc
import app.hunter.sync.SyncDoc
import app.hunter.sync.UserProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class FirestoreMappingTest {
    @Test
    fun docViraMapaEVoltaSemPerda() {
        val doc = SyncDoc(Kind.TRIP, "g", 7L, true, mapOf("a" to 1.5, "b" to null, "c" to "x"))
        val map = docToMap(doc)
        assertEquals(7L, map["updatedAtMs"])
        assertEquals(true, map["deleted"])
        assertEquals(doc, mapToDoc(Kind.TRIP, "g", map))
    }

    @Test
    fun numerosDoFirestoreViramValoresSemanticos() {
        val doc = mapToDoc(Kind.PLACE, "g", mapOf("updatedAtMs" to 5.0, "deleted" to false, "n" to 3L))
        assertEquals(5L, doc.updatedAtMs)
        assertEquals(3L, doc.fields["n"])
    }

    @Test
    fun maisDe18CamposDeDadosELancaErroClaro() {
        val fields = (1..19).associate { "f$it" to it }
        val e = assertFailsWith<IllegalArgumentException> { docToMap(SyncDoc(Kind.FUELUP, "g", 1L, false, fields)) }
        assertEquals(true, e.message!!.contains("18"))
        docToMap(SyncDoc(Kind.FUELUP, "g", 1L, false, (1..18).associate { "f$it" to it })) // limite exato passa
    }

    @Test
    fun campoReservadoNosDadosELancaErro() {
        assertFailsWith<IllegalArgumentException> {
            docToMap(SyncDoc(Kind.FUELUP, "g", 1L, false, mapOf("deleted" to true)))
        }
    }

    @Test
    fun perfilDerivaNomeMinusculoEOmiteAnoNulo() {
        val m = profileToMap(UserProfile("ARTHUR Lúcio", "Honda", null, null, false), 99L)
        assertEquals("arthur lúcio", m["displayNameLower"])
        assertFalse(m.containsKey("year"))
        assertEquals(99L, m["updatedAtMs"])
        assertEquals(setOf("displayName", "displayNameLower", "brand", "model", "voice", "updatedAtMs"), m.keys)
    }

    @Test
    fun perfilVoltaDoMapaComAnoLongEVozPadrao() {
        val p = mapToProfile(mapOf("displayName" to "A", "year" to 2020L, "updatedAtMs" to 1L))!!
        assertEquals(2020, p.year)
        assertEquals(true, p.voice)
        assertNull(p.brand)
        assertEquals(p, mapToProfile(profileToMap(p, 1L)))
        assertNull(mapToProfile(mapOf("updatedAtMs" to 1L)))
    }

    @Test
    fun hodometroIdaEVolta() {
        val o = OdometerDoc(12.5, 40.0, 9L)
        assertEquals(o, mapToOdometer(odometerToMap(o)))
        assertEquals(o, mapToOdometer(mapOf("offsetKm" to 12.5, "tankLiters" to 40L, "updatedAtMs" to 9L)))
    }

    @Test
    fun lotesRespeitamOTamanho() {
        assertEquals(listOf(400, 400, 100), batches((1..900).toList()).map { it.size })
        assertEquals(emptyList(), batches(emptyList<Int>()))
    }
}
