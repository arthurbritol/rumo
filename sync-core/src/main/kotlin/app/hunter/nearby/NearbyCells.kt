package app.hunter.nearby

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Mesma lógica de `workers/nearby/src/geo.js`: quadrados de 1° x 1°, e o círculo (centro, raio) toca de 1 a 4.

private const val M_POR_GRAU = 111320.0 // metros por grau de latitude

/** Id do quadrado de 1° x 1° que contém o ponto, ex.: Vitória -> "-21:-41". */
fun cellId(lat: Double, lon: Double): String = "${floor(lat).toInt()}:${floor(lon).toInt()}"

/** Ids dos quadrados que o círculo (centro, raio em km) toca, sem repetir. */
fun cellsInRadius(lat: Double, lon: Double, radiusKm: Double): Set<String> {
    val rM = radiusKm * 1000
    val cosLat = cos(lat * PI / 180)
    val dLat = rM / M_POR_GRAU
    val dLon = rM / (M_POR_GRAU * cosLat)
    val ids = LinkedHashSet<String>()
    for (la in floor(lat - dLat).toInt()..floor(lat + dLat).toInt()) {
        for (lo in floor(lon - dLon).toInt()..floor(lon + dLon).toInt()) {
            // Ponto do quadrado mais próximo do centro (aproximação plana em metros).
            val nLat = min(max(lat, la.toDouble()), la + 1.0)
            val nLon = min(max(lon, lo.toDouble()), lo + 1.0)
            val dy = (nLat - lat) * M_POR_GRAU
            val dx = (nLon - lon) * M_POR_GRAU * cosLat
            if (sqrt(dx * dx + dy * dy) <= rM) ids += "$la:$lo"
        }
    }
    return ids
}
