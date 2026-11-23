package app.hunter.app.ui

import app.hunter.nav.LatLon
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Movimento do mapa "estilo Waze": lógica pura (sem Android) que transforma os fixes de GPS (1 Hz, ruidosos)
 * em uma posição, um rumo e um zoom suaves, calculados a cada quadro. O HunterMap só chama [MotionSmoother.frame]
 * e aplica o resultado na câmera e no marcador.
 */

// ---------- Constantes ----------

/** Fix mais velho que isto (relógio monotônico) não vai para o desenho. */
const val MOTION_MAX_FIX_AGE_MS = 5_000L

/** Precisão pior que isto não vai para o desenho (salvo se o GPS estiver mudo, ver [MOTION_GPS_SILENCE_MS]). */
const val MOTION_MAX_ACCURACY_M = 50f

/** Sem fix bom há mais que isto: aceita um fix pior (rede), até [MOTION_FALLBACK_ACCURACY_M]. */
const val MOTION_GPS_SILENCE_MS = 8_000L
const val MOTION_FALLBACK_ACCURACY_M = 200f

/** Quanto no máximo se prevê à frente do último fix (o próximo chega em ~1 s; passado isso, o marcador para). */
const val MOTION_PREDICT_MAX_S = 1.5

/** Abaixo disto não se prevê (parado ou arrastando-se: o rumo do GPS é lixo). */
const val MOTION_PREDICT_MIN_SPEED_MPS = 1.0

/** Velocidade plausível máxima para a predição (252 km/h); acima disso a velocidade é tratada como erro. */
const val MOTION_MAX_PLAUSIBLE_SPEED_MPS = 70.0

/** Histerese do "está andando": liga acima de 2 m/s, desliga abaixo de 1 m/s (evita a seta girando no semáforo). */
const val MOTION_MOVING_ON_MPS = 2.0
const val MOTION_MOVING_OFF_MPS = 1.0

/** Constantes de tempo (s) do alcance exponencial da posição e do rumo desenhados. */
const val MOTION_POSITION_TAU_S = 0.25
const val MOTION_BEARING_TAU_S = 0.35

/** Velocidade angular máxima da câmera (graus/s): curva fechada gira rápido, mas sem "estalo". */
const val MOTION_MAX_BEARING_RATE_DPS = 180.0

/** Se o alvo estiver mais longe que isto do desenhado (túnel, retomada), salta em vez de deslizar. */
const val MOTION_JUMP_DISTANCE_M = 100.0

/** Parado, deriva do GPS menor que isto é ignorada (o marcador não "anda" sozinho). */
const val MOTION_STILL_DEADBAND_M = 4.0

/** Marcador sobre o traçado só quando o desvio é menor que isto (e não está fora da rota). */
const val MOTION_SNAP_MAX_DEVIATION_M = 25.0

/** O progresso da navegação deve cair a no máximo isto do ponto do traçado: senão é de outra rota (recalculada) e é ignorado. */
const val MOTION_SNAP_CONSISTENCY_M = 15.0

/** Zoom adaptativo: [MOTION_ZOOM_NEAR] até [MOTION_ZOOM_SLOW_MPS]; [MOTION_ZOOM_FAR] a partir de [MOTION_ZOOM_FAST_MPS] (~90 km/h). */
const val MOTION_ZOOM_NEAR = 18.0
const val MOTION_ZOOM_FAR = 16.0
const val MOTION_ZOOM_SLOW_MPS = 3.0
const val MOTION_ZOOM_FAST_MPS = 25.0

/** O zoom nunca varia mais que isto por segundo, e converge com esta constante de tempo (s). */
const val MOTION_ZOOM_MAX_RATE_PER_S = 0.25
const val MOTION_ZOOM_TAU_S = 1.5

/** Câmera à frente: fração da altura útil da tela que o marcador fica abaixo do centro (parado → rápido). */
const val MOTION_AHEAD_MIN_FRACTION = 0.12
const val MOTION_AHEAD_MAX_FRACTION = 0.22

