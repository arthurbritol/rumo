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
        assertEquals(Orientacao.Paisagem, ClasseDeTela.Larga.orientacao())
        assertEquals(Orientacao.Retrato, ClasseDeTela.Retrato.orientacao())
    }

    @Test
    fun `retrato ocioso tem busca, atalhos, botoes e cartao compacto embaixo`() {
        val e = mapa(ClasseDeTela.Retrato)
        assertTrue(e.busca && e.atalhos && e.botoesFlutuantes && e.prontoCompacto && e.cartoesEmbaixo)
        assertFalse(e.colunaLateral || e.cartoesNaColuna)
        assertEquals(0, e.larguraColunaDp)
        assertEquals(FormaDasAbas.BarraInferior, e.abas)
    }

    @Test
    fun `paisagem ociosa tem coluna esquerda com tudo e trilho de abas`() {
        for (c in listOf(ClasseDeTela.PaisagemCompacta, ClasseDeTela.Larga)) {
            val e = mapa(c)
            assertTrue(e.colunaLateral && e.cartoesNaColuna && e.busca && e.atalhos && e.botoesFlutuantes)
            assertFalse(e.prontoCompacto || e.cartoesEmbaixo)
            assertEquals(larguraColunaLateralDp(c), e.larguraColunaDp)
            assertEquals(FormaDasAbas.Trilho, e.abas)
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
            assertEquals(FormaDasAbas.Nenhuma, e.abas)
            assertTrue(e.centralizarNavegando)
        }
        assertTrue(mapa(ClasseDeTela.PaisagemCompacta, navegando = true).base.painelLateral)
        assertTrue(mapa(ClasseDeTela.Retrato, navegando = true).base.bannerManobra)
        assertFalse(mapa(ClasseDeTela.Retrato, navegando = true, seguindo = true).centralizarNavegando)
        for (c in ClasseDeTela.entries) assertTrue(mapa(c, navegando = true).base.alertaPerigo)
    }

    @Test
    fun `cartao selecionado em retrato tira o cartao pronto e em paisagem vai para a coluna`() {
        assertFalse(mapa(ClasseDeTela.Retrato, cartao = true).prontoCompacto)
        val p = mapa(ClasseDeTela.PaisagemCompacta, cartao = true)
        assertTrue(p.cartoesNaColuna && p.colunaLateral)
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
            // fora da navegacao o cartao do encontro e um cartao selecionado: nao coexiste com o cartao pronto compacto
            assertFalse(mapa(c, encontro = true, cartao = true).prontoCompacto)
        }
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
    fun `altura do mapa do detalhe fica entre 200 e 340`() {
        assertEquals(340, alturaDoMapaDoDetalheDp(900))
        assertEquals(200, alturaDoMapaDoDetalheDp(360))
        assertEquals(200, alturaDoMapaDoDetalheDp(300))
        assertEquals(330, alturaDoMapaDoDetalheDp(600))
    }
}
