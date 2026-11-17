package app.hunter.nearby

/**
 * Estado imutável do Rolê por perto. Cada quadrado ([celula]) manda a lista inteira dele (vizinhos e propostas); o
 * estado guarda a última lista de cada quadrado e junta tudo, de modo que quem aparece em dois quadrados (perto da
 * divisa) vale uma vez só, com o dado do quadrado que falou por último. O encontro é um só, identificado pelo id.
 */
class NearbyState private constructor(
    private val porCelula: Map<String, List<Vizinho>>,
    private val propostasPorCelula: Map<String, List<Proposta>>,
    /** O encontro atual, com a última lista de participantes (nulo fora de um encontro). */
    val encontro: ServerMessage.Encontro?,
    /** Como o último encontro acabou (nulo enquanto há um encontro ou depois de [limparFim]). */
    val fim: ServerMessage.Fim?,
    val ultimoErro: String?,
    /** Do último `erro` com espera (`espera`): quanto falta, em ms. */
    val ultimaEsperaMs: Long?,
) {
    constructor() : this(emptyMap(), emptyMap(), null, null, null, null)

    private fun copiar(
        porCelula: Map<String, List<Vizinho>> = this.porCelula,
        propostasPorCelula: Map<String, List<Proposta>> = this.propostasPorCelula,
        encontro: ServerMessage.Encontro? = this.encontro,
        fim: ServerMessage.Fim? = this.fim,
        ultimoErro: String? = this.ultimoErro,
        ultimaEsperaMs: Long? = this.ultimaEsperaMs,
    ) = NearbyState(porCelula, propostasPorCelula, encontro, fim, ultimoErro, ultimaEsperaMs)

    /** Quem combina primeiro; dentro de cada grupo, a ordem de chegada. Sem repetir `sessao`. */
    val vizinhos: List<Vizinho> by lazy {
        val porSessao = LinkedHashMap<String, Vizinho>()
        for (lista in porCelula.values) for (v in lista) {
            porSessao.remove(v.sessao)
            porSessao[v.sessao] = v
        }
        val todos = porSessao.values.toList()
        todos.filter { it.combina } + todos.filterNot { it.combina }
    }

    /** Propostas abertas de todos os quadrados, sem repetir `id` (o quadrado mais recente vence); as que combinam primeiro. */
    val propostas: List<Proposta> by lazy {
        val porId = LinkedHashMap<String, Proposta>()
        for (lista in propostasPorCelula.values) for (p in lista) {
            porId.remove(p.id)
            porId[p.id] = p
        }
        val todas = porId.values.toList()
        todas.filter { it.combina } + todas.filterNot { it.combina }
    }

    /** O quadrado cuja lista (a mais recente que traz a pessoa) vale para [sessao]; nulo se ela não está na lista. */
    fun celulaDoVizinho(sessao: String): String? =
        porCelula.entries.lastOrNull { (_, lista) -> lista.any { it.sessao == sessao } }?.key

    /** Descarta vizinhos e propostas (quadrados novos), mas guarda o encontro, o fim e o erro. */
    fun semListas(): NearbyState = copiar(porCelula = emptyMap(), propostasPorCelula = emptyMap())

    /** Dá o encontro por acabado localmente (saiu, cancelou) com o [motivo]. */
    fun encerrar(id: String, motivo: MotivoFim): NearbyState =
        copiar(encontro = null, fim = ServerMessage.Fim(id, motivo))

    fun limparFim(): NearbyState = copiar(fim = null)

    companion object {
        const val CELULA_PADRAO = ""

        fun reduce(state: NearbyState, msg: ServerMessage, celula: String = CELULA_PADRAO): NearbyState =
            when (msg) {
                is ServerMessage.Vizinhos -> {
                    // Reinserir leva este quadrado ao fim: ele é o mais recente.
                    val mapa = LinkedHashMap(state.porCelula)
                    mapa.remove(celula)
                    mapa[celula] = msg.lista
                    state.copiar(porCelula = mapa)
                }
                is ServerMessage.Propostas -> {
                    val mapa = LinkedHashMap(state.propostasPorCelula)
                    mapa.remove(celula)
                    mapa[celula] = msg.lista
                    state.copiar(propostasPorCelula = mapa)
                }
                // Um encontro novo (ou a lista nova de participantes do mesmo) substitui o anterior.
                is ServerMessage.Encontro -> state.copiar(encontro = msg, fim = null)
                is ServerMessage.Fim -> {
                    val atual = state.encontro
                    // Fim de outro encontro (atrasado) não derruba o atual.
                    if (atual != null && atual.id != msg.id) state else state.copiar(encontro = null, fim = msg)
                }
                is ServerMessage.Ok -> state
                is ServerMessage.Erro -> state.copiar(ultimoErro = msg.codigo, ultimaEsperaMs = msg.restanteMs)
            }
    }
}
