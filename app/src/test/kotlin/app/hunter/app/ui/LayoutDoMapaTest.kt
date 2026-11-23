package app.hunter.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LayoutDoMapaTest {
    private fun mapa(
        classe: ClasseDeTela,
        navegando: Boolean = false,
        encontro: Boolean = false,
        cartao: Boolean = false,
        previa: Boolean = false,
        posto: Boolean = false,
        gravando: Boolean = false,
        seguindo: Boolean = true,
        atalhos: Boolean = true,
    ) = elementosDoMapa(
        EstadoDoMapa(
            classe, EstadoDaTela(navegando, encontro, seguindo, gravando),
            cartaoSelecionado = cartao, previaDeRota = previa, postoSelecionado = posto, temAtalhos = atalhos,
        ),
    )

    @Test
    fun `classe de tela por largura e altura`() {
        assertEquals(ClasseDeTela.Retrato, classeDeTela(360, 800))
        assertEquals(ClasseDeTela.Retrato, classeDeTela(800, 1280)) // tablet em pe
        assertEquals(ClasseDeTela.PaisagemCompacta, classeDeTela(800, 360))
        assertEquals(ClasseDeTela.PaisagemCompacta, classeDeTela(891, 411))
        assertEquals(ClasseDeTela.Larga, classeDeTela(1280, 800))
        assertEquals(ClasseDeTela.PaisagemCompacta, classeDeTela(900, 500))
        assertEquals(ClasseDeTela.PaisagemCompacta, classeDeTela(600, 360))
        // Deitado mas estreito (tela dividida, janela flutuante): retrato compacto.
        assertEquals(ClasseDeTela.Retrato, classeDeTela(411, 380))
        assertEquals(ClasseDeTela.Retrato, classeDeTela(400, 360))
        assertEquals(ClasseDeTela.Retrato, classeDeTela(599, 300))
        assertEquals(Orientacao.Paisagem, ClasseDeTela.Larga.orientacao())
        assertEquals(Orientacao.Retrato, ClasseDeTela.Retrato.orientacao())
    }

    @Test
    fun `retrato ocioso tem busca, atalhos, botoes e cartao compacto embaixo`() {
        val e = mapa(ClasseDeTela.Retrato)
        assertTrue(e.busca && e.atalhos && e.botoesFlutuantes && e.prontoCompacto && e.cartoesEmbaixo)
        assertFalse(e.colunaLateral || e.cartoesNaColuna)
        assertEquals(0, e.larguraColunaDp)
    }

    @Test
    fun `paisagem ociosa tem coluna esquerda com tudo e trilho de abas`() {
        for (c in listOf(ClasseDeTela.PaisagemCompacta, ClasseDeTela.Larga)) {
            val e = mapa(c)
            assertTrue(e.colunaLateral && e.cartoesNaColuna && e.busca && e.atalhos && e.botoesFlutuantes)
            assertFalse(e.cartoesEmbaixo)
            assertTrue(e.prontoCompacto)
            assertEquals(larguraColunaLateralDp(c), e.larguraColunaDp)
        }
        assertTrue(larguraColunaLateralDp(ClasseDeTela.PaisagemCompacta) in 300..340)
    }

    @Test
    fun `tablet larga a coluna e o conteudo`() {
        assertTrue(larguraColunaLateralDp(ClasseDeTela.Larga) > larguraColunaLateralDp(ClasseDeTela.PaisagemCompacta))
        assertTrue(larguraMaximaDoConteudoDp(ClasseDeTela.Larga) in 600..720)
        assertTrue(larguraMaximaDoConteudoDp(ClasseDeTela.PaisagemCompacta) in 600..720)
    }

    @Test
    fun `navegando some a coluna e as abas e mantem o essencial`() {
        for (c in ClasseDeTela.entries) {
            val e = mapa(c, navegando = true, seguindo = false)
            assertFalse(e.colunaLateral || e.busca || e.atalhos || e.botoesFlutuantes || e.prontoCompacto)
            assertTrue(e.centralizarNavegando)
        }
        assertTrue(mapa(ClasseDeTela.PaisagemCompacta, navegando = true).base.painelLateral)
        assertTrue(mapa(ClasseDeTela.Retrato, navegando = true).base.bannerManobra)
        assertFalse(mapa(ClasseDeTela.Retrato, navegando = true, seguindo = true).centralizarNavegando)
        for (c in ClasseDeTela.entries) assertTrue(mapa(c, navegando = true).base.alertaPerigo)
    }

    @Test
    fun `cartao selecionado em paisagem vai para o topo da coluna e esconde os atalhos`() {
        val p = mapa(ClasseDeTela.PaisagemCompacta, cartao = true)
        assertTrue(p.cartoesNaColuna && p.colunaLateral && p.cartoesNoTopoDaColuna)
        assertFalse(p.atalhos)
        assertTrue(p.busca)
        // Sem cartao os atalhos voltam e a ordem e a de sempre.
        val livre = mapa(ClasseDeTela.PaisagemCompacta)
        assertTrue(livre.atalhos)
        assertFalse(livre.cartoesNoTopoDaColuna)
        // Em retrato os atalhos continuam (o cartao fica embaixo) e nada vai para o topo da coluna.
        val r = mapa(ClasseDeTela.Retrato, cartao = true)
        assertTrue(r.atalhos)
        assertFalse(r.cartoesNoTopoDaColuna)
    }

    @Test
    fun `pronto para rodar e compacto em retrato e em paisagem, menos gravando`() {
        for (c in ClasseDeTela.entries) {
            assertTrue(mapa(c).prontoCompacto)
            assertTrue(mapa(c, cartao = true).prontoCompacto)
            assertFalse(mapa(c, gravando = true).prontoCompacto)
        }
    }

    @Test
    fun `atalhos na coluna sao poucos em paisagem de celular`() {
        assertEquals(0, maximoDeAtalhosNaColuna(ClasseDeTela.Retrato))
        assertEquals(3, maximoDeAtalhosNaColuna(ClasseDeTela.PaisagemCompacta))
        assertTrue(maximoDeAtalhosNaColuna(ClasseDeTela.Larga) > 3)
    }

    @Test
    fun `previa de rota e posto selecionado trocam a busca pelo cartao`() {
        for (c in ClasseDeTela.entries) {
            for (e in listOf(mapa(c, previa = true), mapa(c, posto = true))) {
                assertFalse(e.busca || e.atalhos || e.prontoCompacto)
            }
            assertFalse(mapa(c, previa = true).botoesFlutuantes)
            assertTrue(mapa(c, posto = true).botoesFlutuantes)
        }
        assertTrue(mapa(ClasseDeTela.PaisagemCompacta, previa = true).cartoesNaColuna)
        assertTrue(mapa(ClasseDeTela.PaisagemCompacta, previa = true).cartoesNoTopoDaColuna)
        assertTrue(mapa(ClasseDeTela.PaisagemCompacta, posto = true).cartoesNoTopoDaColuna)
    }

    @Test
    fun `gravando nao mostra atalhos nem cartao compacto mas mantem o aviso de perigo`() {
        for (c in ClasseDeTela.entries) {
            val e = mapa(c, gravando = true)
            assertFalse(e.atalhos || e.prontoCompacto)
            assertTrue(e.busca)
            assertTrue(e.base.alertaPerigo)
        }
    }

    @Test
    fun `sem favoritos nem recentes nao ha faixa de atalhos`() {
        assertFalse(mapa(ClasseDeTela.Retrato, atalhos = false).atalhos)
    }

    @Test
    fun `encontro ativo so vira pilula navegando`() {
        for (c in ClasseDeTela.entries) {
            assertTrue(mapa(c, navegando = true, encontro = true).base.pilulaEncontro)
            assertFalse(mapa(c, encontro = true).base.pilulaEncontro)
        }
        // Encontro ativo com o cartao aberto: em paisagem o cartao sobe ao topo da coluna e os atalhos somem.
        val e = mapa(ClasseDeTela.PaisagemCompacta, encontro = true, cartao = true)
        assertTrue(e.cartoesNoTopoDaColuna && !e.atalhos && !e.base.pilulaEncontro)
        // Navegando, o encontro vira so a pilula: sem coluna nem cartoes.
        val n = mapa(ClasseDeTela.PaisagemCompacta, navegando = true, encontro = true, cartao = true)
        assertTrue(n.base.pilulaEncontro && !n.colunaLateral && !n.cartoesNoTopoDaColuna)
    }

    @Test
    fun `abas aparecem fora do mapa mesmo navegando`() {
        assertEquals(FormaDasAbas.Trilho, formaDasAbas(ClasseDeTela.PaisagemCompacta, abasNoMapa = false, naAbaDoMapa = false))
        assertEquals(FormaDasAbas.BarraInferior, formaDasAbas(ClasseDeTela.Retrato, abasNoMapa = false, naAbaDoMapa = false))
        assertEquals(FormaDasAbas.Nenhuma, formaDasAbas(ClasseDeTela.Larga, abasNoMapa = false, naAbaDoMapa = true))
    }

    @Test
    fun `ate quatro botoes ficam todos visiveis`() {
        val todos = BotaoDoMapa.entries
        val d = distribuirBotoes(todos)
        assertEquals(todos, d.visiveis)
        assertFalse(d.temMais)
        assertEquals(listOf(BotaoDoMapa.Postos), distribuirBotoes(listOf(BotaoDoMapa.Postos)).visiveis)
        assertEquals(emptyList(), distribuirBotoes(emptyList()).visiveis)
    }

    @Test
    fun `passando de quatro o quarto vira mais e o resto vai para o menu`() {
        val cinco = BotaoDoMapa.entries + BotaoDoMapa.Postos
        val d = distribuirBotoes(cinco)
        assertEquals(3, d.visiveis.size)
        assertEquals(2, d.noMenuMais.size)
        assertTrue(d.temMais)
    }

    @Test
    fun `quantos botoes cabem na altura livre`() {
        // Retrato alto, cartao compacto embaixo (~120 dp), com atalhos: os 4 cabem.
        val alto = alturaLivreParaBotoesDp(alturaDoMapaDp = 780, insetTopoDp = 24, comAtalhos = true, alturaEmbaixoDp = 120)
        assertEquals(4, maximoDeBotoes(alto))
        // Retrato baixo (640) com cartao de proposta (~300 dp): so um botao e o "mais".
        val baixo = alturaLivreParaBotoesDp(640, 24, true, 300)
        assertEquals(2, maximoDeBotoes(baixo))
        // Nunca menos de 2 (um botao + "mais") e nunca mais de 4.
        assertEquals(2, maximoDeBotoes(-50))
        assertEquals(4, maximoDeBotoes(2000))
        // Limites da conta: 4 alvos = 4*52 + 3*10 = 238 dp; 3 = 176; 2 = 114.
        assertEquals(4, maximoDeBotoes(238))
        assertEquals(3, maximoDeBotoes(237))
        assertEquals(3, maximoDeBotoes(176))
        assertEquals(2, maximoDeBotoes(175))
    }

    @Test
    fun `a pilha de botoes escolhida nunca passa da altura livre`() {
        for (h in 500..900 step 20) for (embaixo in listOf(120, 232, 300, 350)) for (atalhos in listOf(true, false)) {
            val livre = alturaLivreParaBotoesDp(h, 24, atalhos, embaixo)
            val n = maximoDeBotoes(livre)
            val usado = n * 52 + (n - 1) * 10
            // so pode estourar quando nem 2 alvos cabem (piso de 2)
            if (n > 2) assertTrue(usado <= livre, "h=$h embaixo=$embaixo usado=$usado livre=$livre")
        }
    }

    @Test
    fun `altura do mapa do detalhe fica entre 200 e 340`() {
        assertEquals(340, alturaDoMapaDoDetalheDp(900))
        assertEquals(200, alturaDoMapaDoDetalheDp(360))
        assertEquals(200, alturaDoMapaDoDetalheDp(300))
        assertEquals(330, alturaDoMapaDoDetalheDp(600))
    }
}
