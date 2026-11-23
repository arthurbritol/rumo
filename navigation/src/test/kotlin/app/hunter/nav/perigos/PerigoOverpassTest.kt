package app.hunter.nav.perigos

import app.hunter.nav.Http
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PerigoTagsTest {
    private fun t(vararg kv: Pair<String, String>) = PerigoTags.tipoDeTags(mapOf(*kv))

    @Test fun `radar por highway speed_camera`() = assertEquals(Perigo.RADAR, t("highway" to "speed_camera"))
    @Test fun `radar por enforcement maxspeed`() = assertEquals(Perigo.RADAR, t("enforcement" to "maxspeed", "highway" to "traffic_signals"))
    @Test fun `radar por enforcement speed_camera`() = assertEquals(Perigo.RADAR, t("enforcement" to "speed_camera"))
    @Test fun `enforcement com lista pega o radar`() = assertEquals(Perigo.RADAR, t("enforcement" to "traffic_signals;maxspeed"))
    @Test fun `semaforo com camera`() {
        assertEquals(Perigo.SEMAFORO_COM_CAMERA, t("enforcement" to "traffic_signals"))
        assertEquals(Perigo.SEMAFORO_COM_CAMERA, t("enforcement" to "red_light"))
    }
    @Test fun `lombadas`() {
        for (v in listOf("bump", "hump", "table", "cushion", "speed_table")) assertEquals(Perigo.LOMBADA, t("traffic_calming" to v), v)
    }
    @Test fun `faixa sonora`() = assertEquals(Perigo.FAIXA_SONORA, t("traffic_calming" to "rumble_strip"))
    @Test fun `travessia ferroviaria`() = assertEquals(Perigo.TRAVESSIA_FERROVIARIA, t("railway" to "level_crossing"))
    @Test fun `radar vence lombada no mesmo no`() = assertEquals(Perigo.RADAR, t("highway" to "speed_camera", "traffic_calming" to "table"))
    @Test fun `o que nao e perigo vira nulo`() {
        assertNull(t("highway" to "traffic_signals"))
        assertNull(t("traffic_calming" to "choker"))
        assertNull(t("railway" to "crossing"))
        assertNull(t("enforcement" to "no"))
        assertNull(t())
    }

    @Test fun `categorias`() {
        assertEquals(CategoriaPerigo.RADARES, Perigo.RADAR.categoria)
        assertEquals(CategoriaPerigo.RADARES, Perigo.SEMAFORO_COM_CAMERA.categoria)
        assertEquals(CategoriaPerigo.LOMBADAS, Perigo.LOMBADA.categoria)
        assertEquals(CategoriaPerigo.LOMBADAS, Perigo.FAIXA_SONORA.categoria)
        assertEquals(CategoriaPerigo.TRAVESSIAS, Perigo.TRAVESSIA_FERROVIARIA.categoria)
    }

    @Test fun `maxspeed`() {
        assertEquals(60, PerigoTags.limiteKmh("60"))
        assertEquals(80, PerigoTags.limiteKmh(" 80 km/h "))
        assertEquals(97, PerigoTags.limiteKmh("60 mph"))
        assertNull(PerigoTags.limiteKmh("none"))
        assertNull(PerigoTags.limiteKmh("signals"))
        assertNull(PerigoTags.limiteKmh("BR:urban"))
        assertNull(PerigoTags.limiteKmh("60;80"))
        assertNull(PerigoTags.limiteKmh(null))
        assertNull(PerigoTags.limiteKmh("0"))
    }

    @Test fun `direction em graus ou cardeal`() {
        assertEquals(90.0, PerigoTags.direcaoGraus(mapOf("direction" to "90")))
        assertEquals(45.0, PerigoTags.direcaoGraus(mapOf("direction" to "NE")))
        assertEquals(350.0, PerigoTags.direcaoGraus(mapOf("camera:direction" to "-10")))
        assertNull(PerigoTags.direcaoGraus(mapOf("direction" to "forward")))
        assertNull(PerigoTags.direcaoGraus(mapOf("direction" to "both")))
        assertNull(PerigoTags.direcaoGraus(emptyMap()))
    }
}

