package app.hunter.app.nearby

import app.hunter.model.TrackPoint
import app.hunter.nearby.ClientMessage
import app.hunter.nearby.Distancia
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
class NearbyEncounterViewModelTest {
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

    private fun proposta(id: String = idEnc) =
        Proposta(id, -20.6, -40.5, 2, 4, Ritmo.MODERADO, Distancia.ATE50, Parada.CAFE, true)

    private fun encontroMsg(id: String = idEnc) = ServerMessage.Encontro(
        id, Ponto(-20.6, -40.5), 1, "eu1", listOf(Participante("eu1", null, null, false, null, null, false)),
    )

    private fun Rig.emit(msg: ServerMessage, celula: String = cellCentro) {
        link.eventFlow.tryEmit(NearbyEvent(celula, msg))
    }

    /** Procurando no centro do quadrado, com um vizinho e uma proposta na lista; limpa o que já foi enviado. */
    private fun Rig.procurando() {
        vm.ligar(pref)
        positions.tryEmit(centro)
        emit(ServerMessage.Vizinhos(listOf(viz("a"))))
        emit(ServerMessage.Propostas(listOf(proposta())))
        link.sent.clear()
    }

    @Test
    fun `proporPonto envia proposta so ao quadrado do ponto`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.proporPonto(-20.6, -40.5, 4)
        assertEquals(ClientMessage.Proposta(-20.6, -40.5, 4) to cellCentro, r.link.sent.single())
        assertEquals(EncontroUiState.Aguardando, r.vm.encontro.value)
        // Outra proposta enquanto espera não sai.
        r.vm.proporPonto(-20.7, -40.5, 3)
        assertEquals(1, r.link.sent.size)
    }

    @Test
    fun `proporPonto fora dos quadrados da busca avisa e nao envia`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.proporPonto(10.0, 10.0, 4)
        assertTrue(r.link.sent.isEmpty())
        assertEquals(TEXTO_FORA_DA_AREA, r.vm.aviso.value)
        assertEquals(EncontroUiState.Nenhum, r.vm.encontro.value)
    }

    @Test
    fun `vagas ficam entre 2 e 8`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.proporPonto(-20.6, -40.5, 99)
        assertEquals(ClientMessage.Proposta(-20.6, -40.5, 8), r.link.sent.single().first)
    }

    @Test
    fun `resposta encontro vira encontro ativo do proponente e cancelar vai ao quadrado`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.proporPonto(-20.6, -40.5, 4)
        r.emit(encontroMsg())
        val ativo = r.vm.encontro.value
        assertIs<EncontroUiState.Ativo>(ativo)
        assertTrue(ativo.souProponente)
        assertEquals("eu1", ativo.encontro.eu)
        r.link.sent.clear()
        r.vm.sair()
        assertEquals(ClientMessage.Cancelar to cellCentro, r.link.sent.single())
        assertEquals(EncontroUiState.Encerrado(MotivoFim.CANCELADO), r.vm.encontro.value)
        assertEquals(0, r.link.encDisconnects) // proponente não usa conexão extra
    }

    @Test
    fun `entrar abre uma conexao extra so uma vez e manda entrar quando ela abre`() = runTest {
        val r = rig()
        r.procurando()
        val antes = r.vm.estado.value as NearbyUiState.Procurando
        r.vm.entrar(idEnc)
        r.vm.entrar(idEnc) // toque duplo
        assertEquals(listOf(cellCentro to idEnc), r.link.encConnects)
        assertTrue(r.link.sent.isEmpty()) // nada vai às conexões da busca
        assertTrue(r.link.sentEnc.isEmpty()) // ainda não abriu
        r.link.openedEncFlow.tryEmit(idEnc)
        assertEquals(listOf<ClientMessage>(ClientMessage.Entrar(idEnc)), r.link.sentEnc)
        // A lista de vizinhos não mudou (a proposta em que entrou sai da lista de propostas, só isso).
        val depois = r.vm.estado.value as NearbyUiState.Procurando
        assertEquals(antes.vizinhos, depois.vizinhos)
        assertTrue(depois.propostas.isEmpty())
    }

    @Test
    fun `encontro da conexao extra vira ativo sem duplicar vizinhos`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.entrar(idEnc)
        r.emit(encontroMsg())
        val ativo = r.vm.encontro.value
        assertIs<EncontroUiState.Ativo>(ativo)
        assertEquals(false, ativo.souProponente)
        val st = r.vm.estado.value as NearbyUiState.Procurando
        assertEquals(listOf("a"), st.vizinhos.map { it.sessao })
    }

    @Test
    fun `erro cheio mostra o texto, desfaz a entrada e fecha a conexao extra`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.entrar(idEnc)
        r.emit(ServerMessage.Erro("cheio"))
        assertEquals("Esse encontro já está cheio", r.vm.aviso.value)
        assertEquals(EncontroUiState.Nenhum, r.vm.encontro.value)
        assertEquals(1, r.link.encDisconnects)
        assertEquals(1, (r.vm.estado.value as NearbyUiState.Procurando).vizinhos.size) // lista de vizinhos intacta
        // Pode tentar outra vez.
        r.vm.entrar(idEnc)
        assertEquals(2, r.link.encConnects.size)
    }

    @Test
    fun `erro de espera na proposta mostra os minutos e libera para tentar de novo`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.proporPonto(-20.6, -40.5, 4)
        r.emit(ServerMessage.Erro("espera", 240_001))
        assertEquals("Espere 5 min para propor de novo", r.vm.aviso.value)
        assertEquals(EncontroUiState.Nenhum, r.vm.encontro.value)
        r.vm.limparAviso()
        assertEquals(null, r.vm.aviso.value)
    }

    @Test
    fun `fim fecha o encontro e a conexao extra`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.entrar(idEnc)
        r.emit(encontroMsg())
        r.emit(ServerMessage.Fim(idEnc, MotivoFim.CHEGARAM))
        assertEquals(EncontroUiState.Encerrado(MotivoFim.CHEGARAM), r.vm.encontro.value)
        assertEquals(1, r.link.encDisconnects)
        r.vm.limparFim()
        assertEquals(EncontroUiState.Nenhum, r.vm.encontro.value)
    }

    @Test
    fun `fim ou encontro de outro id e ignorado`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.entrar(idEnc)
        r.emit(encontroMsg())
        r.emit(ServerMessage.Fim("ffffffffffffffff", MotivoFim.EXPIROU))
        r.emit(encontroMsg("ffffffffffffffff"))
        val ativo = r.vm.encontro.value
        assertIs<EncontroUiState.Ativo>(ativo)
        assertEquals(idEnc, ativo.encontro.id)
    }

    @Test
    fun `sair de um encontro em que entrou manda sair pela conexao extra e desconecta`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.entrar(idEnc)
        r.emit(encontroMsg())
        r.vm.sair()
        assertEquals(ClientMessage.Sair, r.link.sentEnc.last())
        assertEquals(1, r.link.encDisconnects)
        assertEquals(EncontroUiState.Encerrado(MotivoFim.SAIU), r.vm.encontro.value)
        // Fim atrasado do servidor depois de sair não reabre nada.
        r.emit(ServerMessage.Fim(idEnc, MotivoFim.SAIU))
        assertEquals(EncontroUiState.Encerrado(MotivoFim.SAIU), r.vm.encontro.value)
    }

    @Test
    fun `em encontro a posicao vai a cada 10 segundos com distancia e tempo ate o ponto`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.proporPonto(-20.6, -40.5, 4)
        r.emit(encontroMsg())
        // A posição entra no quadro assim que o encontro abre.
        val primeira = r.link.sent.map { it.first }.filterIsInstance<ClientMessage.Posicao>().last()
        assertTrue(primeira.distKm!! in 10.0..12.0, "distKm=${primeira.distKm}") // ~11,1 km em reta
        assertEquals(17.0, primeira.etaMin!!, 1.0) // a 40 km/h
        r.link.sent.clear()
        advanceTimeBy(9_000)
        r.positions.tryEmit(centro)
        assertTrue(r.link.sent.isEmpty()) // antes do piso de 10 s
        advanceTimeBy(2_000)
        r.positions.tryEmit(centro)
        val msg = r.link.sent.single().first
        assertIs<ClientMessage.Posicao>(msg)
        assertTrue(msg.etaMin != null && msg.distKm != null)
        assertEquals(null, r.link.sent.single().second) // proponente: pelas conexões da busca
    }

    @Test
    fun `quem entrou manda a posicao com o tempo pela conexao extra`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.entrar(idEnc)
        r.emit(encontroMsg())
        val p = r.link.sentEnc.filterIsInstance<ClientMessage.Posicao>().last()
        assertTrue(p.etaMin != null && p.distKm != null)
    }

    @Test
    fun `sem resposta da proposta desiste com aviso`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.proporPonto(-20.6, -40.5, 4)
        advanceTimeBy(16_000)
        assertEquals(TEXTO_SEM_RESPOSTA, r.vm.aviso.value)
        assertEquals(EncontroUiState.Nenhum, r.vm.encontro.value)
        assertEquals(ClientMessage.Cancelar to cellCentro, r.link.sent.last()) // melhor esforço
    }

    @Test
    fun `quadrado do proponente reabrindo cancela a proposta localmente`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.proporPonto(-20.6, -40.5, 4)
        r.emit(encontroMsg())
        r.link.openedFlow.tryEmit(cellCentro)
        assertEquals(EncontroUiState.Encerrado(MotivoFim.CANCELADO), r.vm.encontro.value)
    }

    @Test
    fun `desligar encerra o encontro`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.entrar(idEnc)
        r.vm.desligar()
        assertEquals(EncontroUiState.Nenhum, r.vm.encontro.value)
        assertEquals(1, r.link.disconnects)
    }
}
