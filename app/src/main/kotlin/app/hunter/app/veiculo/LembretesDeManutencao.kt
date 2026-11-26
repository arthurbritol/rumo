package app.hunter.app.veiculo

import app.hunter.model.DueState
import app.hunter.model.MaintenanceItem
import app.hunter.model.MaintenanceReminders
import app.hunter.model.Veiculo

/** Itens de manutenção de um veículo e o hodômetro dele (km atual), para o aviso de manutenção vencida. */
data class ManutencaoDoVeiculo(val veiculo: Veiculo, val itens: List<MaintenanceItem>, val km: Double)

data class LembreteDevido(val veiculo: Veiculo, val due: MaintenanceReminders.Due)

/**
 * Lembretes de manutenção de TODOS os veículos não removidos (não só o ativo). O registro do que já foi avisado
 * é por item (ids de item são únicos entre veículos). Kotlin puro.
 */
object LembretesDeManutencao {
    /** Vencidos primeiro. Cada veículo usa o km dele. */
    fun devidos(porVeiculo: List<ManutencaoDoVeiculo>, nowMs: Long, notified: Map<Long, DueState>): List<LembreteDevido> =
        porVeiculo.flatMap { m -> MaintenanceReminders.due(m.itens, m.km, nowMs, notified).map { LembreteDevido(m.veiculo, it) } }
            .sortedBy { if (it.due.status.state == DueState.OVERDUE) 0 else 1 }

    /** Registro a guardar depois do aviso, somando os veículos. */
    fun registro(porVeiculo: List<ManutencaoDoVeiculo>, nowMs: Long): Map<Long, DueState> =
        porVeiculo.fold(emptyMap()) { acc, m -> acc + MaintenanceReminders.record(m.itens, m.km, nowMs) }

    /** Com mais de um veículo, a linha leva o nome dele. */
    fun linha(d: LembreteDevido, qtdVeiculos: Int): String =
        (if (qtdVeiculos > 1) "${d.veiculo.nomeExibicao()}: " else "") + MaintenanceReminders.describe(d.due)

    fun titulo(devidos: List<LembreteDevido>, qtdVeiculos: Int): String =
        if (qtdVeiculos > 1) {
            if (devidos.size == 1) "Manutenção de ${devidos.first().veiculo.nomeExibicao()}" else "Manutenção dos seus veículos (${devidos.size} itens)"
        } else {
            TextosDoVeiculo.de(devidos.firstOrNull()?.veiculo?.tipo).tituloDoLembrete(devidos.size)
        }
}
