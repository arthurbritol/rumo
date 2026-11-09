package app.hunter.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViaAndStationsTest {

    private fun near(e: Double, a: Double, tol: Double, msg: String = "") =
        assertTrue(kotlin.math.abs(e - a) <= tol, "$msg esperado≈$e obtido=$a")

    private fun resource(name: String) = ViaAndStationsTest::class.java.getResource("/$name")!!.readText()

    // ---------- postos (resposta real do Overpass salva) ----------

    @Test
    fun `interpreta postos reais, ordena por distancia e da nome aos sem nome`() {
        val origin = LatLon(-8.05, -34.9)
        val stations = FuelStationFinder.parse(resource("overpass_fuel_recife.json"), origin)
        assertTrue(stations.size >= 3)
        assertTrue(stations.any { it.name == "Posto Texaco" })
        assertTrue(stations.any { it.name == "Posto de combustível" }, "sem nome vira genérico")
        val dist = stations.map { app.hunter.model.Geo.distanceM(origin.lat, origin.lon, it.lat, it.lon) }
        assertEquals(dist.sorted(), dist, "do mais perto ao mais longe")
        assertTrue(stations.first().subtitle!!.contains("km do ponto"))
    }

    @Test
    fun `resposta vazia ou sem elements nao quebra`() {
        assertTrue(FuelStationFinder.parse("""{"elements":[]}""", LatLon(0.0, 0.0)).isEmpty())
        assertTrue(FuelStationFinder.parse("""{"version":0.6}""", LatLon(0.0, 0.0)).isEmpty())
    }

    // ---------- ponto a X km da rota ----------

    private fun straight(): Route {
        val d = 1000.0 / 111_194.9
        val shape = listOf(LatLon(0.0, 0.0), LatLon(0.0, d), LatLon(0.0, 2 * d))
        val ms = listOf(
            Maneuver(1, "Siga", null, null, 2000.0, 200.0, 0, 2, null),
            Maneuver(4, "Chegou", null, null, 0.0, 0.0, 2, 2, null),
        )
        return Route(shape, ms, 2000.0, 200.0, false, false, RouteProfile.FASTEST)
    }

    @Test
    fun `pointAt interpola ao longo da rota e limita nas pontas`() {
        val r = straight()
        val d = 1000.0 / 111_194.9
        near(0.5 * d, r.pointAt(500.0).lon, 1e-6)
        near(1.5 * d, r.pointAt(1500.0).lon, 1e-6)
        assertEquals(r.shape.first(), r.pointAt(-10.0))
        assertEquals(r.shape.last(), r.pointAt(99_999.0))
    }

    // ---------- rota com parada ----------

    @Test
    fun `requisicao com parada tem 3 locais e sem alternativas`() {
        val a = LatLon(-8.0, -34.0)
        val v = LatLon(-8.5, -34.5)
        val b = LatLon(-9.0, -35.0)
        val body = ValhallaClient.buildRequest(a, b, RouteProfile.FASTEST, alternates = 0, via = listOf(v))
        assertEquals(3, Regex("\"type\":\"break\"").findAll(body).count())
        assertTrue(body.indexOf("-8.5") in body.indexOf("-8.0")..body.indexOf("-9.0"), "ordem origem → parada → destino")
        kotlinx.serialization.json.Json.parseToJsonElement(body)
    }

    /** Resposta de 2 trechos (origem → posto → destino), no formato do Valhalla. */
    private fun twoLegJson(): String {
        val d = 1000.0 / 111_194.9
        val leg1 = Polyline.encode(listOf(LatLon(0.0, 0.0), LatLon(0.0, d), LatLon(0.0, 2 * d)))
        val leg2 = Polyline.encode(listOf(LatLon(0.0, 2 * d), LatLon(d, 2 * d), LatLon(2 * d, 2 * d)))
        fun man(type: Int, begin: Int, end: Int, len: Double, time: Double) =
            """{"type":$type,"instruction":"m$type","length":$len,"time":$time,"begin_shape_index":$begin,"end_shape_index":$end}"""
        return """{"trip":{"summary":{"length":4.0,"time":400.0,"has_toll":false,"has_highway":false},"legs":[
            {"shape":"${leg1.replace("\\", "\\\\")}","maneuvers":[${man(1, 0, 2, 2.0, 200.0)},${man(4, 2, 2, 0.0, 0.0)}]},
            {"shape":"${leg2.replace("\\", "\\\\")}","maneuvers":[${man(1, 0, 1, 1.0, 100.0)},${man(15, 1, 2, 1.0, 100.0)},${man(4, 2, 2, 0.0, 0.0)}]}
        ]}}"""
    }

    @Test
    fun `junta os trechos numa geometria so e desloca os indices das manobras`() {
        val r = ValhallaClient.parse(twoLegJson(), RouteProfile.FASTEST).single()
        assertEquals(5, r.shape.size, "3 + 3 − 1 ponto repetido")
        assertEquals(5, r.maneuvers.size)
        // manobras do 2º trecho começam em 2 (ponto do posto) e vão até 4
        assertEquals(listOf(0, 2, 2, 3, 4), r.maneuvers.map { it.beginShapeIndex })
        near(4000.0, r.lengthM, 40.0, "comprimento da geometria")
        near(4000.0, r.distanceM, 1.0)
    }

    @Test
    fun `a navegacao atravessa a parada e so chega no destino final`() {
        val r = ValhallaClient.parse(twoLegJson(), RouteProfile.FASTEST).single()
        val e = NavEngine(r)
        val d = 1000.0 / 111_194.9
        // perto do posto (fim do 1º trecho): ainda não é chegada
        val atStop = e.update(LatLon(0.0, 1.995 * d))
        assertTrue(atStop.upcoming.isDestination)
        assertFalse(atStop.arrived, "o posto é só uma parada")
        // depois do posto a próxima manobra é a curva do 2º trecho
        val after = e.update(LatLon(0.2 * d, 2 * d))
        assertEquals(15, after.upcoming.type)
        // no fim, chegou
        assertTrue(e.update(LatLon(1.9999 * d, 2 * d)).arrived)
    }
}
