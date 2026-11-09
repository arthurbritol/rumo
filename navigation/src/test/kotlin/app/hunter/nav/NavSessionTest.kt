package app.hunter.nav

import app.hunter.model.TrackPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NavSessionTest {

    @BeforeTest @AfterTest
    fun reset() = NavStatus.stop()

    private val dest = Place("Destino", null, 0.009, 0.009)

    private fun route(): Route {
        val d = 1000.0 / 111_194.9
        val shape = listOf(LatLon(0.0, 0.0), LatLon(0.0, d), LatLon(d, d))
        val ms = listOf(
            Maneuver(1, "Siga para leste", "Siga para leste por 1 quilômetro.", null, 1000.0, 100.0, 0, 1, null),
            Maneuver(15, "Vire à esquerda", "Vire à esquerda na Rua B.", "Rua B", 1000.0, 120.0, 1, 2, null),
            Maneuver(4, "Você chegou", "Seu destino é logo ali.", null, 0.0, 0.0, 2, 2, null),
        )
        return Route(shape, ms, 2000.0, 220.0, false, false, RouteProfile.FASTEST)
    }

    private fun pt(eastM: Double, northM: Double, t: Long = 0) =
        TrackPoint(t, northM / 111_194.9, eastM / 111_194.9, accuracyM = 5f)

    // ---------- NavAnnouncer ----------

    private fun prog(idx: Int, dist: Double, arrived: Boolean = false): NavProgress {
        val m = route().maneuvers
        return NavProgress(0.0, 0.0, 0.0, idx, m[idx], dist, m.getOrNull(idx + 1), 0.0, false, arrived)
    }

    @Test
    fun `anuncia a 800, 250 e 60 m, uma vez cada`() {
        val a = NavAnnouncer()
        assertNull(a.next(prog(1, 1200.0)))
        assertEquals("Em 800 metros. Vire à esquerda na Rua B.", a.next(prog(1, 790.0)))
        assertNull(a.next(prog(1, 700.0)))
        assertEquals("Em 250 metros. Vire à esquerda na Rua B.", a.next(prog(1, 245.0)))
        assertEquals("Vire à esquerda na Rua B.", a.next(prog(1, 55.0)))
        assertNull(a.next(prog(1, 30.0)))
    }

    @Test
    fun `manobra curta ja perto nao repete anuncios longos`() {
        val a = NavAnnouncer()
        assertEquals("Em 250 metros. Vire à esquerda na Rua B.", a.next(prog(1, 240.0)))
        assertNull(a.next(prog(1, 230.0)))
        assertEquals("Vire à esquerda na Rua B.", a.next(prog(1, 50.0)))
    }

    @Test
    fun `nova manobra reinicia os anuncios e chegada fala so uma vez`() {
        val a = NavAnnouncer()
        a.next(prog(1, 50.0))
        assertNotNull(a.next(prog(2, 700.0)))
        assertEquals("Você chegou ao seu destino.", a.next(prog(2, 5.0, arrived = true)))
        assertNull(a.next(prog(2, 3.0, arrived = true)))
    }

    @Test
    fun `distancias faladas`() {
        assertEquals("300 metros", NavAnnouncer.spokenDistance(310.0))
        assertEquals("50 metros", NavAnnouncer.spokenDistance(10.0))
        assertEquals("1,5 quilômetros", NavAnnouncer.spokenDistance(1_540.0))
        assertEquals("2 quilômetros", NavAnnouncer.spokenDistance(2_010.0))
    }

    // ---------- NavSession ----------

    @Test
    fun `sem navegacao ativa nao faz nada`() = runTest {
        val s = NavSession(backgroundScope, { _, _, _, _ -> null })
        assertNull(s.onPoint(pt(0.0, 0.0)))
    }

    @Test
    fun `ao iniciar fala a primeira instrucao e publica o progresso`() = runTest {
        NavStatus.start(route(), dest)
        val s = NavSession(backgroundScope, { _, _, _, _ -> null })
        val speech = s.onPoint(pt(10.0, 0.0))!!
        assertEquals(listOf("Siga para leste por 1 quilômetro."), speech)
        val p = NavStatus.state.value.progress!!
        assertEquals(15, p.upcoming.type)
        assertTrue(p.remainingM in 1980.0..1995.0)
    }

    @Test
    fun `fora da rota pede nova rota, fala recalculando e troca o traçado`() = runTest {
        NavStatus.start(route(), dest)
        var asked: Triple<LatLon, Place, RouteProfile>? = null
        val newRoute = route()
        val s = NavSession(backgroundScope, { f, t, p, _ -> asked = Triple(f, t, p); newRoute }, now = { 100_000L })
        s.onPoint(pt(100.0, 0.0))
        s.onPoint(pt(200.0, 300.0))
        s.onPoint(pt(210.0, 310.0))
        val third = s.onPoint(pt(220.0, 320.0))!!
        assertTrue("Recalculando." in third)
        assertTrue(NavStatus.state.value.rerouting)
        runCurrent()
        assertNotNull(asked)
        assertEquals(dest, asked!!.second)
        assertEquals(RouteProfile.FASTEST, asked!!.third)
        assertFalse(NavStatus.state.value.rerouting)
        assertTrue(NavStatus.state.value.route === newRoute)
        // depois do recálculo não repete a instrução de partida
        val after = s.onPoint(pt(10.0, 0.0))!!
        assertTrue(after.none { it.startsWith("Siga") })
    }

    @Test
    fun `recalculo mantem so as paradas que ainda estao a frente`() = runTest {
        val d = 1000.0 / 111_194.9
        val passed = LatLon(0.0, 0.2 * d)        // a 200 m do início: já ficou para trás
        val ahead = LatLon(0.5 * d, d)           // na 2ª perna, ainda não alcançada
        NavStatus.start(route(), dest, via = listOf(passed, ahead))
        var vias: List<LatLon>? = null
        val s = NavSession(backgroundScope, { _, _, _, v -> vias = v; null }, now = { 100_000L })
        s.onPoint(pt(400.0, 0.0))
        repeat(3) { s.onPoint(pt(500.0 + it, 400.0)) } // sai da rota (mais perto da 1ª perna que da 2ª)
        runCurrent()
        assertEquals(listOf(ahead), vias)
    }

    @Test
    fun `se o recalculo falha volta ao estado normal e tenta de novo depois do intervalo`() = runTest {
        NavStatus.start(route(), dest)
        var t = 100_000L
        var calls = 0
        val s = NavSession(backgroundScope, { _, _, _, _ -> calls++; null }, now = { t })
        s.onPoint(pt(100.0, 0.0))
        repeat(3) { s.onPoint(pt(200.0 + it, 300.0)) }
        runCurrent()
        assertEquals(1, calls)
        assertFalse(NavStatus.state.value.rerouting)
        t += 5_000
        repeat(3) { s.onPoint(pt(200.0, 300.0)) }
        runCurrent()
        assertEquals(1, calls, "dentro do intervalo de 15 s não tenta")
        t += 20_000
        s.onPoint(pt(200.0, 300.0))
        runCurrent()
        assertEquals(2, calls)
    }
}
