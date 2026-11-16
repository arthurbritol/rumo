package app.hunter.app.nearby

import app.hunter.nav.LatLon
import app.hunter.nav.Route
import app.hunter.nav.RouteProfile
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EtaCalculatorTest {
    private val de = LatLon(-20.0, -40.0)
    private val para = LatLon(-20.0 - 20.0 / 111.195, -40.0) // 20 km ao sul

    private fun rota(km: Double, min: Double) = Route(emptyList(), emptyList(), km * 1000, min * 60, false, false, RouteProfile.FASTEST)

    @Test
    fun `usa a rota quando o roteador devolve`() = runBlocking {
        val eta = EtaCalculator { _, _ -> rota(27.34, 41.6) }.eta(de, para)
        assertEquals(Eta(27.3, 42.0), eta)
    }

    @Test
    fun `sem rota usa a reta a 40 km por hora`() = runBlocking {
        val eta = EtaCalculator { _, _ -> null }.eta(de, para)
        assertEquals(20.0, eta.distKm, 0.1)
        assertEquals(30.0, eta.etaMin) // 20 km em reta = 30 min
    }

    @Test
    fun `roteador que lanca cai na reta`() = runBlocking {
        val eta = EtaCalculator { _, _ -> error("sem rede") }.eta(de, para)
        assertEquals(30.0, eta.etaMin)
    }

    @Test
    fun `mesmo lugar da zero`() = runBlocking {
        assertEquals(Eta(0.0, 0.0), EtaCalculator { _, _ -> null }.eta(de, de))
    }

    @Test
    fun `roteador bloqueante e interrompido no tempo limite e cai na reta`() = runBlocking {
        var interrompido = false
        val calc = EtaCalculator(timeoutMs = 200) { _, _ ->
            try {
                Thread.sleep(10_000)
            } catch (e: InterruptedException) {
                interrompido = true
                throw e
            }
            null
        }
        val t0 = System.nanoTime()
        val eta = calc.eta(de, para)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(30.0, eta.etaMin)
        assertEquals(null, calc.etaRota(de, para))
        assertTrue(ms < 2_000, "demorou $ms ms")
        assertTrue(interrompido)
    }

    @Test
    fun `roteador que ignora a interrupcao tambem nao prende o chamador alem do tempo limite`() = runBlocking {
        val calc = EtaCalculator(timeoutMs = 200) { _, _ ->
            val fim = System.nanoTime() + 3_000_000_000L
            while (System.nanoTime() < fim) {
                try { Thread.sleep(50) } catch (e: InterruptedException) { /* ignora, como uma chamada de rede bloqueante */ }
            }
            null
        }
        val t0 = System.nanoTime()
        assertEquals(null, calc.etaRota(de, para))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 1_500, "prendeu o chamador por $ms ms")
    }
}
