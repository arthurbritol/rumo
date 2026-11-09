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
        assertEquals(false, m["voice"])
        assertEquals(setOf("displayName", "displayNameLower", "brand", "model", "voice", "updatedAtMs"), m.keys)
    }

    @Test
    fun perfilNuncaPassaDosLimitesDasRegras() {
        val longo = "á".repeat(70)
        val m = profileToMap(UserProfile("  $longo  ", "b".repeat(61), " ${"m".repeat(80)} ", 1800), 1L)
        assertEquals(60, (m["displayName"] as String).length)
        assertEquals(m["displayName"], m["displayNameLower"])
        assertEquals(60, (m["brand"] as String).length)
        assertEquals("m".repeat(60), m["model"])
        assertFalse(m.containsKey("year")) // fora de 1950..2100: omitido em vez de recusado
        // nome vazio vira "Motociclista"; marca/modelo em branco viram nulos
        val vazio = profileToMap(UserProfile("   ", " ", "", 2020), 1L)
        assertEquals("Motociclista", vazio["displayName"])
        assertNull(vazio["brand"])
        assertNull(vazio["model"])
        assertEquals(2020, vazio["year"])
    }

    @Test
    fun perfilNaoCortaEmojiAoMeio() {
        val nome = "a".repeat(59) + "🏍" // 59 letras + moto (2 unidades UTF-16)
        val m = profileToMap(UserProfile(nome, null, null, null), 1L)
        val n = m["displayName"] as String
        assertEquals("a".repeat(59), n)
        assertFalse(Character.isHighSurrogate(n.last()))
    }

    @Test
    fun maiusculaNaoAsciiOmiteDisplayNameLower() {
        // O lower() das regras não converte "Á" (conferido no emulador) e "İ" vira 2 caracteres no Java:
        // nesses casos o campo (opcional) é omitido para o perfil não ser recusado.
        for (nome in listOf("İzmir", "Ágata", "ÉRICA")) {
            val m = profileToMap(UserProfile(nome, null, null, null), 1L)
            assertFalse(m.containsKey("displayNameLower"), nome)
            assertEquals(nome, m["displayName"])
        }
        // minúsculas acentuadas e maiúsculas ASCII continuam com o campo
        assertEquals("joão ávila", profileToMap(UserProfile("JOãO áVILA", null, null, null), 1L)["displayNameLower"])
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
    fun vozAceitaBooleanoETextoLegado() {
        val base = mapOf("displayName" to "A")
        assertEquals(false, mapToProfile(base + ("voice" to false))!!.voice)
        assertEquals(false, mapToProfile(base + ("voice" to "false"))!!.voice)
        assertEquals(true, mapToProfile(base + ("voice" to true))!!.voice)
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
