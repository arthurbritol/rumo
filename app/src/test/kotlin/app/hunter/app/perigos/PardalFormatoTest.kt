package app.hunter.app.perigos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PardalFormatoTest {
    private val dia = 24L * 3600 * 1000
    private val agora = 100 * dia

    private fun st(uf: String, dias: Int? = 0, n: Int = 0, recente: Boolean = true, baixando: Boolean = false, erro: String? = null) =
        PardalUfStatus(uf, dias?.let { agora - it * dia }, n, recente, baixando, erro)

    @Test fun `idade em dias`() {
        assertEquals("hoje", PardalFormato.textoIdade(agora - 3600_000, agora))
        assertEquals("há 1 dia", PardalFormato.textoIdade(agora - dia, agora))
        assertEquals("há 2 dias", PardalFormato.textoIdade(agora - 2 * dia - 1000, agora))
        assertEquals(0, PardalFormato.idadeDias(agora + dia, agora))
    }

    @Test fun `milhar pt-BR`() {
        assertEquals("0", PardalFormato.milhar(0))
        assertEquals("999", PardalFormato.milhar(999))
        assertEquals("3.412", PardalFormato.milhar(3412))
        assertEquals("1.234.567", PardalFormato.milhar(1234567))
    }

    @Test fun `linha do estado`() {
        assertEquals("SP: atualizado há 2 dias · 3.412 pontos", PardalFormato.linhaUf(st("SP", 2, 3412), agora))
        assertEquals("AC: atualizado hoje · 1 ponto", PardalFormato.linhaUf(st("AC", 0, 1), agora))
        assertEquals("SP: desatualizado, há 20 dias · 10 pontos", PardalFormato.linhaUf(st("SP", 20, 10, recente = false), agora))
        assertEquals("SP: baixando…", PardalFormato.linhaUf(st("SP", 2, 5, baixando = true), agora))
        assertEquals("RJ: não baixado", PardalFormato.linhaUf(st("RJ", null), agora))
        assertEquals("SP: atualizado há 2 dias · 5 pontos · falhou (sem rede)", PardalFormato.linhaUf(st("SP", 2, 5, erro = "sem rede"), agora))
        assertTrue(PardalFormato.linhaUf(st("SP", 2, 5, erro = "x".repeat(100)), agora).endsWith("…)"))
    }

    @Test fun `ordenacao e estados a atualizar`() {
        val l = listOf(st("SP", 1), st("AC", 1), st("MG", null, erro = "x"), st("RJ", 1, baixando = true))
        assertEquals(listOf("AC", "MG", "RJ", "SP"), PardalFormato.ordenar(l).map { it.uf })
        assertEquals(listOf("AC", "SP"), PardalFormato.ufsParaAtualizar(l))
    }

    @Test fun `tamanho legivel`() {
        assertEquals("cerca de 12 MB", PardalFormato.tamanho(12_300_000))
        assertEquals("cerca de 800 KB", PardalFormato.tamanho(800_000))
        assertEquals("tamanho desconhecido", PardalFormato.tamanho(null))
    }

    @Test fun `dialogo de dados moveis so fora de navegacao e gravacao`() {
        val p = PardalPedidoDadosMoveis(listOf("SP", "MG"), 5_000_000)
        assertTrue(PardalFormato.mostrarPedido(p, navegando = false, gravando = false))
        assertFalse(PardalFormato.mostrarPedido(p, navegando = true, gravando = false))
        assertFalse(PardalFormato.mostrarPedido(p, navegando = false, gravando = true))
        assertFalse(PardalFormato.mostrarPedido(null, navegando = false, gravando = false))
        assertTrue(PardalFormato.textoPedido(p).contains("SP, MG") && PardalFormato.textoPedido(p).contains("5 MB"))
    }

    @Test fun `espaco usado soma os estados baixados`() {
        assertEquals("Espaço usado: nenhum", PardalFormato.espacoUsado(emptyList()))
        val a = PardalUfStatus("SP", baixadaEm = 1L, bytes = 3_000_000)
        val b = PardalUfStatus("MG", baixadaEm = 1L, bytes = 1_250_000)
        assertEquals("Espaço usado: 4,3 MB", PardalFormato.espacoUsado(listOf(a, b)))
        assertEquals("Espaço usado: menos de 1 MB", PardalFormato.espacoUsado(listOf(PardalUfStatus("AC", baixadaEm = 1L, bytes = 20_000))))
    }

    @Test fun `rede so e sem tarifa se Wi-Fi ou Ethernet e nao medida`() {
        assertTrue(redeSemTarifa(wifi = true, ethernet = false, naoMedida = true))
        assertTrue(redeSemTarifa(wifi = false, ethernet = true, naoMedida = true))
        assertFalse(redeSemTarifa(wifi = true, ethernet = false, naoMedida = false)) // hotspot / roteador 4G
        assertFalse(redeSemTarifa(wifi = false, ethernet = false, naoMedida = true)) // outra rede
    }
}