/** Intervalo máximo entre quadros considerado nos cálculos (um quadro travado não pode "teleportar" nada). */
private const val MOTION_MAX_DT_S = 0.25

/** No ViewModel: leitura mais velha que isto (típico do `lastKnownLocation` na partida) não vai para o mapa. */
const val LIVE_MAX_AGE_MS = 10_000L

private const val M_PER_DEG = 111_194.9

// ---------- Entrada / saída ----------

/** Um fix de GPS reduzido ao que o movimento precisa. [elapsedNanos] é o `Location.elapsedRealtimeNanos` (nulo = "agora"). */
data class MotionFix(
    val lat: Double,
    val lon: Double,
    val speedMps: Float?,
    val bearingDeg: Float?,
    val accuracyM: Float?,
    val elapsedNanos: Long?,
)

/** O que desenhar neste quadro. [bearingDeg] nulo = ainda não há rumo válido (mantenha o da câmera). */
data class MotionFrame(
    val lat: Double,
    val lon: Double,
    val bearingDeg: Double?,
    val speedMps: Double,
    val moving: Boolean,
    /** Zoom adaptativo à velocidade (já suavizado). */
    val zoom: Double,
    /** Fração da altura útil da tela em que a câmera avança à frente do marcador (0 = sobre o marcador). */
    val aheadFraction: Double,
    /** O marcador está sobre o traçado da rota. */
    val snapped: Boolean,
)

// ---------- Funções puras ----------

/** Idade (ms) de um fix pelo relógio monotônico. */
fun fixAgeMs(nowNanos: Long, fixNanos: Long): Long = (nowNanos - fixNanos) / 1_000_000L

/**
 * Filtro de entrada do mapa: descarta o fix velho (relógio monotônico; sem carimbo, passa) ou pior que
 * [MOTION_FALLBACK_ACCURACY_M]. O refinamento por quadro (50 m, GPS mudo) fica no [MotionSmoother].
 */
fun isLiveFixUsable(elapsedNanos: Long?, accuracyM: Float?, nowNanos: Long): Boolean {
    if (elapsedNanos != null && fixAgeMs(nowNanos, elapsedNanos) > LIVE_MAX_AGE_MS) return false
    if (accuracyM != null && accuracyM > MOTION_FALLBACK_ACCURACY_M) return false
    return true
}

/** Menor diferença angular alvo − atual, em (−180, 180]. */
fun shortestAngleDelta(from: Double, to: Double): Double {
    var d = (to - from) % 360.0
    if (d > 180.0) d -= 360.0
    if (d <= -180.0) d += 360.0
    return d
}

fun normalizeDeg(a: Double): Double = ((a % 360.0) + 360.0) % 360.0

/** Filtro passa-baixa circular: vai de [current] a [target] pelo caminho mais curto, no máximo [maxRateDps]. */
fun lowPassBearing(
    current: Double,
    target: Double,
    dtS: Double,
    tauS: Double = MOTION_BEARING_TAU_S,
    maxRateDps: Double = MOTION_MAX_BEARING_RATE_DPS,
): Double {
    val alpha = 1.0 - exp(-dtS / tauS)
    val step = shortestAngleDelta(current, target) * alpha
    val cap = maxRateDps * dtS
    return normalizeDeg(current + step.coerceIn(-cap, cap))
}

/** Deslocamento de [p] por [meters] no rumo [bearingDeg]. */
fun offsetLatLon(p: LatLon, bearingDeg: Double, meters: Double): LatLon {
    val r = Math.toRadians(bearingDeg)
    val dLat = meters * cos(r) / M_PER_DEG
    val dLon = meters * sin(r) / (M_PER_DEG * max(0.01, cos(Math.toRadians(p.lat))))
    return LatLon(p.lat + dLat, p.lon + dLon)
}

fun distanceM(a: LatLon, b: LatLon): Double {
    val dx = (b.lon - a.lon) * cos(Math.toRadians((a.lat + b.lat) / 2)) * M_PER_DEG
    val dy = (b.lat - a.lat) * M_PER_DEG
    return sqrt(dx * dx + dy * dy)
}

