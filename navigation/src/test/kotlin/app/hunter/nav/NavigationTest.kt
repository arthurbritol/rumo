package app.hunter.nav

import app.hunter.pod.PodIcon
import app.hunter.pod.PodPacket
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun resource(name: String) = NavigationTest::class.java.getResource("/$name")!!.readText()

class NavigationTest {

    private fun near(e: Double, a: Double, tol: Double, msg: String = "") =
        assertTrue(kotlin.math.abs(e - a) <= tol, "$msg esperado≈$e obtido=$a")

    // ---------- Polyline ----------

    @Test
    fun `decodifica o exemplo oficial do Google (precisao 5)`() {
        val pts = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, pts.size)
        near(38.5, pts[0].lat, 1e-9); near(-120.2, pts[0].lon, 1e-9)
        near(40.7, pts[1].lat, 1e-9); near(-120.95, pts[1].lon, 1e-9)
        near(43.252, pts[2].lat, 1e-9); near(-126.453, pts[2].lon, 1e-9)
    }

    @Test
    fun `encode e decode sao inversos em precisao 6`() {
        val pts = listOf(LatLon(-8.047600, -34.877000), LatLon(-8.110140, -34.899901), LatLon(0.0, 0.0), LatLon(-33.868820, 151.209296))
        val back = Polyline.decode(Polyline.encode(pts, 6), 6)
        assertEquals(pts.size, back.size)
        pts.zip(back).forEach { (a, b) -> near(a.lat, b.lat, 1e-6); near(a.lon, b.lon, 1e-6) }
    }

    // ---------- Valhalla (resposta real salva) ----------

    @Test
    fun `interpreta resposta real do Valhalla com alternativas`() {
        val routes = ValhallaClient.parse(resource("valhalla_recife.json"), RouteProfile.CURVES)
        assertEquals(3, routes.size, "principal + 2 alternativas")
        val r = routes[0]
        near(10_357.0, r.distanceM, 1.0)
        near(1640.78, r.durationS, 0.1)
        assertEquals(12, r.maneuvers.size)
        assertTrue(r.shape.size > 300)
        // a geometria decodificada tem o mesmo comprimento do resumo (±3%)
        near(r.distanceM, r.lengthM, r.distanceM * 0.03, "comprimento da geometria")
        assertEquals(1, r.maneuvers.first().type)
        assertTrue(r.maneuvers.last().isDestination)
        assertEquals(r.shape.lastIndex, r.maneuvers.last().beginShapeIndex)
        assertTrue(r.maneuvers.first().instruction.startsWith("Dirija"))
        assertEquals(RouteProfile.CURVES, r.profile)
        // primeiro ponto = origem pedida
        near(-8.0476, r.shape.first().lat, 0.001)
    }

    @Test
    fun `requisicao do Valhalla muda as opcoes por perfil`() {
        val a = LatLon(-8.0, -34.0)
        val b = LatLon(-8.1, -34.1)
        fun req(p: RouteProfile) = ValhallaClient.buildRequest(a, b, p, 2)
        assertTrue(req(RouteProfile.FASTEST).contains("\"use_highways\":1.0"))
        assertTrue(req(RouteProfile.CURVES).contains("\"use_highways\":0.0"))
        assertTrue(req(RouteProfile.NO_TOLLS).contains("\"use_tolls\":0.0"))
        assertTrue(req(RouteProfile.NO_DIRT).contains("\"exclude_unpaved\":true"))
        val r = req(RouteProfile.FASTEST)
        assertTrue(r.contains("\"costing\":\"motorcycle\""))
        assertTrue(r.contains("\"language\":\"pt-BR\""))
        assertTrue(r.contains("\"alternates\":2"))
        // JSON válido
        kotlinx.serialization.json.Json.parseToJsonElement(r)
    }

    // ---------- Photon (resposta real salva) ----------

    @Test
    fun `interpreta busca real do Photon e remove duplicados`() {
        val places = PhotonGeocoder.parse(resource("photon_boa_viagem.json"))
        assertEquals(2, places.size, "3 resultados, 2 distintos")
        assertEquals("Avenida Boa Viagem", places[0].name)
        assertTrue(places[0].subtitle!!.contains("Recife"))
        near(-8.0904, places[0].lat, 0.001)
        near(-34.8818, places[0].lon, 0.001)
    }

    @Test
    fun `busca curta nao vai a rede`() {
        val geocoder = PhotonGeocoder(http = object : Http {
            override suspend fun get(url: String): String = error("não deveria chamar")
            override suspend fun postJson(url: String, body: String): String = error("não deveria chamar")
        })
        kotlinx.coroutines.test.runTest { assertTrue(geocoder.search("ab").isEmpty()) }
    }

    @Test
    fun `resultado sem nome usa rua e numero`() {
        val json = """{"features":[{"geometry":{"coordinates":[-34.9,-8.05]},"properties":{"street":"Rua Aurora","housenumber":"100","city":"Recife"}}]}"""
        val p = PhotonGeocoder.parse(json).single()
        assertEquals("Rua Aurora, 100", p.name)
        assertEquals("Recife", p.subtitle)
    }

    // ---------- Motor de navegação (rota sintética) ----------

    /** 1 km para leste, vira à esquerda, 1 km para norte. */
    private fun syntheticRoute(): Route {
        val dLon = 1000.0 / 111_194.9
        val shape = listOf(LatLon(0.0, 0.0), LatLon(0.0, dLon), LatLon(1000.0 / 111_194.9, dLon))
        val ms = listOf(
            Maneuver(1, "Siga para leste", null, "Rua A", 1000.0, 100.0, 0, 1, null),
            Maneuver(15, "Vire à esquerda", null, "Rua B", 1000.0, 120.0, 1, 2, null),
            Maneuver(4, "Você chegou", null, null, 0.0, 0.0, 2, 2, null),
        )
        return Route(shape, ms, 2000.0, 220.0, false, false, RouteProfile.FASTEST)
    }

    private fun at(eastM: Double, northM: Double) = LatLon(northM / 111_194.9, eastM / 111_194.9)

    @Test
    fun `no meio do primeiro trecho a proxima manobra e a curva`() {
        val e = NavEngine(syntheticRoute())
        val p = e.update(at(500.0, 0.0))
        near(500.0, p.alongM, 2.0)
        near(1500.0, p.remainingM, 3.0)
        assertEquals(1, p.upcomingIndex)
        assertEquals(15, p.upcoming.type)
        near(500.0, p.distanceToUpcomingM, 2.0)
        assertEquals(4, p.then!!.type)
        near(170.0, p.remainingS, 1.5, "metade do 1º trecho (50 s) + 2º trecho (120 s)")
    }

    @Test
    fun `tempo restante soma o resto do trecho atual e os seguintes`() {
        val e = NavEngine(syntheticRoute())
        near(220.0, e.update(at(0.0, 0.0)).remainingS, 1.0)
        near(170.0, e.update(at(500.0, 0.0)).remainingS, 1.5) // 50 s + 120 s
        near(60.0, e.update(at(1000.0, 500.0)).remainingS, 1.5)
    }

    @Test
    fun `depois da curva a proxima manobra e o destino e chegada e detectada`() {
        val e = NavEngine(syntheticRoute())
        val p = e.update(at(1000.0, 400.0))
        assertEquals(4, p.upcoming.type)
        near(600.0, p.distanceToUpcomingM, 3.0)
        assertFalse(p.arrived)
        assertNull(p.then)
        val end = e.update(at(1000.0, 990.0))
        assertTrue(end.arrived)
    }

    @Test
    fun `fora da rota so depois de 3 leituras seguidas longe`() {
        val e = NavEngine(syntheticRoute())
        e.update(at(300.0, 0.0))
        assertFalse(e.update(at(400.0, 120.0)).offRoute)
        assertFalse(e.update(at(420.0, 130.0)).offRoute)
        assertTrue(e.update(at(440.0, 140.0)).offRoute)
        // volta para a rota: zera
        assertFalse(e.update(at(500.0, 2.0)).offRoute)
    }

    @Test
    fun `leitura ruidosa perto da rota nao dispara fora de rota`() {
        val e = NavEngine(syntheticRoute())
        repeat(10) { i -> assertFalse(e.update(at(100.0 * i, if (i % 2 == 0) 15.0 else -15.0)).offRoute) }
    }

    @Test
    fun `engine percorre a rota real inteira sem retroceder`() {
        val r = ValhallaClient.parse(resource("valhalla_recife.json"), RouteProfile.FASTEST).first()
        val e = NavEngine(r)
        var lastAlong = -1.0
        var lastIdx = 0
        for (i in r.shape.indices step 3) {
            val p = e.update(r.shape[i])
            assertTrue(p.alongM >= lastAlong - 1.0, "retrocedeu em $i")
            assertTrue(p.upcomingIndex >= lastIdx, "manobra voltou em $i")
            assertTrue(p.deviationM < 2.0)
            assertFalse(p.offRoute)
            lastAlong = p.alongM
            lastIdx = p.upcomingIndex
        }
        val end = e.update(r.shape.last())
        assertTrue(end.arrived)
        near(0.0, end.remainingM, 5.0)
    }

    // ---------- Manobras → ícones do Pod ----------

    private fun m(type: Int, exit: Int? = null) = Maneuver(type, "", null, null, 0.0, 0.0, 0, 0, exit)

    @Test
    fun `traduz manobras do Valhalla para icones do Pod`() {
        val cases = mapOf(
            10 to PodIcon.TURN_RIGHT, 15 to PodIcon.TURN_LEFT, 9 to PodIcon.SLIGHT_RIGHT, 16 to PodIcon.SLIGHT_LEFT,
            11 to PodIcon.SHARP_RIGHT, 14 to PodIcon.SHARP_LEFT, 13 to PodIcon.U_TURN_COUNTER_CLOCKWISE,
            12 to PodIcon.U_TURN_CLOCKWISE, 8 to PodIcon.STRAIGHT, 22 to PodIcon.STRAIGHT,
            4 to PodIcon.DESTINATION, 5 to PodIcon.DESTINATION_RIGHT, 6 to PodIcon.DESTINATION_LEFT,
            23 to PodIcon.KEEP_RIGHT_SOFT, 24 to PodIcon.KEEP_LEFT_SOFT,
            20 to PodIcon.OFF_RAMP_SLIGHT_RIGHT, 21 to PodIcon.OFF_RAMP_SLIGHT_LEFT, 1 to PodIcon.DEPART,
        )
        for ((t, icon) in cases) assertEquals(icon, PodManeuvers.icon(m(t)), "tipo $t")
        assertEquals(PodIcon.roundaboutRightHand(3), PodManeuvers.icon(m(26, 3)))
        assertEquals(PodIcon.roundaboutRightHand(1), PodManeuvers.icon(m(26, null)))
    }

    @Test
    fun `seta pequena usa icones de faixa para keep e exit e some no destino`() {
        assertEquals(PodIcon.KEEP_LANE_RIGHT, PodManeuvers.nextIcon(m(23)))
        assertEquals(PodIcon.KEEP_LANE_LEFT, PodManeuvers.nextIcon(m(24)))
        assertEquals(PodIcon.KEEP_LANE_RIGHT, PodManeuvers.nextIcon(m(20)))
        assertEquals(PodIcon.KEEP_LANE_LEFT, PodManeuvers.nextIcon(m(21)))
        assertEquals(PodIcon.NONE, PodManeuvers.nextIcon(m(4)))
        assertEquals(PodIcon.NONE, PodManeuvers.nextIcon(null))
        assertEquals(PodIcon.TURN_LEFT, PodManeuvers.nextIcon(m(15)))
    }

    @Test
    fun `pacote de navegacao para o Pod tem 20 bytes, CRC valido e usa o estado`() {
        val e = NavEngine(syntheticRoute())
        val p = e.update(at(500.0, 0.0))
        val now = LocalDateTime.of(2026, 11, 30, 17, 0)
        val pkt = PodNavPackets.navigation(p, night = false, mode = PodBottomMode.ARRIVAL_TIME, now = now)
        assertTrue(PodPacket.isValid(pkt))
        assertEquals(PodIcon.TURN_LEFT, pkt[2].toInt() and 0xFF)
        // 500 m → unidade metros
        assertEquals(0x01, pkt[3].toInt() and 0xFF)
        assertEquals(1, pkt[5].toInt())
        // chegada = 17:00 + ceil(170 s / 60) = 3 min → 17:03
        assertEquals(17, pkt[11].toInt() and 0xFF)
        assertEquals(3, pkt[12].toInt() and 0xFF)
        // distância total no rodapé: 1500 m → 15 (km×10), unidade 2
        val d = PodNavPackets.navigation(p, false, PodBottomMode.TOTAL_DISTANCE)
        assertEquals(15, d[12].toInt() and 0xFF)
        assertEquals(2, d[13].toInt())
        assertNotNull(d)
    }
}
