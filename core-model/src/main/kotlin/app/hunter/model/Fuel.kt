package app.hunter.model

data class Fuelup(
    val id: Long,
    val dateMs: Long,
    /** Hodômetro da moto no abastecimento (km). */
    val odometerKm: Double,
    val liters: Double,
    val totalCost: Double,
    /** Tanque cheio: necessário para fechar um trecho de consumo. */
    val fullTank: Boolean,
) {
    val pricePerLiter: Double get() = if (liters > 0) totalCost / liters else 0.0
}

/** Trecho entre dois tanques cheios consecutivos. */
data class FuelSegment(val fromKm: Double, val toKm: Double, val liters: Double, val cost: Double) {
    val km: Double get() = toKm - fromKm
    val kmPerL: Double get() = km / liters
    val costPerKm: Double get() = cost / km
}

data class FuelSummary(
    val segments: List<FuelSegment>,
    /** Consumo médio ponderado (km totais / litros totais) nos trechos fechados; null sem trechos. */
    val avgKmPerL: Double?,
    val avgCostPerKm: Double?,
    /** Consumo do trecho mais recente. */
    val lastKmPerL: Double?,
    val totalCost: Double,
    val totalLiters: Double,
)

object FuelMath {
    /**
     * Método tanque cheio a tanque cheio: o consumo de um trecho é km rodados entre dois abastecimentos
     * cheios dividido pelos litros colocados *depois* do primeiro cheio até o segundo (inclusive),
     * contando parciais no meio. Abastecimentos parciais antes do primeiro cheio não entram.
     */
    fun summarize(fuelups: List<Fuelup>): FuelSummary {
        val sorted = fuelups.sortedWith(compareBy({ it.odometerKm }, { it.dateMs }))
        val segments = mutableListOf<FuelSegment>()
        var prevFull: Fuelup? = null
        var liters = 0.0
        var cost = 0.0
        for (f in sorted) {
            val base = prevFull
            if (base == null) {
                if (f.fullTank) prevFull = f
                continue
            }
            liters += f.liters
            cost += f.totalCost
            if (f.fullTank) {
                if (f.odometerKm > base.odometerKm && liters > 0) {
                    segments += FuelSegment(base.odometerKm, f.odometerKm, liters, cost)
                }
                prevFull = f
                liters = 0.0
                cost = 0.0
            }
        }
        val km = segments.sumOf { it.km }
        val l = segments.sumOf { it.liters }
        val c = segments.sumOf { it.cost }
        return FuelSummary(
            segments = segments,
            avgKmPerL = if (l > 0) km / l else null,
            avgCostPerKm = if (km > 0) c / km else null,
            lastKmPerL = segments.lastOrNull()?.kmPerL,
            totalCost = fuelups.sumOf { it.totalCost },
            totalLiters = fuelups.sumOf { it.liters },
        )
    }

    /** Autonomia estimada com o consumo médio e o tamanho do tanque (L). */
    fun rangeKm(avgKmPerL: Double?, tankLiters: Double): Double? = avgKmPerL?.let { it * tankLiters }
}
