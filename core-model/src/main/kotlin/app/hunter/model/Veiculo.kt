package app.hunter.model

/** Tipo do veículo: define rota, manutenção padrão e textos (as telas por tipo vêm em tarefas seguintes). */
enum class TipoVeiculo {
    MOTO,
    CARRO,
    ;

    companion object {
        /** Lê o nome gravado (banco/nuvem); valor desconhecido vira [MOTO], o comportamento histórico do app. */
        fun deNome(nome: String?): TipoVeiculo = entries.firstOrNull { it.name == nome } ?: MOTO
    }
}

/** Veículo da conta. [removidoEm] não nulo = removido logicamente (some das listas, os dados ficam). */
data class Veiculo(
    val id: String,
    val tipo: TipoVeiculo,
    val apelido: String,
    val marca: String,
    val modelo: String,
    val ano: Int?,
    val tanqueLitros: Double,
    val consumoPadraoKmL: Double?,
    val criadoEm: Long,
    val removidoEm: Long? = null,
) {
    val removido: Boolean get() = removidoEm != null
}

/**
 * Veículo ativo: o preferido ([preferidoId]) se existe e não foi removido; senão o primeiro não removido
 * (mais antigo por [Veiculo.criadoEm], depois id); sem veículos, nulo. [lista] pode trazer removidos.
 */
fun resolverVeiculoAtivo(lista: List<Veiculo>, preferidoId: String?): Veiculo? {
    val vivos = lista.filter { !it.removido }
    return vivos.firstOrNull { it.id == preferidoId }
        ?: vivos.minWithOrNull(compareBy<Veiculo>({ it.criadoEm }, { it.id }))
}
