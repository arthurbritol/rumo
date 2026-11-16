package app.hunter.app.nearby

import app.hunter.nav.LatLon
import app.hunter.nav.Route
import app.hunter.nav.RouteProfile
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EtaCalculatorTest {
    private val de = LatLon(-20.0, -40.0)
    private val para = LatLon(-20.0 - 20.0 / 111.195, -40.0) // 20 km ao sul

    private fun rota(km: Double, min: Double) = Route(emptyList(), emptyList(), km * 1000, min * 60, false, false, RouteProfile.FASTEST)

    @Test
    fun `usa a rota quando o roteador devolve`() = runTest {
        val eta = EtaCalculator { _, _ -> rota(27.34, 41.6) }.eta(de, para)
        assertEquals(Eta(27.3, 42.0), eta)
    }

    @Test
    fun `sem rota usa a reta a 40 km por hora`() = runTest {
        val eta = EtaCalculator { _, _ -> null }.eta(de, para)
        assertEquals(20.0, eta.distKm, 0.1)
        assertEquals(30.0, eta.etaMin) // 20 km em reta = 30 min
    }

    @Test
    fun `roteador que lanca cai na reta`() = runTest {
        val eta = EtaCalculator { _, _ -> error("sem rede") }.eta(de, para)
        assertEquals(30.0, eta.etaMin)
    }

    @Test
    fun `mesmo lugar da zero`() = runTest {
        assertEquals(Eta(0.0, 0.0), EtaCalculator { _, _ -> null }.eta(de, de))
    }
}
