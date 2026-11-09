package app.hunter.nav

import app.hunter.model.Geo

data class LatLon(val lat: Double, val lon: Double)

/** Lugar escolhido como destino (resultado de busca, ponto no mapa ou salvo). */
data class Place(val name: String, val subtitle: String?, val lat: Double, val lon: Double) {
    val latLon get() = LatLon(lat, lon)
}

/** Perfis de rota para moto (Valhalla `motorcycle`). */
enum class RouteProfile(val label: String, val hint: String) {
    FASTEST("Mais rápida", "Usa rodovias"),
    CURVES("Estradas e curvas", "Evita rodovias"),
    NO_TOLLS("Sem pedágio", "Evita praças de pedágio"),
    NO_DIRT("Sem terra", "Evita trechos não pavimentados"),
}

/**
 * Uma manobra do Valhalla. [type] segue os códigos do Valhalla (1 = partida, 4–6 = destino, 10 = direita…).
 * A manobra descreve a ação feita no início do trecho que começa em [beginShapeIndex].
 */
data class Maneuver(
    val type: Int,
    val instruction: String,
    /** Texto para voz, dito antes da manobra. */
    val verbalPre: String?,
    val streetName: String?,
    val lengthM: Double,
    val timeS: Double,
    val beginShapeIndex: Int,
    val endShapeIndex: Int,
    val roundaboutExit: Int?,
) {
    val isDestination: Boolean get() = type in 4..6
}

class Route(
    val shape: List<LatLon>,
    val maneuvers: List<Maneuver>,
    val distanceM: Double,
    val durationS: Double,
    val hasToll: Boolean,
    val hasHighway: Boolean,
    val profile: RouteProfile,
) {
    /** Distância acumulada (m) ao longo do traçado até cada ponto de [shape]. */
    val cumulativeM: DoubleArray by lazy {
        val c = DoubleArray(shape.size)
        for (i in 1 until shape.size) {
            c[i] = c[i - 1] + Geo.distanceM(shape[i - 1].lat, shape[i - 1].lon, shape[i].lat, shape[i].lon)
        }
        c
    }

    /** Distância ao longo da rota (m) do vértice do traçado mais próximo de [p]. */
    fun alongOf(p: LatLon): Double {
        var best = 0
        var bestD = Double.MAX_VALUE
        for (i in shape.indices) {
            val d = Geo.distanceM(p.lat, p.lon, shape[i].lat, shape[i].lon)
            if (d < bestD) { bestD = d; best = i }
        }
        return cumulativeM[best]
    }

    /** Ponto do traçado a [distanceM] metros do início (limitado ao começo/fim). */
    fun pointAt(distanceM: Double): LatLon {
        val c = cumulativeM
        if (distanceM <= 0.0 || c.size < 2) return shape.first()
        if (distanceM >= c.last()) return shape.last()
        var lo = 0
        var hi = c.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (c[mid] <= distanceM) lo = mid else hi = mid
        }
        val span = c[hi] - c[lo]
        val t = if (span <= 0.0) 0.0 else (distanceM - c[lo]) / span
        return LatLon(shape[lo].lat + (shape[hi].lat - shape[lo].lat) * t, shape[lo].lon + (shape[hi].lon - shape[lo].lon) * t)
    }

    /** Comprimento do traçado calculado a partir da geometria (mais confiável que o resumo arredondado). */
    val lengthM: Double get() = cumulativeM.lastOrNull() ?: 0.0
}
