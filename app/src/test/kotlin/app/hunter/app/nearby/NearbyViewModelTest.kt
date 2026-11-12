package app.hunter.app.nearby

import app.hunter.model.TrackPoint
import app.hunter.nearby.ClientMessage
import app.hunter.nearby.Distancia
import app.hunter.nearby.Parada
import app.hunter.nearby.Preferencia
import app.hunter.nearby.PreferenciaVista
import app.hunter.nearby.Ritmo
import app.hunter.nearby.ServerMessage
import app.hunter.nearby.Vizinho
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FakeLink : NearbyLink {
    val eventFlow = MutableSharedFlow<NearbyEvent>(extraBufferCapacity = 16)
    val statusFlow = MutableStateFlow(NearbyStatus.DESCONECTADO)
    override val events: SharedFlow<NearbyEvent> = eventFlow
    override val status: StateFlow<NearbyStatus> = statusFlow

    val connects = mutableListOf<Set<String>>()
    val sent = mutableListOf<Pair<ClientMessage, String?>>()
    var disconnects = 0

    override fun connect(cells: Set<String>) { connects += cells }
    override fun send(msg: ClientMessage, cell: String?) { sent += msg to cell }
    override fun disconnect() { disconnects++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyViewModelTest {
    private val pref = Preferencia(Ritmo.MODERADO, Distancia.ATE50, Parada.CAFE, 5)
    private val centro = TrackPoint(timeMs = 0, latitude = -20.5, longitude = -40.5) // meio de um quadrado
    private val quina = TrackPoint(timeMs = 0, latitude = -21.0, longitude = -41.0) // quatro quadrados
    private val perto = TrackPoint(timeMs = 0, latitude = -20.5, longitude = -40.95) // 1 quadrado a 5 km, 2 a 20 km

    private fun viz(sessao: String, combina: Boolean = false, lat: Double = -20.5) = Vizinho(
        sessao, lat, -40.5, null, PreferenciaVista(Ritmo.MODERADO, Distancia.ATE50, Parada.CAFE), combina,
    )

    private class Rig(val link: FakeLink, val positions: MutableSharedFlow<TrackPoint>, val ativo: MutableList<Boolean>, val vm: NearbyViewModel)

    private fun TestScope.rig(
        signedIn: Boolean = true,
        stops: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1),
    ): Rig {
        val link = FakeLink()
        val positions = MutableSharedFlow<TrackPoint>(extraBufferCapacity = 8)
        val ativo = mutableListOf<Boolean>()
        val vm = NearbyViewModel(
            link = link,
            signedIn = { signedIn },
            positions = { positions },
            onActive = { ativo += it },
            stopRequests = stops,
            clock = { testScheduler.currentTime },
            debounceMs = 5_000,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        return Rig(link, positions, ativo, vm)
    }

    @Test
    fun `ligar conecta os quadrados do raio e manda procurar e depois posicao`() = runTest {
        val r = rig()
        r.vm.ligar(pref)
        assertEquals(NearbyUiState.Ligando, r.vm.estado.value)
        assertEquals(listOf(true), r.ativo)
        r.positions.tryEmit(centro)
        assertEquals(listOf(setOf("-21:-41")), r.link.connects)
        assertEquals(ClientMessage.Procurar(pref), r.link.sent[0].first)
        val pos = r.link.sent[1].first
        assertIs<ClientMessage.Posicao>(pos)
        assertEquals(-20.5, pos.lat)
        assertEquals(2, r.link.sent.size)
        assertEquals(null, r.link.sent[0].second) // todas as conexões
    }

    @Test
    fun `posicoes seguidas respeitam o ritmo de 30 segundos`() = runTest {
        val r = rig()
        r.vm.ligar(pref)
        r.positions.tryEmit(centro)
        advanceTimeBy(12_000)
        r.positions.tryEmit(centro)
        assertEquals(2, r.link.sent.size) // nada de novo antes de 30 s
        advanceTimeBy(20_000)
        r.positions.tryEmit(centro)
        assertEquals(3, r.link.sent.size)
        assertIs<ClientMessage.Posicao>(r.link.sent[2].first)
    }

    @Test
    fun `sem login nao conecta e mostra o aviso`() = runTest {
        val r = rig(signedIn = false)
        r.vm.ligar(pref)
        r.positions.tryEmit(centro)
        assertEquals(NearbyUiState.Desligado(NEARBY_AVISO_ENTRAR), r.vm.estado.value)
        assertTrue(r.link.connects.isEmpty())
        assertTrue(r.link.sent.isEmpty())
        assertTrue(r.ativo.isEmpty())
    }

    @Test
    fun `desligar manda parar, desconecta, desliga o servico e zera o estado`() = runTest {
        val r = rig()
        r.vm.ligar(pref)
        r.positions.tryEmit(centro)
        r.link.eventFlow.tryEmit(NearbyEvent("-21:-41", ServerMessage.Vizinhos(listOf(viz("a")))))
        assertEquals(NearbyUiState.Procurando(listOf(viz("a"))), r.vm.estado.value)

        r.vm.desligar()
        assertEquals(ClientMessage.Parar, r.link.sent.last().first)
        assertEquals(1, r.link.disconnects)
        assertEquals(listOf(true, false), r.ativo)
        assertEquals(NearbyUiState.Desligado(), r.vm.estado.value)

        // Posições depois de desligar não fazem nada.
        val enviados = r.link.sent.size
        r.positions.tryEmit(centro)
        assertEquals(enviados, r.link.sent.size)

        // Ligar de novo começa do zero: a lista antiga não volta (um erro não troca as listas).
        r.vm.ligar(pref)
        r.positions.tryEmit(centro)
        r.link.eventFlow.tryEmit(NearbyEvent("-21:-41", ServerMessage.Erro("x")))
        assertEquals(NearbyUiState.Procurando(emptyList()), r.vm.estado.value)
    }

    @Test
    fun `parar da notificacao desliga`() = runTest {
        val stops = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val r = rig(stops = stops)
        r.vm.ligar(pref)
        r.positions.tryEmit(centro)
        stops.tryEmit(Unit)
        assertEquals(NearbyUiState.Desligado(), r.vm.estado.value)
        assertEquals(1, r.link.disconnects)
    }

    @Test
    fun `mudarRaio reconecta com o novo conjunto de quadrados e manda procurar de novo`() = runTest {
        val r = rig()
        r.vm.ligar(pref)
        r.positions.tryEmit(perto)
        assertEquals(listOf(setOf("-21:-41")), r.link.connects)
        r.link.sent.clear()

        r.vm.mudarRaio(20)
        assertEquals(2, r.link.connects.size)
        assertEquals(setOf("-21:-41", "-21:-42"), r.link.connects.last())
        assertEquals(ClientMessage.Procurar(pref.copy(raioKm = 20)), r.link.sent[0].first)
        assertIs<ClientMessage.Posicao>(r.link.sent[1].first)
    }

    @Test
    fun `mudarRaio sem procurar nao faz nada e o raio fica entre 5 e 20`() = runTest {
        val r = rig()
        r.vm.mudarRaio(10)
        assertTrue(r.link.connects.isEmpty())
        r.vm.ligar(pref)
        r.positions.tryEmit(centro)
        r.link.sent.clear()
        r.vm.mudarRaio(99)
        assertEquals(ClientMessage.Procurar(pref.copy(raioKm = 20)), r.link.sent[0].first)
    }

    @Test
    fun `indisponivel so aparece depois do tempo de espera e some ao reconectar`() = runTest {
        val r = rig()
        r.vm.ligar(pref)
        r.positions.tryEmit(centro)
        r.link.statusFlow.value = NearbyStatus.INDISPONIVEL
        assertEquals(NearbyUiState.Ligando, r.vm.estado.value)

        // O cliente alterna conectando/indisponível a cada tentativa: o relógio não reinicia.
        advanceTimeBy(2_000)
        r.link.statusFlow.value = NearbyStatus.CONECTANDO
        advanceTimeBy(2_000)
        r.link.statusFlow.value = NearbyStatus.INDISPONIVEL
        advanceTimeBy(999)
        assertEquals(NearbyUiState.Ligando, r.vm.estado.value)
        advanceTimeBy(2)
        assertEquals(NearbyUiState.Indisponivel("Rolê por perto indisponível agora"), r.vm.estado.value)

        // Continua mostrando enquanto tenta de novo (sem piscar).
        r.link.statusFlow.value = NearbyStatus.CONECTANDO
        assertEquals(NearbyUiState.Indisponivel("Rolê por perto indisponível agora"), r.vm.estado.value)

        r.link.statusFlow.value = NearbyStatus.CONECTADO
        assertEquals(NearbyUiState.Procurando(emptyList()), r.vm.estado.value)
    }

    @Test
    fun `indisponivel curto que se resolve nao chega a aparecer`() = runTest {
        val r = rig()
        r.vm.ligar(pref)
        r.positions.tryEmit(centro)
        r.link.statusFlow.value = NearbyStatus.CONECTADO
        r.link.statusFlow.value = NearbyStatus.INDISPONIVEL
        advanceTimeBy(3_000)
        r.link.statusFlow.value = NearbyStatus.CONECTADO
        advanceTimeBy(10_000)
        assertEquals(NearbyUiState.Procurando(emptyList()), r.vm.estado.value)
    }

    @Test
    fun `conexao aberta anuncia de novo a procura e a posicao`() = runTest {
        val r = rig()
        r.vm.ligar(pref)
        r.positions.tryEmit(centro)
        r.link.sent.clear()
        r.link.statusFlow.value = NearbyStatus.CONECTADO
        assertEquals(ClientMessage.Procurar(pref), r.link.sent[0].first)
        assertIs<ClientMessage.Posicao>(r.link.sent[1].first)
    }

    @Test
    fun `a mesma sessao vinda de dois quadrados aparece uma vez`() = runTest {
        val r = rig()
        r.vm.ligar(pref.copy(raioKm = 5))
        r.positions.tryEmit(quina)
        assertEquals(4, r.link.connects.last().size)
        r.link.eventFlow.tryEmit(NearbyEvent("-21:-41", ServerMessage.Vizinhos(listOf(viz("s1", lat = -20.99), viz("s2")))))
        r.link.eventFlow.tryEmit(NearbyEvent("-22:-41", ServerMessage.Vizinhos(listOf(viz("s1", lat = -20.995)))))
        val st = r.vm.estado.value
        assertIs<NearbyUiState.Procurando>(st)
        assertEquals(listOf("s2", "s1").sorted(), st.vizinhos.map { it.sessao }.sorted())
        assertEquals(2, st.vizinhos.size)
    }

    @Test
    fun `erro nao_procurando refaz a procura so no quadrado que reclamou`() = runTest {
        val r = rig()
        r.vm.ligar(pref)
        r.positions.tryEmit(quina)
        r.link.sent.clear()
        r.link.eventFlow.tryEmit(NearbyEvent("-22:-42", ServerMessage.Erro("nao_procurando")))
        assertEquals(ClientMessage.Procurar(pref) to "-22:-42", r.link.sent[0])
        assertEquals("-22:-42", r.link.sent[1].second)
    }

    @Test
    fun `eventos de quadrado que nao esta conectado sao ignorados`() = runTest {
        val r = rig()
        r.vm.ligar(pref)
        r.positions.tryEmit(centro)
        r.link.eventFlow.tryEmit(NearbyEvent("0:0", ServerMessage.Vizinhos(listOf(viz("x")))))
        assertEquals(NearbyUiState.Ligando, r.vm.estado.value)
    }
}
