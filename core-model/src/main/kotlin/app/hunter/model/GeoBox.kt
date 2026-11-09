package app.hunter.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/** Retângulo geográfico (graus). Usado para baixar mapas offline. */
data class GeoBox(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double) {

    /** Número de tiles (pirâmide XYZ) entre [minZoom] e [maxZoom]; serve para estimar o tamanho do download. */
    fun tileCount(minZoom: Int, maxZoom: Int): Long {
        var total = 0L
        for (z in minZoom..maxZoom) {
            val x0 = tileX(minLon, z)
            val x1 = tileX(maxLon, z)
            val y0 = tileY(maxLat, z) // norte = y menor
            val y1 = tileY(minLat, z)
            total += (x1 - x0 + 1L) * (y1 - y0 + 1L)
        }
        return total
    }

    companion object {
        private const val KM_PER_DEG_LAT = 111.195

        /** Quadrado de [radiusKm] ao redor de um ponto. */
        fun around(lat: Double, lon: Double, radiusKm: Double): GeoBox {
            val dLat = radiusKm / KM_PER_DEG_LAT
            val dLon = radiusKm / (KM_PER_DEG_LAT * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
            return GeoBox(lat - dLat, lon - dLon, lat + dLat, lon + dLon)
        }

        /** Menor retângulo que contém todos os pontos (lat, lon), com [padKm] de folga. */
        fun enclosing(points: List<Pair<Double, Double>>, padKm: Double = 5.0): GeoBox {
            require(points.isNotEmpty()) { "sem pontos" }
            val midLat = (points.minOf { it.first } + points.maxOf { it.first }) / 2
            val dLat = padKm / KM_PER_DEG_LAT
            val dLon = padKm / (KM_PER_DEG_LAT * cos(Math.toRadians(midLat)).coerceAtLeast(0.01))
            return GeoBox(
                points.minOf { it.first } - dLat, points.minOf { it.second } - dLon,
                points.maxOf { it.first } + dLat, points.maxOf { it.second } + dLon,
            )
        }

        private fun tileX(lon: Double, z: Int): Long = floor((lon + 180.0) / 360.0 * 2.0.pow(z)).toLong().coerceIn(0, (1L shl z) - 1)

        private fun tileY(lat: Double, z: Int): Long {
            val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
            return floor((1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * 2.0.pow(z)).toLong().coerceIn(0, (1L shl z) - 1)
        }
    }
}
