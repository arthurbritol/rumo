package app.hunter.app.ui

import app.hunter.nav.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotionSmootherTest {
    private val sec = 1_000_000_000L
    private val ms = 1_000_000L

    private fun near(e: Double, a: Double, tol: Double, msg: String = "") =
        assertTrue(kotlin.math.abs(e - a) <= tol, "$msg esperado≈$e obtido=$a")

    private fun fix(
        speed: Float? = 15f,
        bearing: Float? = 90f,
        acc: Float? = 5f,
        nanos: Long? = 0L,
        lat: Double = -8.0,
        lon: Double = -34.9,
    ) = MotionFix(lat, lon, speed, bearing, acc, nanos)

    // ---------- predição ----------

    @Test
    fun `predicao anda na direcao do rumo pela velocidade e tempo`() {
        val p = predictPosition(fix(speed = 10f, bearing = 90f), 0L, 1 * sec) // 10 m a leste
        near(-8.0, p.lat, 1e-6)
        near(10.0, distanceM(LatLon(-8.0, -34.9), p), 0.1)
        assertTrue(p.lon > -34.9)
    }

    @Test
    fun `predicao e limitada a 1,5 s`() {
        val p = predictPosition(fix(speed = 10f), 0L, 10 * sec)
        near(15.0, distanceM(LatLon(-8.0, -34.9), p), 0.1)
    }

    @Test
    fun `predicao limita a velocidade implausivel`() {
        val p = predictPosition(fix(speed = 500f), 0L, 1 * sec)
        near(MOTION_MAX_PLAUSIBLE_SPEED_MPS, distanceM(LatLon(-8.0, -34.9), p), 0.1)
    }

    @Test
    fun `parado ou sem rumo nao preve`() {
        val a = predictPosition(fix(speed = 0.5f), 0L, 1 * sec)
        assertEquals(LatLon(-8.0, -34.9), a)
        val b = predictPosition(fix(speed = 15f, bearing = null), 0L, 1 * sec)
        assertEquals(LatLon(-8.0, -34.9), b)
    }

    // ---------- rumo ----------

    @Test
    fun `curva de 180 graus passa pelo caminho curto`() {
        near(-30.0, shortestAngleDelta(10.0, 340.0), 1e-9)
        near(30.0, shortestAngleDelta(350.0, 20.0), 1e-9)
        // 350 -> 20: cada passo aumenta o ângulo atravessando 0, nunca dá a volta longa
        var b = 350.0
        repeat(60) {
            val n = lowPassBearing(b, 20.0, 1.0 / 30)
            assertTrue(shortestAngleDelta(b, n) >= 0.0, "andou para trás: $b -> $n")
            b = n
        }
        near(20.0, b, 1.0)
    }

    @Test
    fun `retorno em U gira sem estalo (limite de taxa)`() {
        var b = 0.0
        val first = lowPassBearing(b, 180.0, 1.0 / 30)
        assertTrue(angleDistance(0.0, first) <= MOTION_MAX_BEARING_RATE_DPS / 30 + 1e-9)
        repeat(90) { b = lowPassBearing(b, 180.0, 1.0 / 30) }
        near(180.0, b, 3.0)
    }

    @Test
    fun `histerese de velocidade liga acima de 2 e desliga abaixo de 1`() {
        val h = MovingHysteresis()
        assertFalse(h.update(1.5f))
        assertTrue(h.update(2.5f))
        assertTrue(h.update(1.5f), "entre 1 e 2 mantém andando")
        assertTrue(h.update(null), "sem velocidade mantém")
        assertFalse(h.update(0.5f))
        assertFalse(h.update(1.5f), "entre 1 e 2 mantém parado")
    }

    @Test
    fun `rumo nulo nunca vira zero e rumo da rota vale na navegacao`() {
        val s = MotionSmoother()
        var now = 0L
        assertTrue(s.onFix(fix(speed = 10f, bearing = 270f, nanos = now), now))
        var f = s.frame(now, false, 16.0)!!
        near(270.0, f.bearingDeg!!, 1e-6)
        // novos fixes sem rumo (ou parados): mantém 270
        repeat(5) {
            now += sec
            s.onFix(fix(speed = 10f, bearing = null, nanos = now), now)
            f = s.frame(now, false, 16.0)!!
        }
        near(270.0, f.bearingDeg!!, 1e-6)
        now += sec
        s.onFix(fix(speed = 0f, bearing = 0f, nanos = now), now)
        f = s.frame(now, false, 16.0)!!
        near(270.0, f.bearingDeg!!, 1e-6, "parado com rumo 0 do GPS não gira")
    }

    @Test
    fun `sem nenhum rumo conhecido o frame traz rumo nulo`() {
        val s = MotionSmoother()
        s.onFix(fix(speed = 0f, bearing = null), 0L)
        assertNull(s.frame(0L, false, 16.0)!!.bearingDeg)
    }

    // ---------- zoom e câmera à frente ----------

    @Test
    fun `zoom por velocidade`() {
        near(18.0, zoomForSpeed(0.0), 1e-9)
        near(18.0, zoomForSpeed(2.0), 1e-9)
        near(16.0, zoomForSpeed(25.0), 1e-9) // 90 km/h
        near(16.0, zoomForSpeed(40.0), 1e-9)
        val mid = zoomForSpeed(14.0)
        assertTrue(mid < 18.0 && mid > 16.0)
        assertTrue(zoomForSpeed(10.0) > zoomForSpeed(20.0))
    }

    @Test
    fun `zoom varia no maximo 0,25 por segundo`() {
        var z = 18.0
        repeat(30) { z = stepZoom(z, 16.0, 1.0 / 30) } // 1 s
        assertTrue(18.0 - z <= MOTION_ZOOM_MAX_RATE_PER_S + 1e-9, "variou $z")
        assertTrue(z < 18.0)
    }

    @Test
    fun `camera a frente cresce com a velocidade`() {
        assertTrue(aheadFractionForSpeed(0.0) < aheadFractionForSpeed(25.0))
        near(MOTION_AHEAD_MIN_FRACTION, aheadFractionForSpeed(0.0), 1e-9)
        near(MOTION_AHEAD_MAX_FRACTION, aheadFractionForSpeed(99.0), 1e-9)
        assertTrue(aheadMeters(0.2, 1000.0, 16.0, -8.0, 50.0) > aheadMeters(0.2, 1000.0, 18.0, -8.0, 50.0))
    }

    @Test
    fun `camera a frente em metros tem valor absoluto esperado (dp, nao pixels)`() {
        // Equador, zoom 17: 78271,516 / 2^17 = 0,59716 m por dp. Altura útil 800 dp, fração 0,2 -> 160 dp -> 95,55 m.
        near(95.5456, aheadMeters(0.2, 800.0, 17.0, 0.0, 0.0), 0.05)
        // Inclinação de 60 graus: cos = 0,5 -> o dobro.
        near(191.0912, aheadMeters(0.2, 800.0, 17.0, 0.0, 60.0), 0.1)
        // Latitude -60: cos = 0,5 -> metade.
        near(47.7728, aheadMeters(0.2, 800.0, 17.0, -60.0, 0.0), 0.05)
        // Um telefone de densidade 3 com 2400 px de altura = 800 dp: o chamador converte px -> dp antes de chamar.
        near(aheadMeters(0.2, 800.0, 17.0, 0.0, 0.0), aheadMeters(0.2, 2400.0 / 3.0, 17.0, 0.0, 0.0), 1e-9)
    }

    // ---------- filtros de fix ----------

    @Test
    fun `descarta fix velho pelo relogio monotonico`() {
        val s = MotionSmoother()
        val now = 100 * sec
        assertFalse(s.onFix(fix(nanos = now - 6 * sec), now))
        assertNull(s.frame(now, false, 16.0))
        assertTrue(s.onFix(fix(nanos = now - 1 * sec), now))
        assertNotNull(s.frame(now, false, 16.0))
    }

    @Test
    fun `descarta fix impreciso mas aceita com GPS mudo`() {
        val s = MotionSmoother()
        assertTrue(s.onFix(fix(acc = 8f, nanos = 0L), 0L))
        // GPS bom há 2 s: fix de rede impreciso é ignorado
        assertFalse(s.onFix(fix(acc = 120f, nanos = 2 * sec, lat = -8.001), 2 * sec))
        // GPS mudo há 9 s: aceita o de rede (até 200 m); pior que isso, não
        assertTrue(s.onFix(fix(acc = 120f, nanos = 9 * sec, lat = -8.001), 9 * sec))
        assertFalse(s.onFix(fix(acc = 400f, nanos = 20 * sec), 20 * sec))
        // primeiro fix já impreciso (>50) sem nada antes: aceita só até 200
        val t = MotionSmoother()
        assertFalse(t.onFix(fix(acc = 60f + 300f), 0L))
    }

    @Test
    fun `ignora fix fora de ordem`() {
        val s = MotionSmoother()
        assertTrue(s.onFix(fix(nanos = 2 * sec), 2 * sec))
        assertFalse(s.onFix(fix(nanos = 1 * sec), 2 * sec))
    }

    // ---------- quadro a quadro ----------

    @Test
    fun `em movimento a posicao desenhada avanca suave sem teleportar`() {
        val s = MotionSmoother()
        var now = 0L
        s.onFix(fix(speed = 10f, bearing = 90f, nanos = now), now)
        s.frame(now, false, 16.0)
        var prev = s.frame(now, false, 16.0)!!
        var maxStep = 0.0
        // 3 s a 30 fps, com um fix novo a cada 1 s (posição real avançando 10 m/s)
        var lon = -34.9
        for (i in 1..90) {
            now += sec / 30
            if (i % 30 == 0) {
                lon += 10.0 / (111_194.9 * kotlin.math.cos(Math.toRadians(-8.0)))
                s.onFix(fix(speed = 10f, bearing = 90f, nanos = now, lon = lon), now)
            }
            val f = s.frame(now, false, 16.0)!!
            maxStep = maxOf(maxStep, distanceM(LatLon(prev.lat, prev.lon), LatLon(f.lat, f.lon)))
            prev = f
        }
        assertTrue(maxStep < 1.0, "salto de $maxStep m num quadro (esperado ~0,33 m)")
        // ao fim de 3 s, perto da posição real (atraso do alcance exponencial de poucos metros)
        // lag estacionário do alcance exponencial ~ v*tau = 2,5 m
        near(2.5, distanceM(LatLon(prev.lat, prev.lon), LatLon(-8.0, lon)), 2.0)
    }

    @Test
    fun `parado nao anda com a deriva do GPS`() {
        val s = MotionSmoother()
        var now = 0L
        s.onFix(fix(speed = 0f, bearing = null, nanos = now), now)
        val a = s.frame(now, false, 16.0)!!
        repeat(10) { i ->
            now += sec
            // deriva de ~2 m
            s.onFix(fix(speed = 0.2f, bearing = null, nanos = now, lat = -8.0 + (if (i % 2 == 0) 2.0 else -2.0) / 111_194.9), now)
            val f = s.frame(now, false, 16.0)!!
            assertEquals(a.lat, f.lat, 0.0)
            assertFalse(f.moving)
        }
    }

    @Test
    fun `retomada longe salta em vez de deslizar`() {
        val s = MotionSmoother()
        s.onFix(fix(speed = 0f, nanos = 0L), 0L)
        s.frame(0L, false, 16.0)
        s.onFix(fix(speed = 0f, nanos = 1 * sec, lat = -8.01), 1 * sec)
        val f = s.frame(1 * sec, false, 16.0)!!
        near(-8.01, f.lat, 1e-9)
    }

    @Test
    fun `zoom adaptativo no quadro comeca no alvo e limita a taxa`() {
        val s = MotionSmoother()
        var now = 0L
        s.onFix(fix(speed = 0f, bearing = null, nanos = 0L), 0L)
        near(18.0, s.frame(now, true, 17.0)!!.zoom, 1e-9)
        // acelera para 25 m/s: em 1 s o zoom cai no máximo 0,25
        s.onFix(fix(speed = 25f, bearing = 90f, nanos = now), now)
        var z = 18.0
        repeat(30) { now += sec / 30; z = s.frame(now, true, 17.0)!!.zoom }
        assertTrue(18.0 - z <= 0.25 + 1e-9 && z < 18.0, "zoom=$z")
        // fora da navegação: zoom fixo
        near(17.0, s.frame(now, false, 17.0)!!.zoom, 1e-9)
    }

    // ---------- snap à rota ----------

    private fun eastRoute(): RoutePath {
        val dLon = 1000.0 / 111_194.9
        return RoutePath(listOf(LatLon(0.0, 0.0), LatLon(0.0, dLon), LatLon(1000.0 / 111_194.9, dLon)))
    }

    @Test
    fun `caminho da rota anda e da o rumo do trecho`() {
        val p = eastRoute()
        near(2000.0, p.lengthM, 2.0)
        near(90.0, p.bearingAt(100.0), 0.5)
        near(0.0, angleDistance(0.0, p.bearingAt(1500.0)), 0.5)
        val q = p.pointAt(500.0)
        near(0.0, q.lat, 1e-9)
        near(500.0, distanceM(LatLon(0.0, 0.0), q), 1.0)
        near(2000.0, p.lengthM, 2.0)
        val end = p.pointAt(99_999.0)
        near(1000.0 / 111_194.9, end.lat, 1e-6)
    }

    @Test
    fun `perto da rota desenha sobre o traco com o rumo da rota`() {
        val s = MotionSmoother()
        val path = eastRoute()
        val now = 0L
        // GPS 12 m ao norte do traçado, rumo do GPS ruidoso (80°)
        s.onFix(fix(speed = 10f, bearing = 80f, nanos = now, lat = 12.0 / 111_194.9, lon = 500.0 / 111_194.9), now)
        s.onSnap(path, 500.0, 12.0, false, now)
        val f = s.frame(now, true, 17.0)!!
        assertTrue(f.snapped)
        near(0.0, f.lat, 1e-9, "sobre o traçado (lat 0)")
        near(90.0, f.bearingDeg!!, 0.5, "rumo da rota, não o do GPS")
    }

    @Test
    fun `desvio grande ou fora da rota volta ao GPS suavizado`() {
        val s = MotionSmoother()
        val path = eastRoute()
        s.onFix(fix(speed = 10f, bearing = 80f, nanos = 0L, lat = 30.0 / 111_194.9, lon = 500.0 / 111_194.9), 0L)
        s.onSnap(path, 500.0, 30.0, false, 0L)
        assertFalse(s.frame(0L, true, 17.0)!!.snapped)
        s.onSnap(path, 500.0, 5.0, true, 0L)
        assertFalse(s.frame(0L, true, 17.0)!!.snapped)
        s.onSnap(path, 500.0, 5.0, false, 0L)
        assertTrue(s.frame(0L, true, 17.0)!!.snapped)
    }

    @Test
    fun `na rota a predicao segue a curva do traco`() {
        val s = MotionSmoother()
        val path = eastRoute()
        // a 20 m/s, 20 m antes da curva (along 980; a curva está em 1000)
        s.onFix(fix(speed = 20f, bearing = 90f, nanos = 0L, lat = 0.0, lon = 980.0 / 111_194.9), 0L)
        s.onSnap(path, 980.0, 0.0, false, 0L)
        var now = 0L
        val start = s.frame(now, true, 17.0)!!
        var f = start
        repeat(45) { now += sec / 30; f = s.frame(now, true, 17.0)!! } // 1,5 s: alvo em along 1010 (10 m depois da curva)
        val moved = distanceM(LatLon(start.lat, start.lon), LatLon(f.lat, f.lon))
        assertTrue(moved > 20.0, "andou só $moved m")
        // Depois da curva: subiu para o norte (>3 m) e continua na coluna da curva (sem cortar por fora).
        assertTrue(f.lat * 111_194.9 > 3.0, "norte=${f.lat * 111_194.9}")
        assertTrue(f.lon <= 1000.0 / 111_194.9 + 1e-7)
        assertTrue(angleDistance(0.0, f.bearingDeg!!) < 45.0, "já girando para o norte: ${f.bearingDeg}")
        repeat(25) { now += sec / 30; f = s.frame(now, true, 17.0)!! } // (antes de o snap expirar, 3 s) a predição parou no limite; o rumo converge
        near(0.0, angleDistance(0.0, f.bearingDeg!!), 5.0, "rumo do trecho norte")
    }

    @Test
    fun `quadro com dt zero ou negativo nao quebra nem anda`() {
        val s = MotionSmoother()
        s.onFix(fix(speed = 10f, bearing = 90f, nanos = 10 * sec), 10 * sec)
        val a = s.frame(10 * sec, true, 17.0)!!
        val b = s.frame(10 * sec, true, 17.0)!! // dt zero
        val c = s.frame(9 * sec, true, 17.0)!! // relógio voltou: dt negativo
        for (f in listOf(b, c)) {
            assertFalse(f.lat.isNaN() || f.lon.isNaN() || f.zoom.isNaN() || f.bearingDeg!!.isNaN())
            near(a.lat, f.lat, 1e-6); near(a.lon, f.lon, 1e-6)
            near(a.zoom, f.zoom, 1e-9)
        }
    }

    @Test
    fun `passa-baixa de 359 para 1 gira 2 graus para a frente`() {
        val n = lowPassBearing(359.0, 1.0, 10.0) // tempo longo: converge
        near(1.0, n, 0.01)
        val step = lowPassBearing(359.0, 1.0, 1.0 / 30)
        assertTrue(shortestAngleDelta(359.0, step) in 0.0..2.0, "passo=$step")
    }

    @Test
    fun `ao parar sobre a rota mantem o rumo desenhado e nao o do GPS`() {
        val s = MotionSmoother()
        val path = eastRoute()
        var now = 0L
        // andando: o GPS diz 80, a rota diz 90
        s.onFix(fix(speed = 10f, bearing = 80f, nanos = now, lat = 0.0, lon = 500.0 / 111_194.9), now)
        s.onSnap(path, 500.0, 2.0, false, now)
        var f = s.frame(now, true, 17.0)!!
        repeat(90) { now += sec / 30; f = s.frame(now, true, 17.0)!! }
        near(90.0, f.bearingDeg!!, 1.0)
        // para: o último rumo do GPS (80) não pode puxar a seta de volta
        now += sec / 30
        s.onFix(fix(speed = 0f, bearing = 80f, nanos = now, lat = 0.0, lon = 500.0 / 111_194.9), now)
        repeat(30) { now += sec / 30; f = s.frame(now, true, 17.0)!! }
        assertFalse(f.moving)
        near(90.0, f.bearingDeg!!, 1.0)
    }

    @Test
    fun `zoom ao trocar de modo comeca no alvo e nao se arrasta`() {
        val s = MotionSmoother()
        var now = 0L
        s.onFix(fix(speed = 0f, bearing = null, nanos = now), now)
        repeat(10) { now += sec / 30; near(15.0, s.frame(now, false, 15.0)!!.zoom, 1e-9) }
        // entra na navegação: o zoom já é o alvo (18 parado), não 15 -> 18 a 0,25/s (12 s)
        now += sec / 30
        near(18.0, s.frame(now, true, 15.0)!!.zoom, 1e-9)
        // sai e volta: de novo no alvo
        now += sec / 30
        near(16.0, s.frame(now, false, 16.0)!!.zoom, 1e-9)
        now += sec / 30
        near(18.0, s.frame(now, true, 16.0)!!.zoom, 1e-9)
    }

    @Test
    fun `snap expira sem progresso novo e com fix longe do ponto`() {
        val path = eastRoute()
        val s = MotionSmoother()
        s.onFix(fix(speed = 10f, bearing = 90f, nanos = 0L, lat = 0.0, lon = 500.0 / 111_194.9), 0L)
        s.onSnap(path, 500.0, 2.0, false, 0L)
        assertTrue(s.frame(1 * sec, true, 17.0)!!.snapped)
        assertFalse(s.frame(4 * sec, true, 17.0)!!.snapped, "sem atualização há 4 s")

        val t = MotionSmoother()
        // fix a ~100 m do ponto do snap (progresso atrasado em relação ao GPS)
        t.onFix(fix(speed = 10f, bearing = 90f, nanos = 0L, lat = 0.0, lon = 600.0 / 111_194.9), 0L)
        t.onSnap(path, 500.0, 2.0, false, 0L)
        assertFalse(t.frame(0L, true, 17.0)!!.snapped)
    }

    @Test
    fun `snap tem histerese de desvio (entra abaixo de 25, sai acima de 40)`() {
        val path = eastRoute()
        val s = MotionSmoother()
        s.onFix(fix(speed = 10f, bearing = 90f, nanos = 0L, lat = 0.0, lon = 500.0 / 111_194.9), 0L)
        s.onSnap(path, 500.0, 30.0, false, 0L)
        assertFalse(s.frame(0L, true, 17.0)!!.snapped, "30 m não entra")
        s.onSnap(path, 500.0, 20.0, false, 0L)
        assertTrue(s.frame(0L, true, 17.0)!!.snapped)
        s.onSnap(path, 500.0, 35.0, false, 0L)
        assertTrue(s.frame(0L, true, 17.0)!!.snapped, "35 m mantém")
        s.onSnap(path, 500.0, 45.0, false, 0L)
        assertFalse(s.frame(0L, true, 17.0)!!.snapped, "45 m sai")
    }

    @Test
    fun `semente do mapa aceita fix velho ou impreciso e depois filtra`() {
        val now = 100 * sec
        assertTrue(isLiveFixUsable(now - 60 * sec, 900f, now, hasCurrent = false))
        assertFalse(isLiveFixUsable(now - 60 * sec, 10f, now, hasCurrent = true))
    }

    @Test
    fun `progresso de outra rota (ponto projetado nao bate com o tracado) e ignorado`() {
        val s = MotionSmoother()
        val path = eastRoute()
        s.onFix(fix(speed = 10f, bearing = 90f, nanos = 0L, lat = 0.0, lon = 500.0 / 111_194.9), 0L)
        // ponto projetado a ~500 m de onde o "along" cai no traçado novo
        s.onSnap(path, 100.0, 5.0, false, 0L, LatLon(0.0, 500.0 / 111_194.9))
        assertFalse(s.frame(0L, true, 17.0)!!.snapped)
        s.onSnap(path, 500.0, 5.0, false, 0L, LatLon(0.0, 500.0 / 111_194.9))
        assertTrue(s.frame(0L, true, 17.0)!!.snapped)
    }

    @Test
    fun `filtro de entrada do mapa descarta velho e impreciso`() {
        val now = 100 * sec
        assertTrue(isLiveFixUsable(now - 2 * sec, 10f, now))
        assertTrue(isLiveFixUsable(null, null, now))
        assertFalse(isLiveFixUsable(now - 30 * sec, 10f, now), "lastKnown velho")
        assertFalse(isLiveFixUsable(now - 1 * sec, 900f, now), "impreciso")
    }
}
