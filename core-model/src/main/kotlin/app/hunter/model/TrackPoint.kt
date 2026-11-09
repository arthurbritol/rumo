package app.hunter.model

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Uma amostra de GPS. Campos opcionais são nulos quando o aparelho não os fornece. */
data class TrackPoint(
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double? = null,
    val speedMps: Float? = null,
    val accuracyM: Float? = null,
    val bearingDeg: Float? = null,
)

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Distância de grande círculo (haversine), em metros. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    fun distanceM(a: TrackPoint, b: TrackPoint): Double = distanceM(a.latitude, a.longitude, b.latitude, b.longitude)
}
