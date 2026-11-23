package app.hunter.nav.perigos

import app.hunter.nav.Http
import app.hunter.nav.HttpFailure
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
        assertNull(PerigoTags.direcaoGraus(mapOf("direction" to "backward")))
        assertNull(PerigoTags.direcaoGraus(mapOf("direction" to "both")))
        assertNull(PerigoTags.direcaoGraus(emptyMap()))
    }
}

class PerigoOverpassTest {
    /** Respostas REAIS do Overpass (2026-11-23, `out center tags`): Recife inteira (116 elementos) e um recorte de 19 de São Paulo. */
    private fun fixture(nome: String) = checkNotNull(javaClass.getResourceAsStream("/overpass/$nome")) { nome }.bufferedReader().readText()
    private val recife get() = fixture("recife-real.json")
    private val sp get() = fixture("sp-reduzido.json")

    @Test fun `parse de resposta real do Recife`() {
        val ps = OverpassPerigoSource.parse(recife)
        // 116 elementos; 8 repetidos do mesmo tipo a menos de 25 m viram um só.
        assertEquals(108, ps.size)
        assertEquals(mapOf(Perigo.LOMBADA to 60, Perigo.RADAR to 26, Perigo.TRAVESSIA_FERROVIARIA to 22), ps.groupingBy { it.tipo }.eachCount())
        assertEquals(ps.size, ps.map { it.id }.toSet().size)
        // radar com `direction` em graus
        val r60 = ps.single { it.id == "n13744146029" }
        assertEquals(Perigo.RADAR, r60.tipo); assertEquals(60, r60.limiteKmh); assertEquals(60.0, r60.direcaoGraus)
        assertEquals(225.0, ps.single { it.id == "n13744146030" }.direcaoGraus)
        // radar sem limite nem direção
        val simples = ps.single { it.id == "n1712346773" }
        assertNull(simples.limiteKmh); assertNull(simples.direcaoGraus)
        assertEquals(-8.0945047, simples.lat); assertEquals(-34.9655523, simples.lon)
        // passagem de pedestre elevada (highway=crossing + traffic_calming=table) entra como lombada
        assertEquals(Perigo.LOMBADA, ps.single { it.id == "n6174694029" }.tipo)
        assertEquals(Perigo.TRAVESSIA_FERROVIARIA, ps.single { it.id == "n341719908" }.tipo)
    }

    @Test fun `parse de resposta real de Sao Paulo (maxspeed, forward e backward, via com center, faixa sonora)`() {
        val ps = OverpassPerigoSource.parse(sp)
        assertEquals(18, ps.size)
        assertEquals(mapOf(Perigo.RADAR to 10, Perigo.LOMBADA to 5, Perigo.FAIXA_SONORA to 2, Perigo.TRAVESSIA_FERROVIARIA to 1), ps.groupingBy { it.tipo }.eachCount())
        assertEquals(50, ps.single { it.id == "n25716953" }.limiteKmh)
        val fwd = ps.single { it.id == "n5476639320" } // direction=forward, maxspeed=40
        assertEquals(40, fwd.limiteKmh); assertNull(fwd.direcaoGraus)
        // `direction=forward/backward` depende da geometria da via: não vira ângulo (pendência documentada)
        assertTrue(ps.filter { it.tipo == Perigo.RADAR && it.direcaoGraus == null && it.limiteKmh != null }.size >= 4)
        // via (way) usa o `center`
        val via = ps.single { it.id == "w228183415" }
        assertEquals(Perigo.LOMBADA, via.tipo)
        assertEquals(-23.5290749, via.lat); assertEquals(-46.6316086, via.lon)
    }

    @Test fun `junta marcadores colados do mesmo tipo e mantem tipos diferentes`() {
        val json = """{"elements":[
          {"type":"node","id":1,"lat":-8.0,"lon":-34.0,"tags":{"traffic_calming":"bump"}},
          {"type":"node","id":2,"lat":-8.00001,"lon":-34.00001,"tags":{"traffic_calming":"bump"}},
          {"type":"node","id":3,"lat":-8.00001,"lon":-34.00001,"tags":{"highway":"speed_camera"}},
          {"type":"node","id":4,"tags":{"highway":"speed_camera"}},
          {"type":"node","id":5,"lat":-8.1,"lon":-34.1,"tags":{"highway":"traffic_signals"}}]}"""
        assertEquals(listOf("n1", "n3"), OverpassPerigoSource.parse(json).map { it.id })
    }

    @Test fun `resposta vazia`() {
        assertEquals(emptyList(), OverpassPerigoSource.parse("""{"version":0.6,"elements":[]}"""))
        assertEquals(emptyList(), OverpassPerigoSource.parse("""{"version":0.6}"""))
    }

