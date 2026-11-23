package app.hunter.app.ui

enum class Orientacao { Retrato, Paisagem }

/** O que importa para decidir o que a tela do mapa mostra. */
data class EstadoDaTela(
    val navegando: Boolean,
    val encontroAtivo: Boolean = false,
    /** A câmera está seguindo a moto (false: o usuário mexeu no mapa). */
    val seguindo: Boolean = true,
)

/** Quais blocos da tela do mapa aparecem. Função pura, testável em JVM. */
data class ElementosDaTela(
    // Fora da navegação (comportamento de sempre).
    val busca: Boolean,
    val camadas: Boolean,
    val faixasSecundarias: Boolean,
    val cartoes: Boolean,
    val painelOcioso: Boolean,
    val abas: Boolean,
    // Navegação enxuta.
    val bannerManobra: Boolean,
    val barraInferior: Boolean,
    val painelLateral: Boolean,
    val pilulaEncontro: Boolean,
    val recentralizar: Boolean,
)

/**
 * Modo de navegação enxuto: com o GPS ativo ficam só manobra, velocidade, ETA, encerrar e recentralizar
 * (mais a pílula do encontro, se houver). Em retrato, banner no topo + barra fina embaixo; em paisagem,
 * um painel lateral estreito. Fora da navegação nada muda.
 */
fun navegacaoEnxuta(estado: EstadoDaTela, orientacao: Orientacao): ElementosDaTela {
    if (!estado.navegando) {
        return ElementosDaTela(
            busca = true, camadas = true, faixasSecundarias = true, cartoes = true, painelOcioso = true, abas = true,
            bannerManobra = false, barraInferior = false, painelLateral = false, pilulaEncontro = false,
            recentralizar = !estado.seguindo,
        )
    }
    val paisagem = orientacao == Orientacao.Paisagem
    return ElementosDaTela(
        busca = false, camadas = false, faixasSecundarias = false, cartoes = false, painelOcioso = false, abas = false,
        bannerManobra = !paisagem, barraInferior = !paisagem, painelLateral = paisagem,
        pilulaEncontro = estado.encontroAtivo,
        recentralizar = !estado.seguindo,
    )
}
