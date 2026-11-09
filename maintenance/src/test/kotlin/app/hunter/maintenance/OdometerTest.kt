package app.hunter.maintenance

import kotlin.test.Test
import kotlin.test.assertEquals

class OdometerTest {
    @Test
    fun `hodometro soma deslocamento e pedais`() {
        assertEquals(1234.5, Odometer.currentKm(1000.0, 234_500.0), 1e-9)
    }

    @Test
    fun `informar o painel gera deslocamento que fecha a conta`() {
        val offset = Odometer.offsetFor(panelKm = 1500.0, tripsDistanceM = 80_000.0)
        assertEquals(1420.0, offset, 1e-9)
        assertEquals(1500.0, Odometer.currentKm(offset, 80_000.0), 1e-9)
        // depois de mais 20 km de pedal
        assertEquals(1520.0, Odometer.currentKm(offset, 100_000.0), 1e-9)
    }
}
