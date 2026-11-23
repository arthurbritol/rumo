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

// ---------------------------------------------------------------- classes de tela e layout do mapa

/** Tamanho da janela: retrato (celular em pé), paisagem de celular, ou paisagem larga (tablet). */
enum class ClasseDeTela { Retrato, PaisagemCompacta, Larga }

/** Largura mínima para o layout deitado (trilho + coluna + mapa). Abaixo disso (tela dividida, janela flutuante) vale o retrato compacto. */
const val LARGURA_MINIMA_PAISAGEM_DP = 600

/**
 * Largura e altura em dp da janela (a configuração do sistema). Deitado: largura >= altura E largura >= 600 dp; uma janela
 * deitada mais estreita (411x380, 400x360) usa o layout de retrato, que cabe em pouca largura.
 */
fun classeDeTela(widthDp: Int, heightDp: Int): ClasseDeTela = when {
    widthDp < heightDp || widthDp < LARGURA_MINIMA_PAISAGEM_DP -> ClasseDeTela.Retrato
    widthDp >= 900 && heightDp >= 600 -> ClasseDeTela.Larga
    else -> ClasseDeTela.PaisagemCompacta
}

fun ClasseDeTela.orientacao(): Orientacao = if (this == ClasseDeTela.Retrato) Orientacao.Retrato else Orientacao.Paisagem

/** Largura da coluna esquerda do mapa (e do painel da navegação) em paisagem; 0 em retrato. */
fun larguraColunaLateralDp(classe: ClasseDeTela): Int = when (classe) {
    ClasseDeTela.Retrato -> 0
    ClasseDeTela.PaisagemCompacta -> 300
    ClasseDeTela.Larga -> 360
}

/** Largura máxima do conteúdo das telas de rolagem (Viagens, Garagem, Amigos, Pod, Perfil), centralizado. */
fun larguraMaximaDoConteudoDp(classe: ClasseDeTela): Int = if (classe == ClasseDeTela.Larga) 720 else 640

/**
 * Um cartão só pode ter rolagem própria quando a altura que recebe é limitada. Numa coluna rolável (a coluna esquerda do mapa)
 * a altura é infinita e `verticalScroll` aninhado lança IllegalStateException: ali o cartão cresce e a coluna rola.
 */
fun rolagemPropria(pedida: Boolean, alturaLimitada: Boolean): Boolean = pedida && alturaLimitada

/** Quantos atalhos cabem na coluna esquerda sem empurrar o resto para baixo da dobra. */
fun maximoDeAtalhosNaColuna(classe: ClasseDeTela): Int = when (classe) {
    ClasseDeTela.Retrato -> 0
    ClasseDeTela.PaisagemCompacta -> 3
    ClasseDeTela.Larga -> 6
}

private const val BOTAO_DP = 52
private const val ESPACO_BOTOES_DP = 10

/**
 * Altura que sobra para a coluna de botões no retrato: o mapa menos o inset do topo, a busca (56), os atalhos (se houver),
 * o cartão de baixo medido e uma folga. Os botões não podem passar daqui para não cobrir o cartão.
 */
fun alturaLivreParaBotoesDp(alturaDoMapaDp: Int, insetTopoDp: Int, comAtalhos: Boolean, alturaEmbaixoDp: Int): Int =
    alturaDoMapaDp - insetTopoDp - 12 /* margem */ - 56 /* busca */ - (if (comAtalhos) 8 + 48 else 0) - 10 /* espaço */ - alturaEmbaixoDp - 12 /* folga */

/** Alvos (inclusive o "mais") que cabem em [alturaLivreDp]: de 2 a 4. Com 2: um botão e o "mais". */
fun maximoDeBotoes(alturaLivreDp: Int): Int =
    ((alturaLivreDp + ESPACO_BOTOES_DP) / (BOTAO_DP + ESPACO_BOTOES_DP)).coerceIn(2, 4)

/** Altura do mapa no detalhe de uma viagem: cabe na tela deitada sem engolir a rolagem. */
fun alturaDoMapaDoDetalheDp(alturaDaTelaDp: Int): Int = (alturaDaTelaDp * 55 / 100).coerceIn(200, 340)

/** Como as abas (Mapa, Viagens, Garagem, Amigos, Pod) aparecem. */
enum class FormaDasAbas { Nenhuma, BarraInferior, Trilho }

/**
 * As abas somem só no mapa durante a navegação ([abasNoMapa] vem de [navegacaoEnxuta]); nas outras telas sempre aparecem.
 * Em paisagem viram um trilho vertical na borda esquerda, liberando a altura.
 */
