package app.hunter.pod.session

import app.hunter.pod.CompassDirection
import app.hunter.pod.PodPacket
import app.hunter.pod.PodPackets
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PodSessionTest {

    private class FakeTransport(val clock: () -> Long) : PodTransport {
        val writes = mutableListOf<Pair<Long, String>>()
        var failFrom: Int = Int.MAX_VALUE
        override suspend fun write(packet: ByteArray) {
            if (writes.size >= failFrom) throw IOException("link caiu")
            writes += clock() to PodPacket.toHex(packet)
        }
        fun hexes() = writes.map { it.second }
        fun times(hex: String) = writes.filter { it.second == hex }.map { it.first }
    }

    private fun hex(b: ByteArray) = PodPacket.toHex(b)
    private val resume = hex(PodPackets.handshake(false))
    private val showPin = hex(PodPackets.handshake(true))
    private val ping = hex(PodPackets.ping())
    private val time1355 = hex(PodPackets.setTime(13, 55, is12h = true))

    private fun TestScope.session(t: FakeTransport, failures: MutableList<Throwable> = mutableListOf()) = PodSession(
        transport = t, scope = backgroundScope,
        now = { testScheduler.currentTime }, localTime = { LocalTime.of(13, 55) }, is12h = true,
        onFailure = { failures += it },
    )

    @Test
    fun `dispositivo conhecido segue a sequencia 21 00, hora, 2 pings com os atrasos do protocolo`() = runTest {
        val t = FakeTransport { testScheduler.currentTime }
        val s = session(t)
        s.resume()
        assertEquals(SessionState.CONNECTED, s.state.value)
        assertEquals(listOf(resume, time1355, ping, ping), t.hexes())
        assertEquals(listOf(0L, 200L, 350L, 350L), t.writes.map { it.first })
        assertEquals(650L, testScheduler.currentTime)
        s.close()
    }

    @Test
    fun `sem tela o keepalive manda PING depois de 2 s sem escrita`() = runTest {
        val t = FakeTransport { testScheduler.currentTime }
        val s = session(t)
        s.resume()
        advanceTimeBy(2_450) // até t = 3100
        assertEquals(listOf(3000L), t.times(ping).filter { it > 350 })
        s.close()
    }

    @Test
    fun `tela dinamica e enviada ja e reenviada a cada 2 s, sem pings extras`() = runTest {
        val t = FakeTransport { testScheduler.currentTime }
        val s = session(t)
        s.resume() // t = 650
        val compass = hex(PodPackets.compass(CompassDirection.NE, night = false))
        s.setScreen(PodPackets.compass(CompassDirection.NE, night = false))
        runCurrent()
        advanceTimeBy(4_500) // até t = 5150
        assertEquals(listOf(650L, 3000L, 5000L), t.times(compass))
        assertTrue(t.times(ping).none { it > 650 }, "a tela já mantém o link vivo")
        s.close()
    }

    @Test
    fun `trocar a tela envia a nova imediatamente`() = runTest {
        val t = FakeTransport { testScheduler.currentTime }
        val s = session(t)
        s.resume()
        s.setScreen(PodPackets.compass(CompassDirection.N, false))
        runCurrent()
        s.setScreen(PodPackets.compass(CompassDirection.E, false))
        runCurrent()
        assertEquals(
            listOf(hex(PodPackets.compass(CompassDirection.N, false)), hex(PodPackets.compass(CompassDirection.E, false))),
            t.hexes().takeLast(2),
        )
        s.close()
    }

    @Test
    fun `dispositivo novo manda SHOW_PIN, espera o PIN e conecta`() = runTest {
        val t = FakeTransport { testScheduler.currentTime }
        val s = session(t)
        s.beginPairing()
        assertEquals(SessionState.AWAITING_PIN, s.state.value)
        assertEquals(listOf(showPin), t.hexes())
        assertEquals(false, s.submitPin("   "), "PIN vazio não envia")
        assertTrue(s.submitPin("1234"))
        assertEquals(SessionState.CONNECTED, s.state.value)
        assertEquals(hex(PodPackets.pin("1234")), t.hexes().last())
        s.close()
    }

    @Test
    fun `PIN so e aceito em AWAITING_PIN`() = runTest {
        val t = FakeTransport { testScheduler.currentTime }
        val s = session(t)
        assertEquals(false, s.submitPin("1234"))
        s.close()
    }

    @Test
    fun `falha de escrita fecha a sessao, avisa e para o keepalive`() = runTest {
        val t = FakeTransport { testScheduler.currentTime }
        val failures = mutableListOf<Throwable>()
        val s = session(t, failures)
        s.resume()
        t.failFrom = t.writes.size // a próxima escrita falha
        advanceTimeBy(3_500)
        assertEquals(SessionState.CLOSED, s.state.value)
        assertEquals(1, failures.size)
        val before = t.writes.size
        advanceTimeBy(10_000)
        assertEquals(before, t.writes.size, "nenhuma escrita depois de fechar")
    }

    @Test
    fun `falha no meio do handshake deixa a sessao fechada`() = runTest {
        val t = FakeTransport { testScheduler.currentTime }
        t.failFrom = 1 // 21 00 ok, a hora falha
        val s = session(t)
        s.resume()
        assertEquals(SessionState.CLOSED, s.state.value)
        assertEquals(listOf(resume), t.hexes())
    }
}
