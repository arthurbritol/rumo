package app.hunter.pod

import kotlin.math.abs

/**
 * Histerese da bússola: só troca de setor quando o rumo passa [hysteresisDeg] além da borda do setor
 * atual, para a seta não ficar piscando entre dois setores (o rumo do GPS oscila). Ideia do WazeTripper.
 */
class CompassFilter(private val hysteresisDeg: Float = 12f) {
    var current: CompassDirection? = null
        private set

    fun update(bearingDeg: Float): CompassDirection {
        val cur = current
        val next = if (cur == null) {
            CompassDirection.fromBearing(bearingDeg)
        } else {
            val center = cur.ordinal * 45f
            if (angularDiff(bearingDeg, center) <= 22.5f + hysteresisDeg) cur else CompassDirection.fromBearing(bearingDeg)
        }
        current = next
        return next
    }

    private fun angularDiff(a: Float, b: Float): Float {
        val d = abs(((a - b) % 360f + 360f) % 360f)
        return if (d > 180f) 360f - d else d
    }
}
