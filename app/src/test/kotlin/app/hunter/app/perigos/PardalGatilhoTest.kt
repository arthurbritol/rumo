package app.hunter.app.perigos

import app.hunter.nav.LatLon
import app.hunter.nav.Route
import app.hunter.nav.RouteProfile
import app.hunter.nav.perigos.ResultadoPardal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PardalGatilhoTest {
    private val base = 1_700_000_000_000L
    private val dia = 24L * 3600 * 1000

    private class Ambiente {
        var ativo = true
        var moveis = false
        var wifi = true
        var catalogo = true
        val baixadas = HashMap<String, Long>()
        val chamadas = mutableListOf<Pair<String, Boolean>>()
        var simultaneas = 0
        var maxSimultaneas = 0
        var resultado: (String) -> ResultadoPardal = { ResultadoPardal.Atualizada(it, 10) }
        var catalogosBaixados = 0
        /** Troca a escolha de UFs do ponto (fronteira: várias UFs). */
        var ufsDoPonto: ((Double) -> List<String>)? = null
    }

    private fun rota() = Route(listOf(LatLon(-8.0, -35.0), LatLon(-23.5, -46.6)), emptyList(), 1.0, 1.0, false, false, RouteProfile.FASTEST)

    private fun TestScope.gatilho(a: Ambiente, status: PardalStatus? = null) = PardalGatilho(
        scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        ativo = { a.ativo },
        permitirDadosMoveis = { a.moveis },
        emWifi = { a.wifi },
        ufDoPonto = { lat, _ ->
            if (!a.catalogo) emptyList() else a.ufsDoPonto?.invoke(lat) ?: if (lat > -10) listOf("PE") else listOf("SP")
        },
        ufsDaRota = { listOf("PE", "SP") },
        catalogoCarregado = { a.catalogo },
        carregarCatalogo = { a.catalogosBaixados++; a.catalogo = true; true },
        baixadoEm = { a.baixadas[it] },
        atualizar = { uf, moveis ->
            a.chamadas += uf to moveis
            a.simultaneas++
            a.maxSimultaneas = maxOf(a.maxSimultaneas, a.simultaneas)
            delay(100)
            a.simultaneas--
            a.resultado(uf)
        },
        status = status,
        agora = { base + currentTime },
    )

    @Test fun `primeira posicao valida baixa a UF que falta`() = runTest {
        val a = Ambiente()
        val g = gatilho(a)
        g.aoPosicao(-8.05, -34.95)
        advanceUntilIdle()
        assertEquals(listOf("PE" to false), a.chamadas)
    }

    @Test fun `mesma UF nao repete e mudar de UF baixa a nova`() = runTest {
        val a = Ambiente()
        val g = gatilho(a)
        g.aoPosicao(-8.05, -34.95)
        g.aoPosicao(-8.25, -34.95) // outro quadrado, mesma UF
        advanceUntilIdle()
        assertEquals(1, a.chamadas.size)
        g.aoPosicao(-23.5, -46.6)
        advanceUntilIdle()
        assertEquals(listOf("PE", "SP"), a.chamadas.map { it.first })
    }

    @Test fun `UF baixada ha menos de 7 dias nao baixa e acima de 7 dias baixa`() = runTest {
        val a = Ambiente()
        a.baixadas["PE"] = base - 3 * dia
        a.baixadas["SP"] = base - 8 * dia
        val g = gatilho(a)
        g.aoRota(rota())
        advanceUntilIdle()
        assertEquals(listOf("SP"), a.chamadas.map { it.first })
    }

    @Test fun `no maximo uma verificacao por UF a cada hora`() = runTest {
        val a = Ambiente()
        a.resultado = { ResultadoPardal.Falhou("x") }
        val g = gatilho(a)
        g.aoRota(rota())
        advanceUntilIdle()
        g.aoRota(rota())
        advanceUntilIdle()
        assertEquals(2, a.chamadas.size)
        advanceTimeBy(3_600_001)
        g.aoRota(rota())
        advanceUntilIdle()
        assertEquals(4, a.chamadas.size)
    }

    @Test fun `um download por vez`() = runTest {
        val a = Ambiente()
        val g = gatilho(a)
        g.aoRota(rota())
        advanceUntilIdle()
        assertEquals(2, a.chamadas.size)
        assertEquals(1, a.maxSimultaneas)
    }

    @Test fun `acao manual fora do Wi-Fi guarda o pedido e so baixa depois de confirmar`() = runTest {
        val a = Ambiente()
        a.resultado = { ResultadoPardal.PrecisaConfirmarDadosMoveis(40_000_000L) }
        val g = gatilho(a)
        g.baixarAgora("PE")
        advanceUntilIdle()
        assertEquals(PardalPedidoDadosMoveis(listOf("PE"), 40_000_000L), g.pedido.value)
        assertEquals(listOf("PE" to false), a.chamadas)
        a.resultado = { ResultadoPardal.Atualizada(it, 5) }
        g.confirmarDadosMoveis()
        advanceUntilIdle()
        assertNull(g.pedido.value)
        assertEquals(listOf("PE" to false, "PE" to true), a.chamadas)
    }

    @Test fun `pedido de dados moveis guarda a lista de UFs com a soma dos bytes e confirmar baixa todas`() = runTest {
        val a = Ambiente()
        a.resultado = { ResultadoPardal.PrecisaConfirmarDadosMoveis(if (it == "PE") 10_000_000L else 30_000_000L) }
        val g = gatilho(a)
        g.baixarAgora("PE")
        g.baixarAgora("SP")
        advanceUntilIdle()
        assertEquals(PardalPedidoDadosMoveis(listOf("PE", "SP"), 40_000_000L), g.pedido.value)
        a.resultado = { ResultadoPardal.Atualizada(it, 5) }
        a.chamadas.clear()
        g.confirmarDadosMoveis()
        advanceUntilIdle()
        assertNull(g.pedido.value)
        assertEquals(setOf("PE" to true, "SP" to true), a.chamadas.toSet())
        assertEquals(2, a.chamadas.size)
    }

    @Test fun `tamanho desconhecido de uma UF nao apaga o das outras`() = runTest {
        val a = Ambiente()
        a.resultado = { ResultadoPardal.PrecisaConfirmarDadosMoveis(if (it == "PE") null else 5_000_000L) }
        val g = gatilho(a)
        g.baixarAgora("PE"); g.baixarAgora("SP")
        advanceUntilIdle()
        assertEquals(5_000_000L, g.pedido.value!!.bytes)
    }

    @Test fun `gatilho automatico fora do Wi-Fi com so Wi-Fi nao pergunta nem chama o atualizador`() = runTest {
        val a = Ambiente().apply { wifi = false }
        val g = gatilho(a)
        g.aoPosicao(-8.05, -34.95)
        g.aoRota(rota())
        advanceUntilIdle()
        assertTrue(a.chamadas.isEmpty())
        assertNull(g.pedido.value)
    }

    @Test fun `o Wi-Fi que volta e aproveitado no proximo quadrado, sem esperar uma hora`() = runTest {
        val a = Ambiente().apply { wifi = false }
        val g = gatilho(a)
        g.aoPosicao(-8.05, -34.95)
        advanceUntilIdle()
        assertTrue(a.chamadas.isEmpty())
        a.wifi = true
        g.aoPosicao(-8.25, -34.95)
        advanceUntilIdle()
        assertEquals(listOf("PE" to false), a.chamadas)
    }

    @Test fun `reverifica a UF atual quando o quadrado muda depois de uma hora`() = runTest {
        val a = Ambiente()
        a.resultado = { ResultadoPardal.Falhou("sem rede") }
        val g = gatilho(a)
        g.aoPosicao(-8.05, -34.95)
        advanceUntilIdle()
        assertEquals(1, a.chamadas.size)
        // Mesma UF, outro quadrado, dentro da hora: nada.
        g.aoPosicao(-8.25, -34.95)
        advanceUntilIdle()
        assertEquals(1, a.chamadas.size)
        // Passada a hora, mesmo sem trocar de UF, tenta de novo.
        advanceTimeBy(3_600_001)
        g.aoPosicao(-8.45, -34.95)
        advanceUntilIdle()
        assertEquals(2, a.chamadas.size)
    }

    @Test fun `fronteira baixa todas as UFs cujo bbox contem a posicao`() = runTest {
        val a = Ambiente().apply { ufsDoPonto = { listOf("MG", "SP") } }
        val g = gatilho(a)
        g.aoPosicao(-22.23, -45.94)
        advanceUntilIdle()
        assertEquals(listOf("MG", "SP"), a.chamadas.map { it.first })
    }

    @Test fun `toque repetido em Atualizar agora nao duplica e a UF aparece baixando na hora`() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("pardal-status2").toFile()
        try {
            val st = PardalStatus(app.hunter.nav.perigos.PardalArmazenamento(dir), agora = { base })
            val a = Ambiente()
            val g = gatilho(a, st)
            g.baixarAgora("PE")
            assertEquals(true, st.ufs.value["PE"]?.baixando) // antes mesmo de a coroutine rodar
            g.baixarAgora("PE")
            g.baixarAgora("PE")
            advanceUntilIdle()
            assertEquals(1, a.chamadas.size)
            assertTrue(st.ufs.value["PE"]?.baixando != true)
            g.baixarAgora("PE") // depois de terminar, pode de novo
            advanceUntilIdle()
            assertEquals(2, a.chamadas.size)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `preferencia de dados moveis liberada e repassada ao atualizador`() = runTest {
        val a = Ambiente().apply { moveis = true }
        gatilho(a).aoPosicao(-8.05, -34.95)
        advanceUntilIdle()
        assertEquals(listOf("PE" to true), a.chamadas)
    }

    @Test fun `desabilitado nao faz nada`() = runTest {
        val a = Ambiente().apply { ativo = false }
        val g = gatilho(a)
        g.aoPosicao(-8.05, -34.95)
        g.aoRota(rota())
        advanceUntilIdle()
        assertTrue(a.chamadas.isEmpty())
    }

    @Test fun `sem catalogo baixa o catalogo uma vez para descobrir a UF`() = runTest {
        val a = Ambiente().apply { catalogo = false }
        val g = gatilho(a)
        g.aoPosicao(-8.05, -34.95)
        advanceUntilIdle()
        assertEquals(1, a.catalogosBaixados)
        assertEquals(listOf("PE" to false), a.chamadas)
    }

    @Test fun `sem catalogo e fora do Wi-Fi com so Wi-Fi nao baixa nada`() = runTest {
        val a = Ambiente().apply { catalogo = false; wifi = false }
        gatilho(a).aoPosicao(-8.05, -34.95)
        advanceUntilIdle()
        assertEquals(0, a.catalogosBaixados)
        assertTrue(a.chamadas.isEmpty())
    }

    @Test fun `status reflete falha e sucesso`() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("pardal-status").toFile()
        try {
            val arm = app.hunter.nav.perigos.PardalArmazenamento(dir)
            val st = PardalStatus(arm, agora = { base })
            val a = Ambiente()
            a.resultado = { ResultadoPardal.Falhou("sem rede") }
            val g = gatilho(a, st)
            g.aoPosicao(-8.05, -34.95)
            advanceUntilIdle()
            assertEquals("sem rede", assertNotNull(st.ufs.value["PE"]).erro)
            assertEquals(false, st.ufs.value["PE"]!!.baixando)
        } finally { dir.deleteRecursively() }
    }
}