/**
 * Dead-reckoning: onde o motociclista deve estar [nowNanos] − [fixNanos] depois do fix, limitado a
 * [MOTION_PREDICT_MAX_S] e à velocidade plausível. Parado/lento ou sem rumo: a posição do próprio fix.
 */
fun predictPosition(fix: MotionFix, fixNanos: Long, nowNanos: Long): LatLon {
    val here = LatLon(fix.lat, fix.lon)
    val speed = (fix.speedMps ?: 0f).toDouble()
    val bearing = fix.bearingDeg
    if (speed < MOTION_PREDICT_MIN_SPEED_MPS || bearing == null) return here
    val dt = ((nowNanos - fixNanos) / 1e9).coerceIn(0.0, MOTION_PREDICT_MAX_S)
    return offsetLatLon(here, bearing.toDouble(), min(speed, MOTION_MAX_PLAUSIBLE_SPEED_MPS) * dt)
}

/** Zoom-alvo para a velocidade: [MOTION_ZOOM_NEAR] devagar, [MOTION_ZOOM_FAR] em ~90 km/h, linear entre os dois. */
fun zoomForSpeed(speedMps: Double): Double {
    val t = ((speedMps - MOTION_ZOOM_SLOW_MPS) / (MOTION_ZOOM_FAST_MPS - MOTION_ZOOM_SLOW_MPS)).coerceIn(0.0, 1.0)
    return MOTION_ZOOM_NEAR + (MOTION_ZOOM_FAR - MOTION_ZOOM_NEAR) * t
}

/** Um passo do zoom em direção ao alvo, suave e com variação máxima por segundo. */
fun stepZoom(current: Double, target: Double, dtS: Double): Double {
    val alpha = 1.0 - exp(-dtS / MOTION_ZOOM_TAU_S)
    val cap = MOTION_ZOOM_MAX_RATE_PER_S * dtS
    return current + ((target - current) * alpha).coerceIn(-cap, cap)
}

/** Fração da altura útil que a câmera avança à frente do marcador: pouco parado, mais rápido (marcador no terço inferior). */
fun aheadFractionForSpeed(speedMps: Double): Double {
    val t = (speedMps / MOTION_ZOOM_FAST_MPS).coerceIn(0.0, 1.0)
    return MOTION_AHEAD_MIN_FRACTION + (MOTION_AHEAD_MAX_FRACTION - MOTION_AHEAD_MIN_FRACTION) * t
}

/**
 * Converte a fração da altura útil em metros no chão. 512 px por tile (MapLibre): m/px = 78271.5·cos(lat)/2^zoom.
 * Com inclinação, o chão aparece encurtado na vertical: divide por cos(tilt) (limitado para não explodir).
 */
fun aheadMeters(fraction: Double, usableHeightPx: Double, zoom: Double, lat: Double, tiltDeg: Double): Double {
    val mPerPx = 78_271.516 * cos(Math.toRadians(lat)) / Math.pow(2.0, zoom)
    val foreshortening = 1.0 / max(0.5, cos(Math.toRadians(tiltDeg)))
    return fraction * usableHeightPx * mPerPx * foreshortening
}

/** Histerese do "está andando": liga acima de [MOTION_MOVING_ON_MPS], desliga abaixo de [MOTION_MOVING_OFF_MPS]. */
class MovingHysteresis {
    var moving = false
        private set

    fun update(speedMps: Float?): Boolean {
        val s = speedMps ?: return moving // sem velocidade: mantém
        if (!moving && s > MOTION_MOVING_ON_MPS) moving = true
        else if (moving && s < MOTION_MOVING_OFF_MPS) moving = false
        return moving
    }

    fun reset() { moving = false }
}

/** Traçado com distâncias acumuladas, para andar [alongM] metros sobre a rota e ler o rumo do trecho. */
class RoutePath(shape: List<LatLon>) {
    private val lat = DoubleArray(shape.size) { shape[it].lat }
    private val lon = DoubleArray(shape.size) { shape[it].lon }
    private val cum = DoubleArray(shape.size)

