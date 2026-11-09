package app.hunter.model

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FuelAndMaintenanceTest {

    private fun f(id: Long, odo: Double, l: Double, cost: Double, full: Boolean = true) =
        Fuelup(id, id * 1000, odo, l, cost, full)

    private fun near(e: Double, a: Double?, tol: Double = 1e-6) = assertTrue(a != null && kotlin.math.abs(e - a) <= tol, "esperado $e obtido $a")

    @Test
    fun `um trecho cheio a cheio`() {
        val s = FuelMath.summarize(listOf(f(1, 1000.0, 12.0, 70.0), f(2, 1200.0, 10.0, 60.0)))
        assertEquals(1, s.segments.size)
        near(20.0, s.avgKmPerL)
        near(0.30, s.avgCostPerKm)
        near(20.0, s.lastKmPerL)
    }

    @Test
    fun `parcial no meio soma litros`() {
        val s = FuelMath.summarize(listOf(f(1, 1000.0, 12.0, 70.0), f(2, 1100.0, 5.0, 30.0, full = false), f(3, 1300.0, 5.0, 30.0)))
        near(30.0, s.avgKmPerL) // 300 km / 10 L
        near(0.2, s.avgCostPerKm) // 60 / 300
    }

    @Test
    fun `parcial antes do primeiro cheio nao entra e sem trecho nao ha media`() {
        val only = FuelMath.summarize(listOf(f(1, 900.0, 3.0, 20.0, full = false), f(2, 1000.0, 12.0, 70.0)))
        assertTrue(only.segments.isEmpty())
        assertNull(only.avgKmPerL)
        assertEquals(15.0, only.totalLiters, 1e-9)
    }

    @Test
    fun `media e ponderada e a ordem de entrada nao importa`() {
        val list = listOf(f(3, 1500.0, 10.0, 60.0), f(1, 1000.0, 12.0, 70.0), f(2, 1200.0, 10.0, 60.0))
        val s = FuelMath.summarize(list)
        assertEquals(2, s.segments.size)
        near(25.0, s.avgKmPerL) // 500 km / 20 L
        near(30.0, s.lastKmPerL) // 300 / 10
    }

    @Test
    fun `autonomia pela media`() {
        near(210.0, FuelMath.rangeKm(30.0, 7.0))
        assertNull(FuelMath.rangeKm(null, 7.0))
    }

    private val day = TimeUnit.DAYS.toMillis(1)
    private fun item(km: Int? = 5000, days: Int? = 180, lastKm: Double? = 1000.0, lastAt: Long? = 0L) =
        MaintenanceItem(1, "Óleo", km, days, lastKm, lastAt)

    @Test
    fun `item sem registro anterior e desconhecido`() {
        assertEquals(DueState.UNKNOWN, MaintenanceMath.status(item(lastKm = null, lastAt = null), 3000.0, 0).state)
    }

    @Test
    fun `em dia, perto e atrasado por km`() {
        val i = item(days = null, lastAt = null)
        assertEquals(DueState.OK, MaintenanceMath.status(i, 2000.0, 0).state)
        val soon = MaintenanceMath.status(i, 5600.0, 0)
        assertEquals(DueState.SOON, soon.state)
        near(400.0, soon.remainingKm)
        val over = MaintenanceMath.status(i, 6100.0, 0)
        assertEquals(DueState.OVERDUE, over.state)
        near(-100.0, over.remainingKm)
    }

    @Test
    fun `por dias e o que vencer primeiro manda`() {
        val i = item()
        assertEquals(DueState.OK, MaintenanceMath.status(i, 1100.0, 10 * day).state)
        assertEquals(DueState.SOON, MaintenanceMath.status(i, 1100.0, 170 * day).state)
        assertEquals(DueState.OVERDUE, MaintenanceMath.status(i, 1100.0, 200 * day).state) // km ok, prazo estourou
        assertEquals(DueState.OVERDUE, MaintenanceMath.status(i, 6500.0, day).state) // prazo ok, km estourou
        assertEquals(170L, MaintenanceMath.status(i, 1100.0, 10 * day).remainingDays)
    }

    @Test
    fun `somente intervalo por dias`() {
        val i = item(km = null, lastKm = null)
        assertEquals(DueState.OK, MaintenanceMath.status(i, 9999.0, 30 * day).state)
    }
}
