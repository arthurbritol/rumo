package app.hunter.model

/** Estatísticas de um trajeto (parciais enquanto grava, finais ao terminar). */
data class TripStats(
    val distanceM: Double = 0.0,
    /** Tempo total desde o primeiro ponto aceito até o último. */
    val elapsedMs: Long = 0,
    /** Tempo em movimento (velocidade acima do limiar de parada). */
    val movingTimeMs: Long = 0,
    /** Tempo somado das paradas (≥ duração mínima de parada). */
    val stoppedTimeMs: Long = 0,
    val stopCount: Int = 0,
    val maxSpeedMps: Double = 0.0,
    val elevationGainM: Double = 0.0,
    val elevationLossM: Double = 0.0,
) {
    /** Velocidade média em movimento (m/s); 0 se ainda não houve movimento. */
    val avgSpeedMps: Double get() = if (movingTimeMs > 0) distanceM / (movingTimeMs / 1000.0) else 0.0
    val avgSpeedKmh: Double get() = avgSpeedMps * 3.6
    val maxSpeedKmh: Double get() = maxSpeedMps * 3.6
    val distanceKm: Double get() = distanceM / 1000.0
}

/** Resumo de um trajeto para listagem/histórico. */
data class TripSummary(
    val id: Long,
    val startedAtMs: Long,
    /** null enquanto o trajeto ainda está sendo gravado. */
    val endedAtMs: Long?,
    val stats: TripStats,
) {
    val isActive: Boolean get() = endedAtMs == null
}

/** Parâmetros de filtragem e detecção; os padrões são pensados para moto (GPS do celular no suporte). */
data class TrackingConfig(
    /** Descarta pontos com precisão pior que isso (m). */
    val maxAccuracyM: Float = 35f,
    /** Descarta saltos implausíveis (m/s). 80 m/s = 288 km/h. */
    val maxPlausibleSpeedMps: Double = 80.0,
    /** Abaixo disso o ponto conta como parado (m/s); 1 m/s = 3,6 km/h. Evita somar deriva do GPS. */
    val stopSpeedMps: Double = 1.0,
    /** Parada só conta se durar pelo menos isso (semáforo curto não é parada). */
    val minStopMs: Long = 60_000,
    /**
     * Intervalo máximo entre pontos para ligá-los. Acima disso (serviço morto e retomado, GPS perdido por
     * muito tempo) o ponto vira nova referência: não soma distância nem tempo em movimento do buraco.
     */
    val maxGapMs: Long = 60_000,
    /** Variação mínima de altitude para entrar no ganho/perda (histerese contra ruído do GPS). */
    val elevationThresholdM: Double = 3.0,
)
