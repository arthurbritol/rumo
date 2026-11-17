package app.hunter.app.nearby

import app.hunter.app.social.ConviteDeEncontro
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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyAmigoViewModelTest {
    private val pref = Preferencia(Ritmo.MODERADO, Distancia.ATE50, Parada.CAFE, 5)
    private val centro = TrackPoint(timeMs = 0, latitude = -20.5, longitude = -40.5)
    private val idEnc = "0123456789abcdef"
    private val cellCentro = "-21:-41"

    private class Rig(val link: FakeLink, val positions: MutableSharedFlow<TrackPoint>, val vm: NearbyViewModel, val convites: MutableList<String>)

    private fun TestScope.rig(resultado: (String) -> ConviteDeEncontro = { ConviteDeEncontro.ENVIADO }): Rig {
        val link = FakeLink()
        val positions = MutableSharedFlow<TrackPoint>(extraBufferCapacity = 8)
        val convites = mutableListOf<String>()
        val vm = NearbyViewModel(
            link = link,
            signedIn = { true },
            positions = { positions },
            clock = { testScheduler.currentTime },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            convidarAmigo = { uid -> convites += uid; resultado(uid) },
        )
        return Rig(link, positions, vm, convites)
    }

    private fun viz(sessao: String) =
        Vizinho(sessao, -20.5, -40.5, null, PreferenciaVista(Ritmo.MODERADO, Distancia.ATE50, Parada.CAFE), false)

    private fun proposta() = Proposta(idEnc, -20.6, -40.5, 2, 4, Ritmo.MODERADO, Distancia.ATE50, Parada.CAFE, true)

    private fun part(s: String) = Participante(s, null, null, false, null, null, false)

    private fun Rig.emit(msg: ServerMessage, celula: String = cellCentro, extra: Boolean = false) {
        link.eventFlow.tryEmit(NearbyEvent(celula, msg, extra))
    }

    private fun Rig.procurando() {
        vm.ligar(pref)
        positions.tryEmit(centro)
        emit(ServerMessage.Vizinhos(listOf(viz("a"))))
        emit(ServerMessage.Propostas(listOf(proposta())))
        link.sent.clear()
    }

    /** Entra num encontro com p2 e p3 e ele acaba: o cartão de fim lista os dois. */
    private fun Rig.encontroAcabado() {
        procurando()
        vm.entrar(idEnc)
        link.openedEncFlow.tryEmit(idEnc)
        emit(ServerMessage.Encontro(idEnc, Ponto(-20.6, -40.5), 1, "eu1", listOf(part("eu1"), part("p2"), part("p3"))), extra = true)
        emit(ServerMessage.Fim(idEnc, MotivoFim.CHEGARAM), extra = true)
        link.sent.clear()
        link.sentEnc.clear()
    }

    private fun acks(r: Rig) = r.link.sent.filter { it.first is ClientMessage.AmigoAck }

    @Test
    fun `amigo_confirmado convida uma vez, avisa e responde o ack pela mesma celula`() = runTest {
        val r = rig()
        r.procurando()
        r.emit(ServerMessage.AmigoConfirmado("u-9"))
        assertEquals(listOf("u-9"), r.convites)
        assertEquals("Convite de amizade enviado", r.vm.aviso.value)
        assertEquals(listOf<Pair<ClientMessage, String?>>(ClientMessage.AmigoAck("u-9") to cellCentro), r.link.sent)
    }

    @Test
    fun `segunda mensagem igual nao convida de novo mas reenvia o ack`() = runTest {
        val r = rig()
        r.procurando()
        r.emit(ServerMessage.AmigoConfirmado("u-9"))
        r.vm.limparAviso()
        r.emit(ServerMessage.AmigoConfirmado("u-9"))
        assertEquals(listOf("u-9"), r.convites)
        assertEquals(2, acks(r).size)
        assertNull(r.vm.aviso.value) // sem novo aviso
    }

    @Test
    fun `a memoria por uid sobrevive a desligar e ligar de novo`() = runTest {
        val r = rig()
        r.procurando()
        r.emit(ServerMessage.AmigoConfirmado("u-9"))
        r.vm.desligar()
        r.procurando()
        r.emit(ServerMessage.AmigoConfirmado("u-9"))
        assertEquals(listOf("u-9"), r.convites)
    }

    @Test
    fun `quem ja e amigo ou esta bloqueado e ignorado, so com ack e sem aviso`() = runTest {
        val r = rig { ConviteDeEncontro.IGNORADO }
        r.procurando()
        r.emit(ServerMessage.AmigoConfirmado("amigo"))
        r.emit(ServerMessage.AmigoConfirmado("bloqueado"))
        assertNull(r.vm.aviso.value)
        assertEquals(listOf<Pair<ClientMessage, String?>>(ClientMessage.AmigoAck("amigo") to cellCentro, ClientMessage.AmigoAck("bloqueado") to cellCentro), r.link.sent)
        // Repetir nao chama o convite outra vez.
        r.emit(ServerMessage.AmigoConfirmado("amigo"))
        assertEquals(listOf("amigo", "bloqueado"), r.convites)
    }

    @Test
    fun `convite que falha nao confirma, nao avisa e nao repete em laco`() = runTest {
        var falha = true
        val r = rig { if (falha) ConviteDeEncontro.FALHOU else ConviteDeEncontro.ENVIADO }
        r.procurando()
        r.emit(ServerMessage.AmigoConfirmado("u-9"))
        assertEquals(listOf("u-9"), r.convites)
        assertTrue(r.link.sent.isEmpty()) // sem ack: o servidor reentrega na proxima conexao
        assertNull(r.vm.aviso.value)
        // Na proxima entrega (nova conexao) tenta de novo e agora conclui.
        falha = false
        r.emit(ServerMessage.AmigoConfirmado("u-9"))
        assertEquals(listOf("u-9", "u-9"), r.convites)
        assertEquals(1, acks(r).size)
        assertEquals("Convite de amizade enviado", r.vm.aviso.value)
    }

    @Test
    fun `excecao no convite conta como falha`() = runTest {
        val link = FakeLink()
        val positions = MutableSharedFlow<TrackPoint>(extraBufferCapacity = 8)
        val vm = NearbyViewModel(
            link = link, signedIn = { true }, positions = { positions },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            convidarAmigo = { error("sem rede") },
        )
        vm.ligar(pref)
        positions.tryEmit(centro)
        link.eventFlow.tryEmit(NearbyEvent(cellCentro, ServerMessage.AmigoConfirmado("u-9"), false))
        assertTrue(link.sent.none { it.first is ClientMessage.AmigoAck })
        assertNull(vm.aviso.value)
    }

    @Test
    fun `ack volta pela conexao extra quando a mensagem veio dela`() = runTest {
        val r = rig()
        r.procurando()
        r.emit(ServerMessage.AmigoConfirmado("u-9"), celula = "qualquer", extra = true)
        assertEquals(listOf<ClientMessage>(ClientMessage.AmigoAck("u-9")), r.link.sentEnc)
        assertTrue(r.link.sent.isEmpty())
    }

    @Test
    fun `quero ser amigo vai pela celula do encontro e vira Marcado so com o ok`() = runTest {
        val r = rig()
        r.encontroAcabado()
        assertEquals(listOf("p2", "p3"), r.vm.fimPessoas.value)
        r.vm.querSerAmigo("p2")
        assertEquals(ClientMessage.Amigo("p2") to cellCentro, r.link.sent.single())
        assertTrue(r.vm.amigosMarcados.value.isEmpty())
        r.emit(ServerMessage.Ok("amigo"))
        assertEquals(setOf("p2"), r.vm.amigosMarcados.value)
        assertEquals(false, r.vm.enviando.value)
        // Marcar de novo a mesma pessoa nao envia nada.
        r.link.sent.clear()
        r.vm.querSerAmigo("p2")
        assertTrue(r.link.sent.isEmpty())
    }

    @Test
    fun `erros de amizade viram mensagem curta e nao marcam`() = runTest {
        val casos = mapOf(
            "inexistente" to "Não foi possível encontrar essa pessoa. Ela pode ter saído",
            "limite" to "Você já marcou muitas pessoas. Tente de novo mais tarde",
            "espera" to "Espere um instante para marcar de novo",
        )
        for ((codigo, texto) in casos) {
            val r = rig()
            r.encontroAcabado()
            r.vm.querSerAmigo("p2")
            r.emit(ServerMessage.Erro(codigo))
            assertEquals(texto, r.vm.aviso.value, codigo)
            assertTrue(r.vm.amigosMarcados.value.isEmpty(), codigo)
            assertEquals(false, r.vm.enviando.value, codigo)
        }
    }

    @Test
    fun `suspenso desliga a busca com a mensagem de suspensao`() = runTest {
        val r = rig()
        r.encontroAcabado()
        r.vm.querSerAmigo("p2")
        r.emit(ServerMessage.Erro("suspenso"))
        assertIs<NearbyUiState.Desligado>(r.vm.estado.value)
        assertNull(r.vm.preferencia.value)
    }

    @Test
    fun `sessao desconhecida nao envia e avisa`() = runTest {
        val r = rig()
        r.procurando()
        r.vm.querSerAmigo("zzz")
        assertTrue(r.link.sent.isEmpty())
        assertEquals("Não foi possível encontrar essa pessoa. Ela pode ter saído", r.vm.aviso.value)
    }
}