class PerigoOverpassTest {
    /** Resposta no formato real do Overpass (`out center tags`), recortada de um trecho de rodovia no Recife (PE). */
    private val resposta = """
    {
      "version": 0.6,
      "generator": "Overpass API 0.7.62.1 084b4234",
      "osm3s": { "timestamp_osm_base": "2026-11-22T10:15:02Z", "copyright": "The data included in this document is from www.openstreetmap.org. The data is made available under ODbL." },
      "elements": [
        { "type": "node", "id": 1851234567, "lat": -8.0431762, "lon": -34.9265011,
          "tags": { "direction": "forward", "highway": "speed_camera", "maxspeed": "60", "source": "survey" } },
        { "type": "node", "id": 2210987654, "lat": -8.0501100, "lon": -34.9300200,
          "tags": { "enforcement": "traffic_signals", "highway": "traffic_signals", "traffic_signals": "signal" } },
        { "type": "node", "id": 2210987655, "lat": -8.0611000, "lon": -34.9400000,
          "tags": { "enforcement": "maxspeed", "maxspeed": "40 km/h", "direction": "270" } },
        { "type": "node", "id": 3300000001, "lat": -8.0702000, "lon": -34.9500000,
          "tags": { "traffic_calming": "bump", "highway": "traffic_calming" } },
        { "type": "node", "id": 3300000002, "lat": -8.0702100, "lon": -34.9500100,
          "tags": { "traffic_calming": "bump" } },
        { "type": "way", "id": 410000123, "center": { "lat": -8.0800000, "lon": -34.9600000 },
          "tags": { "traffic_calming": "table", "highway": "residential", "name": "Rua da Hora" } },
        { "type": "node", "id": 5500000009, "lat": -8.0900000, "lon": -34.9700000,
          "tags": { "traffic_calming": "rumble_strip" } },
        { "type": "node", "id": 6600000010, "lat": -8.0950000, "lon": -34.9750000,
          "tags": { "railway": "level_crossing", "crossing:barrier": "no" } },
        { "type": "node", "id": 7700000011, "lat": -8.0960000, "lon": -34.9760000,
          "tags": { "highway": "traffic_signals" } },
        { "type": "node", "id": 7700000012, "tags": { "highway": "speed_camera" } }
      ]
    }
    """.trimIndent()

    @Test fun `parse da resposta real`() {
        val ps = OverpassPerigoSource.parse(resposta)
        // Sem coordenada e sem tipo conhecido ficam de fora; a 2ª lombada colada na 1ª (~2 m) é junta.
        assertEquals(listOf("n1851234567", "n2210987654", "n2210987655", "n3300000001", "w410000123", "n5500000009", "n6600000010"), ps.map { it.id })
        val radar = ps[0]
        assertEquals(Perigo.RADAR, radar.tipo)
        assertEquals(60, radar.limiteKmh)
        assertNull(radar.direcaoGraus) // "forward" depende da via
        assertEquals(-8.0431762, radar.lat)
        assertEquals(Perigo.SEMAFORO_COM_CAMERA, ps[1].tipo)
        assertNull(ps[1].limiteKmh)
        assertEquals(40, ps[2].limiteKmh)
        assertEquals(270.0, ps[2].direcaoGraus)
        assertEquals(Perigo.LOMBADA, ps[3].tipo)
        // via: usa o centro
        assertEquals(Perigo.LOMBADA, ps[4].tipo)
        assertEquals(-34.96, ps[4].lon)
        assertEquals(Perigo.FAIXA_SONORA, ps[5].tipo)
        assertEquals(Perigo.TRAVESSIA_FERROVIARIA, ps[6].tipo)
    }

    @Test fun `resposta vazia ou sem elements`() {
        assertEquals(emptyList(), OverpassPerigoSource.parse("""{"version":0.6,"elements":[]}"""))
        assertEquals(emptyList(), OverpassPerigoSource.parse("""{"remark":"runtime error: Query timed out"}"""))
    }

    @Test fun `resposta quebrada lanca`() {
        assertFailsWith<Exception> { OverpassPerigoSource.parse("<html>Too Many Requests</html>") }
    }

    @Test fun `a consulta cobre o quadrado com 4 casas e todas as tags`() {
        val q = OverpassPerigoSource.consulta(TileId.de(-8.05, -34.93))
        assertTrue("[out:json]" in q && "out center tags" in q)
        assertTrue("highway\"=\"speed_camera" in q)
        assertTrue("traffic_calming" in q && "rumble_strip" in q)
        assertTrue("railway\"=\"level_crossing" in q)
        assertTrue("(-8.1000,-35.0000,-8.0000,-34.9000)" in q, q)
    }

    private class FakeHttp(val respostas: Map<String, () -> String>) : Http {
        val chamadas = mutableListOf<String>()
        override suspend fun get(url: String): String {
            chamadas += url
            val f = respostas.entries.firstOrNull { url.startsWith(it.key) }?.value ?: error("sem resposta")
            return f()
        }
        override suspend fun postJson(url: String, body: String) = error("não usado")
    }

    @Test fun `espelho alternativo quando o primeiro falha`() = runTest {
        val http = FakeHttp(mapOf("https://a/" to { throw IOException("timeout") }, "https://b/" to { resposta }))
        val ps = OverpassPerigoSource(http, listOf("https://a/x", "https://b/x")).buscar(TileId(1450, 820))
        assertEquals(7, ps.size)
        assertEquals(2, http.chamadas.size)
        assertTrue(http.chamadas[1].startsWith("https://b/x?data="))
    }

    @Test fun `todos os espelhos falham lanca`() = runTest {
        val http = FakeHttp(mapOf("https://a/" to { throw IOException("x") }))
        assertFailsWith<IOException> { OverpassPerigoSource(http, listOf("https://a/x", "https://a/y")).buscar(TileId(0, 0)) }
        assertEquals(2, http.chamadas.size)
    }
}
