package app.hunter.app.nearby

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Decide quando mandar a posição ao servidor: a cada [intervalMs] procurando e a cada [floorMs] em encontro;
 * antes disso se andou [distanceM] ou mais, mas nunca abaixo do piso [floorMs].
 */
class PositionThrottle(
    val floorMs: Long = 10_000,
    val intervalMs: Long = 30_000,
    val distanceM: Double = 100.0,
) {
    private var lastMs: Long? = null
    private var lastLat = 0.0
    private var lastLon = 0.0

    @Synchronized
    fun shouldSend(nowMs: Long, lat: Double, lon: Double, inEncounter: Boolean): Boolean {
        val last = lastMs
        if (last != null) {
            val elapsed = nowMs - last
            if (elapsed < floorMs) return false
            val interval = if (inEncounter) floorMs else intervalMs
            if (elapsed < interval && metros(lastLat, lastLon, lat, lon) < distanceM) return false
        }
        lastMs = nowMs
        lastLat = lat
        lastLon = lon
        return true
    }

    private fun metros(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = 6_371_000.0
        val p1 = la1 * PI / 180
        val p2 = la2 * PI / 180
        val dp = p2 - p1
        val dl = (lo2 - lo1) * PI / 180
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}
