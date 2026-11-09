package app.hunter.nav

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Onde o motociclista está na rota. */
data class NavProgress(
    /** Distância percorrida ao longo da rota (m). */
    val alongM: Double,
    val remainingM: Double,
    val remainingS: Double,
    /** Índice, em [Route.maneuvers], da próxima manobra a executar. */
    val upcomingIndex: Int,
    val upcoming: Maneuver,
    val distanceToUpcomingM: Double,
    /** A manobra seguinte à próxima (seta pequena no Pod), se houver. */
    val then: Maneuver?,
    val deviationM: Double,
    val offRoute: Boolean,
    val arrived: Boolean,
)

/**
 * Projeta a posição do GPS no traçado e calcula distância/tempo restantes e a próxima manobra.
 * Considera fora da rota após [offRouteCount] leituras seguidas a mais de [offRouteM] do traçado.
 * Não é thread-safe (usar de uma coroutine/thread só).
 */
class NavEngine(
    val route: Route,
    private val offRouteM: Double = 45.0,
    private val offRouteCount: Int = 3,
    private val arrivalM: Double = 25.0,
) {
    private val shape = route.shape
    private val cum = route.cumulativeM
    private var lastSeg = 0
    private var offStreak = 0

    init {
        require(shape.size >= 2) { "rota sem geometria" }
        require(route.maneuvers.isNotEmpty()) { "rota sem manobras" }
    }

    fun update(p: LatLon, accuracyM: Float? = null): NavProgress {
        val (seg, t, dev) = project(p)
        lastSeg = seg
        val segLen = cum[seg + 1] - cum[seg]
        val along = cum[seg] + t * segLen
        val total = cum.last()

        val limit = max(offRouteM, (accuracyM ?: 0f) * 2.0)
        offStreak = if (dev > limit) offStreak + 1 else 0

        val ms = route.maneuvers
        var idx = ms.size - 1
        for (i in 1 until ms.size) {
            if (cum[ms[i].beginShapeIndex.coerceIn(0, shape.lastIndex)] > along + 1.0) { idx = i; break }
        }
        val upcoming = ms[idx]
        val upcomingAt = cum[upcoming.beginShapeIndex.coerceIn(0, shape.lastIndex)]
        val remaining = max(0.0, total - along)

        return NavProgress(
            alongM = along,
            remainingM = remaining,
            remainingS = remainingSeconds(along),
            upcomingIndex = idx,
            upcoming = upcoming,
            distanceToUpcomingM = max(0.0, upcomingAt - along),
            then = ms.getOrNull(idx + 1),
            deviationM = dev,
            offRoute = offStreak >= offRouteCount,
            arrived = upcoming.isDestination && remaining <= arrivalM,
        )
    }

    private fun remainingSeconds(along: Double): Double {
        val ms = route.maneuvers
        var s = 0.0
        for (k in 0 until ms.size - 1) {
            val start = cum[ms[k].beginShapeIndex.coerceIn(0, shape.lastIndex)]
            val end = cum[ms[k + 1].beginShapeIndex.coerceIn(0, shape.lastIndex)]
            if (along >= end) continue
            val len = end - start
            val frac = if (len <= 0.0) 1.0 else (end - max(along, start)) / len
            s += ms[k].timeS * frac
        }
        return s
    }

    /** @return (segmento, fração 0..1 no segmento, desvio em metros). Busca a partir do último segmento. */
    private fun project(p: LatLon): Triple<Int, Double, Double> {
        val from = max(0, lastSeg - 3)
        val to = min(shape.size - 2, lastSeg + 400)
        var best = scan(p, from, to)
        // Longe do que se esperava (ex.: voltou a um trecho anterior, ou o último fix foi ruim): varre tudo.
        if (best.third > 200.0 && (from > 0 || to < shape.size - 2)) {
            val all = scan(p, 0, shape.size - 2)
            if (all.third < best.third) best = all
        }
        return best
    }

    private fun scan(p: LatLon, from: Int, to: Int): Triple<Int, Double, Double> {
        var bestSeg = from
        var bestT = 0.0
        var bestDev = Double.MAX_VALUE
        val kx = cos(Math.toRadians(p.lat))
        for (i in from..to) {
            val a = shape[i]
            val b = shape[i + 1]
            val ax = (a.lon - p.lon) * kx * M_PER_DEG
            val ay = (a.lat - p.lat) * M_PER_DEG
            val bx = (b.lon - p.lon) * kx * M_PER_DEG
            val by = (b.lat - p.lat) * M_PER_DEG
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 1e-9) 0.0 else ((-ax * dx) + (-ay * dy)).div(len2).coerceIn(0.0, 1.0)
            val px = ax + t * dx
            val py = ay + t * dy
            val d = sqrt(px * px + py * py)
            if (d < bestDev) { bestDev = d; bestSeg = i; bestT = t }
        }
        return Triple(bestSeg, bestT, bestDev)
    }

    private companion object {
        const val M_PER_DEG = 111_194.9
    }
}
