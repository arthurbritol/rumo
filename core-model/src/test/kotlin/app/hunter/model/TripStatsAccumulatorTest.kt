package app.hunter.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TripStatsAccumulatorTest {

    /** 1° de latitude ≈ 111.195 m; deslocar para o norte por [m] metros. */
    private fun north(m: Double) = m / 111_194.9

    private fun pt(
        tSec: Long,
        northM: Double = 0.0,
        alt: Double? = null,
        speed: Float? = null,
        acc: Float? = 5f,
    ) = TrackPoint(timeMs = tSec * 1000, latitude = -8.05 + north(northM), longitude = -34.9, altitudeM = alt, speedMps = speed, accuracyM = acc)

    private fun assertNear(expected: Double, actual: Double, tol: Double, msg: String = "") =
        assertTrue(kotlin.math.abs(expected - actual) <= tol, "$msg esperado≈$expected obtido=$actual")

    @Test
    fun `haversine de 1 grau de latitude e cerca de 111,2 km`() {
        assertNear(111_195.0, Geo.distanceM(0.0, 0.0, 1.0, 0.0), 100.0)
        assertEquals(0.0, Geo.distanceM(10.0, 20.0, 10.0, 20.0), 1e-9)
    }

    @Test
    fun `trajeto em linha reta a 10 m por s soma distancia e tempo em movimento`() {
        val acc = TripStatsAccumulator()
        for (t in 0..60L) acc.add(pt(t, northM = t * 10.0, speed = 10f))
        val s = acc.snapshot()
        assertNear(600.0, s.distanceM, 1.0, "distância")
        assertEquals(60_000, s.elapsedMs)
        assertEquals(60_000, s.movingTimeMs)
        assertNear(36.0, s.avgSpeedKmh, 0.1, "média")
        assertNear(36.0, s.maxSpeedKmh, 0.1, "máxima")
        assertEquals(0, s.stopCount)
    }

    @Test
    fun `sem velocidade do aparelho deriva a velocidade da distancia`() {
        val acc = TripStatsAccumulator()
        for (t in 0..10L) acc.add(pt(t, northM = t * 20.0, speed = null))
        assertNear(200.0, acc.snapshot().distanceM, 1.0)
        assertNear(72.0, acc.snapshot().maxSpeedKmh, 0.5)
    }

    @Test
    fun `descarta ponto com precisao ruim`() {
        val acc = TripStatsAccumulator()
        assertTrue(acc.add(pt(0)))
        assertFalse(acc.add(pt(1, northM = 10.0, speed = 10f, acc = 80f)))
        assertEquals(1, acc.acceptedCount)
        assertEquals(0.0, acc.snapshot().distanceM)
    }

    @Test
    fun `descarta ponto fora de ordem ou duplicado no tempo`() {
        val acc = TripStatsAccumulator()
        acc.add(pt(10))
        assertFalse(acc.add(pt(10, northM = 5.0, speed = 5f)))
        assertFalse(acc.add(pt(9, northM = 5.0, speed = 5f)))
    }

    @Test
    fun `descarta salto de GPS implausivel`() {
        val acc = TripStatsAccumulator()
        acc.add(pt(0))
        assertFalse(acc.add(pt(1, northM = 5_000.0, speed = null))) // 5 km em 1 s
        assertTrue(acc.add(pt(2, northM = 20.0, speed = 10f)))
        assertNear(20.0, acc.snapshot().distanceM, 0.5)
    }

    @Test
    fun `parado nao soma distancia por deriva do GPS`() {
        val acc = TripStatsAccumulator()
        acc.add(pt(0))
        for (t in 1..30L) acc.add(pt(t, northM = (t % 3) * 2.0, speed = 0.2f)) // tremendo 0–4 m
        assertEquals(0.0, acc.snapshot().distanceM)
        assertEquals(0, acc.snapshot().movingTimeMs)
    }

    @Test
    fun `parada de 2 minutos conta uma parada e tempo parado, semaforo de 30 s nao conta`() {
        val acc = TripStatsAccumulator()
        var t = 0L
        var n = 0.0
        fun move(secs: Int) = repeat(secs) { t++; n += 10; acc.add(pt(t, n, speed = 10f)) }
        fun stop(secs: Int) = repeat(secs) { t++; acc.add(pt(t, n, speed = 0f)) }
        acc.add(pt(0, 0.0, speed = 10f))
        move(20)
        stop(30)   // semáforo
        move(20)
        stop(120)  // parada de verdade
        move(10)
        val s = acc.snapshot()
        assertEquals(1, s.stopCount)
        assertNear(120_000.0, s.stoppedTimeMs.toDouble(), 2_000.0, "tempo parado")
        assertEquals(50_000, s.movingTimeMs)
    }

    @Test
    fun `parada em andamento ja entra no snapshot apos o minimo`() {
        val acc = TripStatsAccumulator()
        acc.add(pt(0, 0.0, speed = 10f))
        acc.add(pt(1, 10.0, speed = 10f))
        for (t in 2..90L) acc.add(pt(t, 10.0, speed = 0f))
        assertEquals(1, acc.snapshot().stopCount)
        val shortStop = TripStatsAccumulator().also { a ->
            a.add(pt(0, 0.0, speed = 10f)); a.add(pt(1, 10.0, speed = 10f))
            for (t in 2..30L) a.add(pt(t, 10.0, speed = 0f))
        }
        assertEquals(0, shortStop.snapshot().stopCount)
    }

    @Test
    fun `altimetria usa histerese e ignora ruido de ate 3 m`() {
        val acc = TripStatsAccumulator()
        // ruído ±1 m em torno de 100 m (pico a pico 2 m < 3 m): nada de ganho
        for (t in 0..20L) acc.add(pt(t, t * 10.0, alt = 100.0 + (if (t % 2 == 0L) 1.0 else -1.0), speed = 10f))
        assertEquals(0.0, acc.snapshot().elevationGainM)
        assertEquals(0.0, acc.snapshot().elevationLossM)
    }

    @Test
    fun `altimetria soma subida e descida reais`() {
        val acc = TripStatsAccumulator()
        var t = 0L
        // sobe de 100 a 160 m em passos de 5 m, depois desce a 130
        for (alt in 100..160 step 5) { acc.add(pt(t, t * 10.0, alt = alt.toDouble(), speed = 10f)); t++ }
        for (alt in 155 downTo 130 step 5) { acc.add(pt(t, t * 10.0, alt = alt.toDouble(), speed = 10f)); t++ }
        val s = acc.snapshot()
        assertNear(60.0, s.elevationGainM, 0.01, "ganho")
        assertNear(30.0, s.elevationLossM, 0.01, "perda")
    }

    @Test
    fun `fromPoints reproduz o resultado incremental`() {
        val points = (0..120L).map { t -> pt(t, northM = if (t < 60) t * 10.0 else 600.0, speed = if (t < 60) 10f else 0f, alt = 50.0 + t / 4) }
        val inc = TripStatsAccumulator().also { a -> points.forEach { a.add(it) } }
        assertEquals(inc.snapshot(), TripStatsAccumulator.fromPoints(points).snapshot())
    }

    @Test
    fun `buraco longo nos dados nao soma a reta nem o tempo`() {
        val acc = TripStatsAccumulator()
        for (t in 0..10L) acc.add(pt(t, t * 10.0, speed = 10f))
        // 10 minutos sem dados e 6 km depois
        assertTrue(acc.add(pt(610, 6_100.0, speed = 10f)))
        for (t in 611..620L) acc.add(pt(t, 6_100.0 + (t - 610) * 10.0, speed = 10f))
        val s = acc.snapshot()
        assertNear(200.0, s.distanceM, 2.0, "só os dois trechos com dados")
        assertEquals(20_000, s.movingTimeMs)
        assertEquals(620_000, s.elapsedMs)
    }

    @Test
    fun `snapshot vazio e zerado`() {
        val s = TripStatsAccumulator().snapshot()
        assertEquals(TripStats(), s)
        assertEquals(0.0, s.avgSpeedKmh)
    }
}
