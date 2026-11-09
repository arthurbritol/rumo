package app.hunter.model

/** Estimativa do combustível que ainda há no tanque. */
data class FuelState(
    val litersLeft: Double,
    val rangeKm: Double,
    /** true quando não há histórico de consumo e foi usado o consumo genérico. */
    val usedDefaultConsumption: Boolean,
)

/** Recomendação de abastecimento para uma rota. */
data class FuelAdvice(
    val routeKm: Double,
    val rangeKm: Double,
    val needsStop: Boolean,
    /** Km da rota em que convém procurar um posto (null se não precisa parar). */
    val stopAtKm: Double?,
) {
    /** true quando o tanque estimado nem dá para um trecho curto: abastecer antes de sair. */
    val refuelBeforeLeaving: Boolean get() = needsStop && (stopAtKm ?: 0.0) < 10.0
}

object FuelPlanner {
    /** Consumo genérico para uma 350 cc, usado enquanto não há abastecimentos suficientes. */
    const val DEFAULT_KM_PER_L = 28.0

    /** Folga do tanque: planeja parar antes de usar mais que (1 − RESERVE) da autonomia estimada. */
    const val RESERVE = 0.15

    /**
     * Combustível restante a partir do último tanque cheio: tanque cheio − consumo desde então + litros de
     * abastecimentos parciais posteriores. Sem nenhum tanque cheio registrado não há como estimar (null).
     */
    fun state(tankLiters: Double, avgKmPerL: Double?, odometerKm: Double, fuelups: List<Fuelup>): FuelState? {
        val lastFull = fuelups.filter { it.fullTank }.maxByOrNull { it.odometerKm } ?: return null
        val kpl = avgKmPerL?.takeIf { it > 0 } ?: DEFAULT_KM_PER_L
        val added = fuelups.filter { !it.fullTank && it.odometerKm > lastFull.odometerKm }.sumOf { it.liters }
        val used = (odometerKm - lastFull.odometerKm).coerceAtLeast(0.0) / kpl
        val left = (tankLiters + added - used).coerceIn(0.0, tankLiters)
        return FuelState(left, left * kpl, usedDefaultConsumption = avgKmPerL == null)
    }

    fun advise(routeKm: Double, rangeKm: Double): FuelAdvice {
        val usable = rangeKm * (1 - RESERVE)
        if (routeKm <= usable) return FuelAdvice(routeKm, rangeKm, needsStop = false, stopAtKm = null)
        return FuelAdvice(routeKm, rangeKm, needsStop = true, stopAtKm = (usable * 0.9).coerceAtLeast(0.0))
    }
}