    @Test fun `remark (ex timeout da consulta) e falha e nunca resultado`() {
        val e = assertFailsWith<OverpassRemark> {
            OverpassPerigoSource.parse("""{"version":0.6,"elements":[{"type":"node","id":1,"lat":1.0,"lon":2.0,"tags":{"highway":"speed_camera"}}],"remark":"runtime error: Query timed out in query at line 3 after 26 seconds."}""")
        }
        assertTrue("timed out" in e.message!!)
    }

    @Test fun `resposta quebrada lanca`() {
        assertFailsWith<Exception> { OverpassPerigoSource.parse("<html>Too Many Requests</html>") }
        assertFailsWith<Exception> { OverpassPerigoSource.parse("# 502 Gateway Timeout\n\nThis Overpass API server is currently at capacity.") }
    }

    @Test fun `a consulta cobre o quadrado com 4 casas e todas as tags`() {
        val q = OverpassPerigoSource.consulta(TileId.de(-8.05, -34.93))
        assertTrue("[out:json]" in q && "out center tags" in q)
        assertTrue("[timeout:25]" in q)
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

    private val a = "https://a/api"
    private val b = "https://b/api"
    private val c = "https://c/api"
    private fun FakeHttp.hosts() = chamadas.map { it.substringBefore("?") }

    @Test fun `ordem padrao dos espelhos`() {
        assertEquals(
            listOf(
                "https://overpass-api.de/api/interpreter",
                "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
                "https://overpass.kumi.systems/api/interpreter",
            ),
            OverpassPerigoSource.ESPELHOS,
        )
    }

    @Test fun `espelho alternativo quando o primeiro falha`() = runTest {
        val http = FakeHttp(mapOf(a to { throw HttpFailure(502, "HTTP 502") }, b to { recife }))
        val ps = OverpassPerigoSource(http, listOf(a, b)).buscar(TileId(1450, 819))
        assertEquals(108, ps.size)
        assertEquals(listOf(a, b), http.hosts())
        assertTrue(http.chamadas[1].startsWith("$b?data="))
    }

    @Test fun `remark num espelho passa para o proximo`() = runTest {
        val http = FakeHttp(mapOf(a to { """{"elements":[],"remark":"runtime error: Query timed out"}""" }, b to { sp }))
        assertEquals(18, OverpassPerigoSource(http, listOf(a, b)).buscar(TileId(1, 1)).size)
    }

    @Test fun `espelho que falhou fica de lado por 10 min e depois volta`() = runTest {
        var t = 0L
        var aOk = false
        val http = FakeHttp(mapOf(a to { if (aOk) sp else throw HttpFailure(504, "HTTP 504") }, b to { sp }))
        val src = OverpassPerigoSource(http, listOf(a, b), agora = { t })
        src.buscar(TileId(1, 1)) // a falha, b responde
        assertEquals(listOf(a, b), http.hosts())
        http.chamadas.clear()
        t += 60_000
        src.buscar(TileId(1, 1)) // a está de lado: vai direto a b
        assertEquals(listOf(b), http.hosts())
        http.chamadas.clear()
        t += 10 * 60_000
        aOk = true
        src.buscar(TileId(1, 1)) // passou a pausa: a volta
        assertEquals(listOf(a), http.hosts())
        http.chamadas.clear()
        src.buscar(TileId(1, 1)) // e, saudável, continua sendo o primeiro
        assertEquals(listOf(a), http.hosts())
    }

    @Test fun `todos de lado tenta todos mesmo assim`() = runTest {
        val http = FakeHttp(mapOf(a to { throw IOException("x") }, b to { throw IOException("y") }))
        val src = OverpassPerigoSource(http, listOf(a, b), agora = { 0 })
        assertFailsWith<IOException> { src.buscar(TileId(1, 1)) }
        assertFailsWith<IOException> { src.buscar(TileId(1, 1)) }
        assertEquals(4, http.chamadas.size)
    }

    @Test fun `todos os espelhos falham lanca e repassa o Retry-After`() = runTest {
        val http = FakeHttp(mapOf(a to { throw IOException("x") }, b to { throw HttpFailure(429, "HTTP 429", 90_000) }, c to { throw IOException("z") }))
        val e = assertFailsWith<HttpFailure> { OverpassPerigoSource(http, listOf(a, b, c)).buscar(TileId(0, 0)) }
        assertEquals(3, http.chamadas.size)
        assertEquals(90_000L, e.retryAfterMs)
    }

    @Test fun `user agent identifica o app`() {
        assertEquals("Rumo/1.2.3 (app de navegação para motos; https://github.com/arthurbritol/rumo)", PerigoStatus.userAgent("1.2.3"))
    }
}
