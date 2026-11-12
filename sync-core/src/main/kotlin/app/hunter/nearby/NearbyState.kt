package app.hunter.nearby

/**
 * Estado imutável dos vizinhos. Cada quadrado ([celula]) manda a lista inteira dele; o estado guarda
 * a última lista de cada quadrado e junta tudo, de modo que quem aparece em dois quadrados (perto da
 * divisa) vale uma vez só, com a posição do quadrado que falou por último.
 */
class NearbyState private constructor(
    private val porCelula: Map<String, List<Vizinho>>,
    val ultimoErro: String?,
) {
    constructor() : this(emptyMap(), null)

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

    companion object {
        const val CELULA_PADRAO = ""

        fun reduce(state: NearbyState, msg: ServerMessage, celula: String = CELULA_PADRAO): NearbyState =
            when (msg) {
                is ServerMessage.Vizinhos -> {
                    // Reinserir leva este quadrado ao fim: ele é o mais recente.
                    val mapa = LinkedHashMap(state.porCelula)
                    mapa.remove(celula)
                    mapa[celula] = msg.lista
                    NearbyState(mapa, state.ultimoErro)
                }
                is ServerMessage.Erro -> NearbyState(state.porCelula, msg.codigo)
            }
    }
}
