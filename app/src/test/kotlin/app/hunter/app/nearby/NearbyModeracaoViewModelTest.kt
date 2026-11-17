package app.hunter.app.nearby

import app.hunter.model.TrackPoint
import app.hunter.nearby.ClientMessage
import app.hunter.nearby.Distancia
import app.hunter.nearby.MotivoDenuncia
import app.hunter.nearby.MotivoFim
import app.hunter.nearby.Parada
import app.hunter.nearby.Participante
import app.hunter.nearby.Ponto
import app.hunter.nearby.Preferencia
import app.hunter.nearby.PreferenciaVista
import app.hunter.nearby.Proposta
import app.hunter.nearby.Ritmo
import app.hunter.nearby.ServerMessage
import app.hunter.nearby.Vizinho
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyModeracaoViewModelTest {
    private val pref = Preferencia(Ritmo.MODERADO, Distancia.ATE50, Parada.CAFE, 5)
    private val centro = TrackPoint(timeMs = 0, latitude = -20.5, longitude = -40.5)
    private val idEnc = "0123456789abcdef"
    private val cellCentro = "-21:-41"

    private class Rig(val link: FakeLink, val positions: MutableSharedFlow<TrackPoint>, val vm: NearbyViewModel)

    private fun TestScope.rig(): Rig {
        val link = FakeLink()
        val positions = MutableSharedFlow<TrackPoint>(extraBufferCapacity = 8)
        val vm = NearbyViewModel(
            link = link,
            signedIn = { true },
            positions = { positions },
            clock = { testScheduler.currentTime },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        return Rig(link, positions, vm)
    }

    private fun viz(sessao: String) =
        Vizinho(sessao, -20.5, -40.5, null, PreferenciaVista(Ritmo.MODERADO, Distancia.ATE50, Parada.CAFE), false)

    private fun proposta() = Proposta(idEnc, -20.6, -40.5, 2, 4, Ritmo.MODERADO, Distancia.ATE50, Parada.CAFE, true)

    private fun part(s: String) = Participante(s, null, null, false, null, null, false)

    private fun encontroMsg(vararg outros: String) = ServerMessage.Encontro(
        idEnc, Ponto(-20.6, -40.5), 1, "eu1", listOf(part("eu1")) + outros.map(::part),
    )

    private fun Rig.emit(msg: ServerMessage, celula: String = cellCentro, extra: Boolean = false) {
        link.eventFlow.tryEmit(NearbyEvent(celula, msg, extra))
    }

    private fun Rig.procurando() {
        vm.ligar(pref)
        positions.tryEmit(centro)
        emit(ServerMessage.Vizinhos(listOf(viz("a"), viz("b"))))
        emit(ServerMessage.Propostas(listOf(proposta())))
        link.sent.clear()
    }

    private fun Rig.sessoesVisiveis() = (vm.estado.value as NearbyUiState.Procurando).vizinhos.map { it.sessao }

    @Test
    fun `bloquear envia so a sessao ao quadrado do vizinho e a moto some logo`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.bloquear("a")
        assertEquals(ClientMessage.Bloquear("a") to cellCentro, r.link.sent.single())
        assertEquals(listOf("b"), r.sessoesVisiveis()) // sem esperar o servidor
        assertTrue(r.vm.enviando.value)
        // O servidor manda a lista de novo com a mesma sessao: continua escondida.
        r.emit(ServerMessage.Vizinhos(listOf(viz("a"), viz("b"))))
        assertEquals(listOf("b"), r.sessoesVisiveis())
        r.emit(ServerMessage.Ok("bloqueio"))
        assertEquals(false, r.vm.enviando.value)
        assertEquals(listOf("b"), r.sessoesVisiveis())
    }

    @Test
    fun `denunciar envia sessao e motivo, nao esconde e confirma`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.denunciar("a", MotivoDenuncia.INCOMODO)
        assertEquals(ClientMessage.Denunciar("a", MotivoDenuncia.INCOMODO) to cellCentro, r.link.sent.single())
        assertEquals(listOf("a", "b"), r.sessoesVisiveis())
        assertTrue(r.vm.enviando.value)
        r.vm.bloquear("b") // enquanto envia, nada novo sai
        assertEquals(1, r.link.sent.size)
        r.emit(ServerMessage.Ok("denuncia"))
        assertEquals("Denúncia enviada", r.vm.aviso.value)
        assertEquals(false, r.vm.enviando.value)
    }

    @Test
    fun `erro suspenso desliga a busca e mostra a mensagem`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.bloquear("a")
        r.emit(ServerMessage.Erro("suspenso"))
        assertEquals(
            NearbyUiState.Desligado("Recurso indisponível por denúncias. Fale com o dono do Rumo."),
            r.vm.estado.value,
        )
        assertEquals(null, r.vm.preferencia.value)
        assertEquals(false, r.vm.enviando.value)
        assertEquals(1, r.link.disconnects)
    }

    @Test
    fun `erro inexistente mostra aviso e a moto continua escondida`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.bloquear("a")
        r.emit(ServerMessage.Erro("inexistente"))
        assertEquals("Não foi possível encontrar essa pessoa. Ela pode ter saído", r.vm.aviso.value)
        assertEquals(false, r.vm.enviando.value)
        assertEquals(listOf("b"), r.sessoesVisiveis())
    }

    @Test
    fun `erro limite avisa e a moto volta para tentar de novo`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.bloquear("a")
        r.emit(ServerMessage.Erro("limite"))
        assertEquals("Você atingiu o limite de pessoas bloqueadas", r.vm.aviso.value)
        assertEquals(listOf("a", "b"), r.sessoesVisiveis())
    }

    @Test
    fun `indisponivel tenta de novo uma vez e depois avisa`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.bloquear("a")
        r.emit(ServerMessage.Erro("indisponivel"))
        assertEquals(1, r.link.sent.size) // ainda esperando o intervalo
        advanceTimeBy(3_000)
        val esperado: ClientMessage = ClientMessage.Bloquear("a")
        assertEquals(listOf(esperado to cellCentro, esperado to cellCentro), r.link.sent.toList())
        assertTrue(r.vm.enviando.value)
        r.emit(ServerMessage.Erro("indisponivel"))
        advanceTimeBy(5_000)
        assertEquals(2, r.link.sent.size) // só uma nova tentativa
        assertEquals("Não foi possível bloquear agora. Tente de novo daqui a pouco", r.vm.aviso.value)
        assertEquals(false, r.vm.enviando.value)
        assertEquals(listOf("a", "b"), r.sessoesVisiveis())
    }

    @Test
    fun `sem resposta o envio termina com aviso`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.denunciar("a", MotivoDenuncia.OUTRO)
        advanceTimeBy(16_000)
        assertEquals(TEXTO_SEM_RESPOSTA, r.vm.aviso.value)
        assertEquals(false, r.vm.enviando.value)
    }

    @Test
    fun `sessao desconhecida avisa e nao envia`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.bloquear("zzz")
        assertTrue(r.link.sent.isEmpty())
        assertEquals("Não foi possível encontrar essa pessoa. Ela pode ter saído", r.vm.aviso.value)
    }

    @Test
    fun `quem propos bloqueia participante pelo quadrado do ponto e ele some do encontro`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.proporPonto(-20.6, -40.5, 4)
        r.emit(encontroMsg("p2", "p3"))
        r.link.sent.clear()
        r.vm.bloquear("p2")
        assertEquals(ClientMessage.Bloquear("p2") to cellCentro, r.link.sent.single())
        assertTrue(r.link.sentEnc.isEmpty())
        val ativo = r.vm.encontro.value as EncontroUiState.Ativo
        assertEquals(listOf("eu1", "p3"), ativo.encontro.participantes.map { it.sessao })
        // O servidor repete a lista com p2: continua fora.
        r.emit(encontroMsg("p2", "p3"))
        assertEquals(listOf("eu1", "p3"), (r.vm.encontro.value as EncontroUiState.Ativo).encontro.participantes.map { it.sessao })
    }

    @Test
    fun `quem entrou bloqueia pela conexao do encontro`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.entrar(idEnc)
        r.link.openedEncFlow.tryEmit(idEnc)
        r.emit(encontroMsg("p2"), extra = true)
        r.link.sent.clear()
        r.link.sentEnc.clear()
        r.vm.denunciar("p2", MotivoDenuncia.COMPORTAMENTO)
        assertEquals(listOf<ClientMessage>(ClientMessage.Denunciar("p2", MotivoDenuncia.COMPORTAMENTO)), r.link.sentEnc)
        assertTrue(r.link.sent.isEmpty())
        r.emit(ServerMessage.Ok("denuncia"), extra = true)
        assertEquals("Denúncia enviada", r.vm.aviso.value)
    }

    @Test
    fun `no cartao de fim bloqueia e denuncia pelo quadrado guardado do encontro`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.entrar(idEnc)
        r.link.openedEncFlow.tryEmit(idEnc)
        r.emit(encontroMsg("p2"), extra = true)
        r.emit(ServerMessage.Fim(idEnc, MotivoFim.CHEGARAM), extra = true)
        assertIs<EncontroUiState.Encerrado>(r.vm.encontro.value)
        assertEquals(listOf("p2"), r.vm.fimPessoas.value)
        r.link.sent.clear()
        r.link.sentEnc.clear()
        r.vm.denunciar("p2", MotivoDenuncia.FALSO)
        assertEquals(ClientMessage.Denunciar("p2", MotivoDenuncia.FALSO) to cellCentro, r.link.sent.single())
        r.emit(ServerMessage.Ok("denuncia"))
        assertEquals("Denúncia enviada", r.vm.aviso.value)
        r.vm.bloquear("p2")
        assertEquals(ClientMessage.Bloquear("p2") to cellCentro, r.link.sent.last())
        r.vm.limparFim()
        assertTrue(r.vm.fimPessoas.value.isEmpty())
    }

    @Test
    fun `fim do encontro antes do ok nao fecha a conexao extra ate a resposta`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.entrar(idEnc)
        r.link.openedEncFlow.tryEmit(idEnc)
        r.emit(encontroMsg("p2"), extra = true)
        r.vm.bloquear("p2")
        r.emit(ServerMessage.Fim(idEnc, MotivoFim.CANCELADO), extra = true)
        assertEquals(0, r.link.encDisconnects) // o pedido ainda espera resposta pela conexao extra
        r.emit(ServerMessage.Ok("bloqueio"), extra = true)
        assertEquals(1, r.link.encDisconnects)
        assertEquals(false, r.vm.enviando.value)
    }

    @Test
    fun `fim antes do erro tambem fecha a conexao extra depois`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.entrar(idEnc)
        r.link.openedEncFlow.tryEmit(idEnc)
        r.emit(encontroMsg("p2"), extra = true)
        r.vm.denunciar("p2", MotivoDenuncia.OUTRO)
        r.emit(ServerMessage.Fim(idEnc, MotivoFim.CANCELADO), extra = true)
        r.emit(ServerMessage.Erro("limite"), extra = true)
        assertEquals(1, r.link.encDisconnects)
    }

    @Test
    fun `quadrado de destino fora da busca avisa na hora e nao envia`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.entrar(idEnc)
        r.link.openedEncFlow.tryEmit(idEnc)
        r.emit(encontroMsg("p2"), extra = true)
        r.emit(ServerMessage.Fim(idEnc, MotivoFim.CHEGARAM), extra = true)
        r.positions.tryEmit(TrackPoint(timeMs = 0, latitude = 10.5, longitude = 10.5)) // outros quadrados
        r.link.sent.clear()
        r.vm.bloquear("p2")
        assertTrue(r.link.sent.isEmpty())
        assertEquals("Não foi possível bloquear agora. Tente de novo daqui a pouco", r.vm.aviso.value)
        assertEquals(false, r.vm.enviando.value)
    }

    @Test
    fun `erro de espera durante a proposta no mesmo quadrado pertence a proposta`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.bloquear("a")
        r.vm.proporPonto(-20.6, -40.5, 4)
        r.emit(ServerMessage.Erro("espera", 120_000))
        assertEquals(EncontroUiState.Nenhum, r.vm.encontro.value)
        assertEquals(textoDoErro("espera", 120_000), r.vm.aviso.value)
        assertTrue(r.vm.enviando.value) // a moderacao continua esperando a propria resposta
        r.emit(ServerMessage.Ok("bloqueio"))
        assertEquals(false, r.vm.enviando.value)
    }

    @Test
    fun `desligar esquece os bloqueios da sessao`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.bloquear("a")
        r.vm.desligar()
        r.vm.ligar(pref)
        r.positions.tryEmit(centro)
        r.emit(ServerMessage.Vizinhos(listOf(viz("a"), viz("b"))))
        assertEquals(listOf("a", "b"), r.sessoesVisiveis())
    }
}