    init {
        for (i in 1 until shape.size) cum[i] = cum[i - 1] + distanceM(shape[i - 1], shape[i])
    }

    val lengthM: Double get() = if (cum.isEmpty()) 0.0 else cum.last()
    val isUsable: Boolean get() = cum.size >= 2 && lengthM > 0.0

    private fun segmentAt(alongM: Double): Int {
        val a = alongM.coerceIn(0.0, lengthM)
        var lo = 0
        var hi = cum.size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cum[mid] <= a) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun pointAt(alongM: Double): LatLon {
        val a = alongM.coerceIn(0.0, lengthM)
        val i = segmentAt(a)
        val len = cum[i + 1] - cum[i]
        val t = if (len <= 1e-9) 0.0 else (a - cum[i]) / len
        return LatLon(lat[i] + t * (lat[i + 1] - lat[i]), lon[i] + t * (lon[i + 1] - lon[i]))
    }

    /** Rumo do trecho (0..360) no ponto [alongM]; pula trechos de comprimento zero. */
    fun bearingAt(alongM: Double): Double {
        var i = segmentAt(alongM)
        while (i < cum.size - 2 && cum[i + 1] - cum[i] <= 1e-9) i++
        val dx = (lon[i + 1] - lon[i]) * cos(Math.toRadians((lat[i] + lat[i + 1]) / 2))
        val dy = lat[i + 1] - lat[i]
        if (dx == 0.0 && dy == 0.0) return 0.0
        return normalizeDeg(atan2(dx, dy) * 180.0 / PI)
    }
}

// ---------- Estado por quadro ----------

/**
 * Suavizador de movimento. Uma instância por mapa; chame [onFix] a cada fix, [onSnap] a cada progresso da
 * navegação e [frame] a cada quadro. Tudo com o relógio monotônico (nanos) passado de fora: testável sem Android.
 * Não é thread-safe (usar só da thread principal).
 */
class MotionSmoother {
    private var lastFix: MotionFix? = null
    private var lastFixStamp = 0L
    private val hysteresis = MovingHysteresis()
    private var heldBearing: Double? = null

    private var snapPath: RoutePath? = null
    private var snapAlong = 0.0
    private var snapStamp = 0L

    private var drawn: LatLon? = null
    private var drawnBearing: Double? = null
    private var zoom: Double? = null
    private var lastFrameNanos: Long? = null

    /** @return true se o fix foi aceito para o desenho. */
    fun onFix(fix: MotionFix, nowNanos: Long): Boolean {
        val stamp = fix.elapsedNanos ?: nowNanos
        if (fixAgeMs(nowNanos, stamp) > MOTION_MAX_FIX_AGE_MS) return false
        if (lastFix != null && stamp < lastFixStamp) return false // fora de ordem
        val acc = fix.accuracyM
        if (acc != null && acc > MOTION_MAX_ACCURACY_M) {
            // Só um fix impreciso (rede) quando o GPS está mudo há tempo; senão o GPS bom continua mandando.
            val silent = lastFix == null || fixAgeMs(nowNanos, lastFixStamp) > MOTION_GPS_SILENCE_MS
            if (!silent || acc > MOTION_FALLBACK_ACCURACY_M) return false
        }
        lastFix = fix
        lastFixStamp = stamp
        val moving = hysteresis.update(fix.speedMps)
        val b = fix.bearingDeg
        if (moving && b != null) heldBearing = normalizeDeg(b.toDouble())
        return true
    }

