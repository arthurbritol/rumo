package app.hunter.app.ui

enum class Orientacao { Retrato, Paisagem }

/** O que importa para decidir o que a tela do mapa mostra. */
data class EstadoDaTela(
    val navegando: Boolean,
    val encontroAtivo: Boolean = false,
    /** A câmera está seguindo a moto (false: o usuário mexeu no mapa). */
    val seguindo: Boolean = true,
    /** Gravando uma viagem (mesmo sem rota): os avisos de radar e lombada também valem. */
    val gravando: Boolean = false,
)

/** Quais blocos da tela do mapa aparecem (todos são lidos pela tela). Função pura, testável em JVM. */
data class ElementosDaTela(
    /** Tela de sempre: busca, faixas, cartões e painel inferior. */
    val ocioso: Boolean,
    /** Botões de postos, trânsito e rolê. */
    val camadas: Boolean,
    val abas: Boolean,
    val bannerManobra: Boolean,
    val barraInferior: Boolean,
    val painelLateral: Boolean,
    val pilulaEncontro: Boolean,
    val recentralizar: Boolean,
    /** Cartão do próximo radar/lombada: faz parte do essencial, na navegação e na gravação. */
    val alertaPerigo: Boolean,
)

/**
 * Modo de navegação enxuto: com o GPS ativo ficam só manobra, velocidade, ETA, encerrar e recentralizar
 * (mais a pílula do encontro, se houver e o aviso do próximo radar/lombada, que também vale gravando). Em retrato, banner no topo + barra fina embaixo; em paisagem,
 * um painel lateral estreito. Fora da navegação nada muda.
 */
fun navegacaoEnxuta(estado: EstadoDaTela, orientacao: Orientacao): ElementosDaTela {
    val nav = estado.navegando
    val paisagem = orientacao == Orientacao.Paisagem
    return ElementosDaTela(
        ocioso = !nav, camadas = !nav, abas = !nav,
        bannerManobra = nav && !paisagem, barraInferior = nav && !paisagem, painelLateral = nav && paisagem,
        pilulaEncontro = nav && estado.encontroAtivo,
        recentralizar = !estado.seguindo,
        alertaPerigo = nav || estado.gravando,
    )
}