fun formaDasAbas(classe: ClasseDeTela, abasNoMapa: Boolean, naAbaDoMapa: Boolean): FormaDasAbas = when {
    naAbaDoMapa && !abasNoMapa -> FormaDasAbas.Nenhuma
    classe == ClasseDeTela.Retrato -> FormaDasAbas.BarraInferior
    else -> FormaDasAbas.Trilho
}

/** Botões flutuantes do mapa, na ordem em que aparecem de cima para baixo. */
enum class BotaoDoMapa { Postos, Transito, Role, Centralizar }

data class BotoesDoMapa(val visiveis: List<BotaoDoMapa>, val noMenuMais: List<BotaoDoMapa>) {
    val temMais: Boolean get() = noMenuMais.isNotEmpty()
}

/** No máximo [maximo] alvos na coluna; passando disso o último vira "mais" e guarda o resto num mini menu. */
fun distribuirBotoes(disponiveis: List<BotaoDoMapa>, maximo: Int = 4): BotoesDoMapa =
    if (disponiveis.size <= maximo) BotoesDoMapa(disponiveis, emptyList())
    else BotoesDoMapa(disponiveis.take(maximo - 1), disponiveis.drop(maximo - 1))

/** O que importa para decidir onde cada bloco da tela do mapa fica. */
data class EstadoDoMapa(
    val classe: ClasseDeTela,
    val tela: EstadoDaTela,
    /** Cartão de moto, proposta ou encontro aberto: em paisagem vai para o topo da coluna e esconde os atalhos. */
    val cartaoSelecionado: Boolean = false,
    val previaDeRota: Boolean = false,
    val postoSelecionado: Boolean = false,
    val pesquisando: Boolean = false,
    val temAtalhos: Boolean = true,
)

/** Onde cada bloco aparece (todos lidos pela tela do mapa). Função pura, testável em JVM. */
data class ElementosDoMapa(
    /** Tudo o que a navegação enxuta decide (banner, barra, painel lateral, pílula, perigo...). */
    val base: ElementosDaTela,
    /** Paisagem fora da navegação: coluna esquerda fixa e rolável com busca, atalhos, cartão e cartões selecionados. */
    val colunaLateral: Boolean,
    val larguraColunaDp: Int,
    /** Busca "Para onde?" (retrato: topo, largura total; paisagem: no alto da coluna). */
    val busca: Boolean,
    /** Faixa de atalhos (favoritos e recentes): sempre abaixo da busca, nunca sobreposta. */
    val atalhos: Boolean,
    /** Postos, trânsito, rolê, localização: retrato abaixo dos atalhos à direita; paisagem na borda direita. */
    val botoesFlutuantes: Boolean,
    /** Centralizar sozinho no canto, durante a navegação. */
    val centralizarNavegando: Boolean,
    /** "Pronto para rodar" compacto (retrato: uma linha de até ~96 dp; paisagem: título, texto e botão na coluna). Só sem gravar. */
    val prontoCompacto: Boolean,
    /** Cartões (moto, proposta, encontro, posto, prévia) dentro da coluna esquerda em vez de cobrir o mapa. */
    val cartoesNaColuna: Boolean,
    /** Na coluna, os cartões selecionados vêm ANTES da busca (e os atalhos somem), para ficarem acima da dobra. */
    val cartoesNoTopoDaColuna: Boolean,
    /** Cartões como folha embaixo (retrato). */
    val cartoesEmbaixo: Boolean,
)

fun elementosDoMapa(e: EstadoDoMapa): ElementosDoMapa {
    val base = navegacaoEnxuta(e.tela, e.classe.orientacao())
    val paisagem = e.classe != ClasseDeTela.Retrato
    val ocioso = base.ocioso
    val livre = ocioso && !e.previaDeRota && !e.postoSelecionado
    val coluna = ocioso && paisagem
    return ElementosDoMapa(
        base = base,
        colunaLateral = coluna,
        larguraColunaDp = if (coluna) larguraColunaLateralDp(e.classe) else 0,
        busca = livre,
        atalhos = livre && e.temAtalhos && !e.tela.gravando && !(coluna && e.cartaoSelecionado),
        botoesFlutuantes = ocioso && !e.pesquisando && !e.previaDeRota && (base.camadas || base.recentralizar),
        centralizarNavegando = !ocioso && base.recentralizar && !e.pesquisando,
        prontoCompacto = livre && !e.tela.gravando,
        cartoesNaColuna = coluna,
        cartoesNoTopoDaColuna = coluna && (e.cartaoSelecionado || e.previaDeRota || e.postoSelecionado),
        cartoesEmbaixo = ocioso && !paisagem,
    )
}