    /**
     * Progresso da navegação: [path] é o traçado, [alongM] a distância já percorrida. Fora da rota ou com desvio
     * ≥ [MOTION_SNAP_MAX_DEVIATION_M] (ou [alongM] nulo), o marcador volta a seguir o GPS suavizado.
     */
    fun onSnap(
        path: RoutePath?,
        alongM: Double?,
        deviationM: Double,
        offRoute: Boolean,
        nowNanos: Long,
        /** Ponto projetado informado pela navegação; se não coincide com [path] em [alongM], o progresso é de outra rota. */
        snapPoint: LatLon? = null,
    ) {
        if (path == null || alongM == null || !path.isUsable || offRoute || deviationM >= MOTION_SNAP_MAX_DEVIATION_M) {
            snapPath = null
            return
        }
        if (snapPoint != null && distanceM(snapPoint, path.pointAt(alongM)) > MOTION_SNAP_CONSISTENCY_M) {
            snapPath = null
            return
        }
        snapPath = path
        snapAlong = alongM
        snapStamp = nowNanos
    }

    fun reset() {
        lastFix = null
        heldBearing = null
        snapPath = null
        drawn = null
        drawnBearing = null
        zoom = null
        lastFrameNanos = null
        hysteresis.reset()
    }

    /** Rumo mantido (último válido), para a seta do marcador quando o fix atual não traz rumo. */
    val lastBearing: Double? get() = heldBearing ?: drawnBearing

    fun frame(nowNanos: Long, adaptiveZoom: Boolean, fixedZoom: Double): MotionFrame? {
        val fix = lastFix ?: return null
        val prev = lastFrameNanos
        val dt = if (prev == null) 0.0 else ((nowNanos - prev) / 1e9).coerceIn(0.0, MOTION_MAX_DT_S)
        lastFrameNanos = nowNanos

        val moving = hysteresis.moving
        val speed = if (moving) (fix.speedMps ?: 0f).toDouble() else 0.0

        // Alvo de posição e de rumo.
        val path = snapPath
        val target: LatLon
        var targetBearing: Double? = heldBearing
        if (path != null) {
            val ageS = ((nowNanos - snapStamp) / 1e9).coerceIn(0.0, MOTION_PREDICT_MAX_S)
            val ahead = if (speed >= MOTION_PREDICT_MIN_SPEED_MPS) min(speed, MOTION_MAX_PLAUSIBLE_SPEED_MPS) * ageS else 0.0
            val along = snapAlong + ahead
            target = path.pointAt(along)
            // Rumo da rota; parado, segura o último (a não ser que ainda não haja nenhum).
            if (moving || targetBearing == null) targetBearing = path.bearingAt(along)
        } else {
            target = predictPosition(if (moving) fix else fix.copy(speedMps = 0f), lastFixStamp, nowNanos)
        }

        // Posição: alcance exponencial; salta se estiver longe demais; ignora deriva parado.
        val cur = drawn
        val pos = when {
            cur == null -> target
            distanceM(cur, target) > MOTION_JUMP_DISTANCE_M -> target
            !moving && distanceM(cur, target) < MOTION_STILL_DEADBAND_M -> cur
            else -> {
                val a = 1.0 - exp(-dt / MOTION_POSITION_TAU_S)
                LatLon(cur.lat + (target.lat - cur.lat) * a, cur.lon + (target.lon - cur.lon) * a)
            }
        }
        drawn = pos

        // Rumo: passa-baixa circular em direção ao alvo; sem alvo, mantém o desenhado.
        val curB = drawnBearing
        val b = when {
            targetBearing == null -> curB
            curB == null -> targetBearing
            else -> lowPassBearing(curB, targetBearing, dt)
        }
        drawnBearing = b

        // Zoom adaptativo (só na navegação); nos demais modos, o fixo.
        val z = if (!adaptiveZoom) fixedZoom else {
            val zTarget = zoomForSpeed(speed)
            val curZ = zoom
            if (curZ == null) zTarget else stepZoom(curZ, zTarget, dt)
        }
        zoom = z

        return MotionFrame(
            lat = pos.lat,
            lon = pos.lon,
            bearingDeg = b,
            speedMps = speed,
            moving = moving,
            zoom = z,
            aheadFraction = if (adaptiveZoom) aheadFractionForSpeed(speed) else 0.0,
            snapped = path != null,
        )
    }
}

/** Distância angular absoluta entre dois rumos (0..180). */
fun angleDistance(a: Double, b: Double): Double = abs(shortestAngleDelta(a, b))
