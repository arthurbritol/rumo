package app.hunter.model

import java.util.concurrent.TimeUnit

/** Item de manutenção com intervalo por km e/ou por dias. */
data class MaintenanceItem(
    val id: Long,
    val name: String,
    val intervalKm: Int?,
    val intervalDays: Int?,
    val lastDoneKm: Double?,
    val lastDoneAtMs: Long?,
)

enum class DueState { OK, SOON, OVERDUE, UNKNOWN }

data class DueStatus(
    val state: DueState,
    /** Km restantes (negativo = atrasado); null se o item não tem intervalo em km ou nunca foi feito. */
    val remainingKm: Double?,
    val remainingDays: Long?,
)

object MaintenanceMath {
    fun status(
        item: MaintenanceItem,
        currentKm: Double,
        nowMs: Long,
        soonKm: Double = 500.0,
        soonDays: Long = 15,
    ): DueStatus {
        val remKm = if (item.intervalKm != null && item.lastDoneKm != null) {
            item.lastDoneKm + item.intervalKm - currentKm
        } else null
        val remDays = if (item.intervalDays != null && item.lastDoneAtMs != null) {
            item.intervalDays - TimeUnit.MILLISECONDS.toDays(nowMs - item.lastDoneAtMs)
        } else null

        val state = when {
            remKm == null && remDays == null -> DueState.UNKNOWN
            (remKm ?: 1.0) < 0 || (remDays ?: 1L) < 0 -> DueState.OVERDUE
            (remKm ?: Double.MAX_VALUE) <= soonKm || (remDays ?: Long.MAX_VALUE) <= soonDays -> DueState.SOON
            else -> DueState.OK
        }
        return DueStatus(state, remKm, remDays)
    }

    /** Sugestões iniciais genéricas da moto; o usuário deve conferir com o manual da moto. */
    val defaultItems: List<Triple<String, Int?, Int?>> = listOf(
        Triple("Óleo e filtro de óleo", 5000, 180),
        Triple("Lubrificar corrente", 500, null),
        Triple("Filtro de ar", 10000, 365),
        Triple("Fluido de freio", null, 730),
        Triple("Pastilhas de freio", 10000, null),
        Triple("Vela de ignição", 10000, null),
        Triple("Pneus (calibragem e desgaste)", null, 30),
    )

    /** Sugestões iniciais genéricas do carro (nome, km, dias); conferir com o manual do carro. */
    val defaultItemsCarro: List<Triple<String, Int?, Int?>> = listOf(
        Triple("Óleo e filtro de óleo", 10000, 365),
        Triple("Filtro de ar", 15000, 365),
        Triple("Filtro de combustível", 20000, 730),
        Triple("Pneus (rodízio, alinhamento e balanceamento)", 10000, null),
        Triple("Pneus (calibragem)", null, 30),
        Triple("Pastilhas de freio", 30000, null),
        Triple("Discos de freio", 60000, null),
        Triple("Fluido de freio", null, 730),
        Triple("Correia dentada", 60000, 1825),
        Triple("Velas de ignição", 40000, null),
        Triple("Bateria", null, 1460),
        Triple("Revisão geral", 10000, 365),
    )

    /** Itens padrão do tipo de veículo (moto = os de sempre). */
    fun itensPadrao(tipo: TipoVeiculo): List<Triple<String, Int?, Int?>> = when (tipo) {
        TipoVeiculo.MOTO -> defaultItems
        TipoVeiculo.CARRO -> defaultItemsCarro
    }
}
