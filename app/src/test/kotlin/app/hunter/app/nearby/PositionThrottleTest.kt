package app.hunter.app.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PositionThrottleTest {
    private val lat = -21.0
    private val lon = -41.0

    @Test
    fun primeiraPosicaoEnvia() {
        assertTrue(PositionThrottle().shouldSend(1_000, lat, lon, false))
    }

    @Test
    fun cincoSegundosDepoisNaoEnvia() {
        val t = PositionThrottle()
        assertTrue(t.shouldSend(0, lat, lon, false))
        assertFalse(t.shouldSend(5_000, lat, lon, false))
    }

    @Test
    fun trintaEUmSegundosDepoisEnvia() {
        val t = PositionThrottle()
        assertTrue(t.shouldSend(0, lat, lon, false))
        assertFalse(t.shouldSend(29_000, lat, lon, false))
        assertTrue(t.shouldSend(31_000, lat, lon, false))
    }

    @Test
    fun emEncontroOnzeSegundosDepoisEnvia() {
        val t = PositionThrottle()
        assertTrue(t.shouldSend(0, lat, lon, true))
        assertFalse(t.shouldSend(9_000, lat, lon, true))
        assertTrue(t.shouldSend(11_000, lat, lon, true))
    }

    @Test
    fun procurandoOnzeSegundosDepoisSemAndarNaoEnvia() {
        val t = PositionThrottle()
        assertTrue(t.shouldSend(0, lat, lon, false))
        assertFalse(t.shouldSend(11_000, lat, lon, false))
    }

    @Test
    fun andou150mEm12sEnvia() {
        val t = PositionThrottle()
        assertTrue(t.shouldSend(0, lat, lon, false))
        // ~150 m ao norte (1 grau de latitude ~ 111,2 km).
        assertTrue(t.shouldSend(12_000, lat + 0.00135, lon, false))
    }

    @Test
    fun andou150mEm5sNaoEnvia() {
        val t = PositionThrottle()
        assertTrue(t.shouldSend(0, lat, lon, false))
        assertFalse(t.shouldSend(5_000, lat + 0.00135, lon, false))
    }

    @Test
    fun andouPoucoEm12sNaoEnvia() {
        val t = PositionThrottle()
        assertTrue(t.shouldSend(0, lat, lon, false))
        assertFalse(t.shouldSend(12_000, lat + 0.0005, lon, false)) // ~55 m
    }

    @Test
    fun posicaoNaoEnviadaNaoMoveAReferencia() {
        val t = PositionThrottle()
        assertTrue(t.shouldSend(0, lat, lon, false))
        // Passos de ~60 m: nenhum chega a 100 m da referência enviada antes de passar o intervalo.
        assertFalse(t.shouldSend(11_000, lat + 0.00054, lon, false))
        assertTrue(t.shouldSend(12_000, lat + 0.00108, lon, false)) // ~120 m da última enviada
    }
}

class BackoffTest {
    @Test
    fun dobraAteOMaximo() {
        val b = Backoff()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (0..6).map { b.nextDelayMs(it) })
    }

    @Test
    fun tentativasGrandesNaoEstouram() {
        val b = Backoff()
        assertEquals(30_000L, b.nextDelayMs(200))
        assertEquals(1_000L, b.nextDelayMs(-3))
    }
}
