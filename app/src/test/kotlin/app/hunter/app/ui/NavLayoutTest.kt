package app.hunter.app.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavLayoutTest {
    private fun fora(o: Orientacao, seguindo: Boolean = true) =
        navegacaoEnxuta(EstadoDaTela(navegando = false, seguindo = seguindo), o)

    private fun nav(o: Orientacao, encontro: Boolean = false, seguindo: Boolean = true) =
        navegacaoEnxuta(EstadoDaTela(navegando = true, encontroAtivo = encontro, seguindo = seguindo), o)

    @Test
    fun `fora da navegacao tudo continua como antes nas duas orientacoes`() {
        for (o in Orientacao.entries) {
            val e = fora(o)
            assertTrue(e.busca && e.camadas && e.faixasSecundarias && e.cartoes && e.painelOcioso && e.abas)
            assertFalse(e.bannerManobra || e.barraInferior || e.painelLateral || e.pilulaEncontro)
            assertFalse(e.recentralizar)
        }
    }

    @Test
    fun `fora da navegacao recentralizar aparece quando o usuario mexeu no mapa`() {
        assertTrue(fora(Orientacao.Retrato, seguindo = false).recentralizar)
    }

    @Test
    fun `navegando em retrato mostra banner e barra inferior e esconde o resto`() {
        val e = nav(Orientacao.Retrato)
        assertTrue(e.bannerManobra && e.barraInferior)
        assertFalse(e.painelLateral)
        assertFalse(e.busca || e.camadas || e.faixasSecundarias || e.cartoes || e.painelOcioso || e.abas)
    }

    @Test
    fun `navegando em paisagem mostra so o painel lateral`() {
        val e = nav(Orientacao.Paisagem)
        assertTrue(e.painelLateral)
        assertFalse(e.bannerManobra || e.barraInferior)
        assertFalse(e.busca || e.camadas || e.faixasSecundarias || e.cartoes || e.painelOcioso || e.abas)
    }

    @Test
    fun `pilula do encontro so aparece navegando com encontro ativo`() {
        for (o in Orientacao.entries) {
            assertTrue(nav(o, encontro = true).pilulaEncontro)
            assertFalse(nav(o, encontro = false).pilulaEncontro)
            assertFalse(navegacaoEnxuta(EstadoDaTela(navegando = false, encontroAtivo = true), o).pilulaEncontro)
        }
    }

    @Test
    fun `recentralizar na navegacao so quando nao esta seguindo`() {
        for (o in Orientacao.entries) {
            assertTrue(nav(o, seguindo = false).recentralizar)
            assertFalse(nav(o, seguindo = true).recentralizar)
        }
    }
}
