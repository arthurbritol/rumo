package app.hunter.model

import kotlin.math.max

/**
 * Acumula estatísticas de um trajeto ponto a ponto, de forma incremental (o serviço atualiza a UI e o
 * banco sem reprocessar tudo). Pura e determinística: reconstruir a partir dos pontos salvos
 * ([fromPoints]) dá o mesmo resultado, o que permite retomar após o processo ser morto.
 */
class TripStatsAccumulator(private val config: TrackingConfig = TrackingConfig()) {

    private var firstTimeMs = 0L
    private var last: TrackPoint? = null

    private var distanceM = 0.0
    private var movingMs = 0L
    private var maxSpeedMps = 0.0

    private var stationarySinceMs: Long? = null
    private var stops = 0
    private var stoppedMs = 0L

    private var altRef: Double? = null
    private var gainM = 0.0
    private var lossM = 0.0

    /** Pontos aceitos até agora. */
    var acceptedCount = 0
        private set

    /** @return true se o ponto foi aceito (passou nos filtros de precisão, tempo e plausibilidade). */
    fun add(p: TrackPoint): Boolean {
        val acc = p.accuracyM
        if (acc != null && acc > config.maxAccuracyM) return false

        val prev = last
        if (prev == null) {
            firstTimeMs = p.timeMs
            last = p
            acceptedCount = 1
            updateElevation(p)
            return true
        }

        val dtMs = p.timeMs - prev.timeMs
        if (dtMs <= 0) return false
        if (dtMs > config.maxGapMs) {
            // Buraco nos dados: encerra qualquer parada até o último ponto conhecido e recomeça daqui.
            closeStop(prev.timeMs)
            last = p
            acceptedCount++
            updateElevation(p)
            return true
        }
        val d = Geo.distanceM(prev, p)
        val derivedSpeed = d / (dtMs / 1000.0)
        if (derivedSpeed > config.maxPlausibleSpeedMps) return false

        val speed = p.speedMps?.toDouble() ?: derivedSpeed
        if (speed >= config.stopSpeedMps) {
            distanceM += d
            movingMs += dtMs
            maxSpeedMps = max(maxSpeedMps, speed)
            updateElevation(p)
            closeStop(prev.timeMs)
        } else if (stationarySinceMs == null) {
            stationarySinceMs = prev.timeMs
        }

        last = p
        acceptedCount++
        return true
    }

    /** Fecha o período parado que termina em [endMs] (último ponto parado), se durou o bastante. */
    private fun closeStop(endMs: Long) {
        val since = stationarySinceMs ?: return
        val dur = endMs - since
        if (dur >= config.minStopMs) {
            stops++
            stoppedMs += dur
        }
        stationarySinceMs = null
    }

    private fun updateElevation(p: TrackPoint) {
        val alt = p.altitudeM ?: return
        val ref = altRef
        when {
            ref == null -> altRef = alt
            alt - ref >= config.elevationThresholdM -> { gainM += alt - ref; altRef = alt }
            ref - alt >= config.elevationThresholdM -> { lossM += ref - alt; altRef = alt }
        }
    }

    /** Estatísticas atuais; uma parada em andamento que já passou do mínimo entra na conta. */
    fun snapshot(): TripStats {
        val l = last ?: return TripStats()
        var stopCount = stops
        var stopped = stoppedMs
        stationarySinceMs?.let { since ->
            val ongoing = l.timeMs - since
            if (ongoing >= config.minStopMs) {
                stopCount++
                stopped += ongoing
            }
        }
        return TripStats(
            distanceM = distanceM,
            elapsedMs = l.timeMs - firstTimeMs,
            movingTimeMs = movingMs,
            stoppedTimeMs = stopped,
            stopCount = stopCount,
            maxSpeedMps = maxSpeedMps,
            elevationGainM = gainM,
            elevationLossM = lossM,
        )
    }

    companion object {
        /** Reconstrói o acumulador a partir de pontos já salvos. */
        fun fromPoints(points: List<TrackPoint>, config: TrackingConfig = TrackingConfig()): TripStatsAccumulator =
            TripStatsAccumulator(config).also { acc -> points.forEach { acc.add(it) } }
    }
}
