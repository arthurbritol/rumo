package app.hunter.app.nearby

import androidx.lifecycle.ViewModel
import app.hunter.model.TrackPoint
import app.hunter.nearby.ClientMessage
import app.hunter.nearby.NearbyState
import app.hunter.nearby.Preferencia
import app.hunter.nearby.ServerMessage
import app.hunter.nearby.Vizinho
import app.hunter.nearby.cellsInRadius
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/** O que a tela do Rolê por perto mostra. */
sealed interface NearbyUiState {
    /** Não está procurando. [aviso] explica por que não ligou (ex.: sem conta). */
    data class Desligado(val aviso: String? = null) : NearbyUiState

    /** Ligado, ainda sem conexão ou sem a primeira lista. */
    data object Ligando : NearbyUiState

    /** Procurando; [vizinhos] só têm a posição aproximada e as escolhas, nunca quem são. */
    data class Procurando(val vizinhos: List<Vizinho>) : NearbyUiState

    data class Indisponivel(val mensagem: String) : NearbyUiState
}

const val NEARBY_AVISO_ENTRAR = "Entre na sua conta para procurar rolê"
const val NEARBY_AVISO_SERVICO = "Não foi possível manter a busca em segundo plano"
const val NEARBY_AVISO_LOCALIZACAO = "Permita a localização para procurar rolê"

/**
 * Liga e desliga a procura por rolê: conecta os quadrados do raio, anuncia a preferência (`procurar`) e a posição
 * (no ritmo do [PositionThrottle]), junta as listas de vizinhos e fecha tudo ao desligar.
 *
 * Tudo roda em [dispatcher] (a thread principal em produção), então o estado não precisa de trava.
 * [positions] é a fonte de GPS (só coletada enquanto procura); [onActive] liga/desliga o serviço em primeiro
 * plano; [stopRequests] é o "Parar" da notificação. A posição e o token nunca são registrados em log.
 */
class NearbyViewModel(
    private val link: NearbyLink,
    private val signedIn: () -> Boolean,
    private val positions: () -> Flow<TrackPoint>,
    private val onActive: (Boolean) -> Unit = {},
    stopRequests: Flow<Unit> = emptyFlow(),
    /** O serviço em primeiro plano não conseguiu subir: a busca não se sustentaria em segundo plano. */
    startFailures: Flow<Unit> = emptyFlow(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Quanto "indisponível" precisa durar para aparecer (o cliente alterna conectando/indisponível ao tentar de novo). */
    private val debounceMs: Long = 5_000,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val onDispose: () -> Unit = {},
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _estado = MutableStateFlow<NearbyUiState>(NearbyUiState.Desligado())
    val estado: StateFlow<NearbyUiState> = _estado.asStateFlow()

    private val _preferencia = MutableStateFlow<Preferencia?>(null)

    /** A preferência ativa (com o raio atual); nula quando não está procurando. */
    val preferencia: StateFlow<Preferencia?> = _preferencia.asStateFlow()

    private var pref: Preferencia? = null
    private var state = NearbyState()
    private var cells: Set<String> = emptySet()
    private var lastPoint: TrackPoint? = null
    private var throttle = PositionThrottle()
    private var needProcurar = false
    private var conectou = false
    private var indisponivel = false

    private var locationJob: Job? = null
    private var statusJob: Job? = null
    private var eventsJob: Job? = null
    private var openedJob: Job? = null
    private var debounceJob: Job? = null

    init {
        scope.launch { stopRequests.collect { desligar() } }
        scope.launch { startFailures.collect { parar(NEARBY_AVISO_SERVICO) } }
    }

    fun ligar(p: Preferencia) {
        if (pref != null) return
        if (!signedIn()) {
            _estado.value = NearbyUiState.Desligado(NEARBY_AVISO_ENTRAR)
            return
        }
        pref = p
        _preferencia.value = p
        state = NearbyState()
        cells = emptySet()
        lastPoint = null
        throttle = PositionThrottle()
        needProcurar = true
        conectou = false
        indisponivel = false
        _estado.value = NearbyUiState.Ligando
        onActive(true)
        eventsJob = scope.launch { link.events.collect(::onEvent) }
        openedJob = scope.launch { link.opened.collect(::onOpened) }
        statusJob = scope.launch { link.status.collect(::onStatus) }
        locationJob = scope.launch {
            try {
                positions().collect(::onPoint)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                parar(NEARBY_AVISO_LOCALIZACAO)
            }
        }
    }

    fun desligar() = parar(null)

    /** Muda o raio (5 a 20 km): reconecta os quadrados que o novo círculo toca e anuncia a preferência de novo. */
    fun mudarRaio(km: Int) {
        val atual = pref ?: return
        val novo = atual.copy(raioKm = km.coerceIn(5, 20))
        if (novo == atual) return
        pref = novo
        _preferencia.value = novo
        needProcurar = true
        lastPoint?.let(::onPoint)
    }

    private fun parar(aviso: String?) {
        if (pref == null) {
            _estado.value = NearbyUiState.Desligado(aviso)
            return
        }
        link.send(ClientMessage.Parar)
        link.disconnect()
        locationJob?.cancel()
        statusJob?.cancel()
        eventsJob?.cancel()
        openedJob?.cancel()
        debounceJob?.cancel()
        locationJob = null
        statusJob = null
        eventsJob = null
        openedJob = null
        debounceJob = null
        pref = null
        _preferencia.value = null
        state = NearbyState() // não há `limpar`: um estado novo descarta as listas antigas
        cells = emptySet()
        lastPoint = null
        needProcurar = false
        conectou = false
        indisponivel = false
        onActive(false)
        _estado.value = NearbyUiState.Desligado(aviso)
    }

    private fun onPoint(p: TrackPoint) {
        val atual = pref ?: return
        lastPoint = p
        val novas = cellsInRadius(p.latitude, p.longitude, atual.raioKm.toDouble())
        if (novas != cells) {
            cells = novas
            state = NearbyState() // quadrados novos: listas de quadrados que saíram não valem mais
            needProcurar = true
            link.connect(novas)
            refresh()
        }
        publicar(p, atual)
    }

    /** `procurar` + posição quando necessário (conexão nova, raio novo); depois só a posição, no ritmo do throttle. */
    private fun publicar(p: TrackPoint, atual: Preferencia) {
        if (needProcurar) throttle = PositionThrottle()
        val due = throttle.shouldSend(clock(), p.latitude, p.longitude, false)
        if (needProcurar) {
            needProcurar = false
            link.send(ClientMessage.Procurar(atual))
            link.send(posicao(p))
        } else if (due) {
            link.send(posicao(p))
        }
    }

    private fun posicao(p: TrackPoint) = ClientMessage.Posicao(
        lat = p.latitude,
        lon = p.longitude,
        rumo = p.bearingDeg?.toDouble(),
        vel = p.speedMps?.let { (it * 3.6).coerceAtLeast(0.0) },
        distKm = null,
        etaMin = null,
    )

    private fun onStatus(s: NearbyStatus) {
        when (s) {
            NearbyStatus.CONECTADO -> {
                debounceJob?.cancel()
                debounceJob = null
                indisponivel = false
                conectou = true
            }
            NearbyStatus.INDISPONIVEL -> if (!indisponivel && debounceJob == null) {
                debounceJob = scope.launch {
                    delay(debounceMs)
                    indisponivel = true
                    refresh()
                }
            }
            // CONECTANDO/DESCONECTADO não apagam a mensagem nem o relógio: sem piscar durante as novas tentativas.
            else -> Unit
        }
        refresh()
    }

    /** Conexão de um quadrado (re)aberta: o servidor não lembra de quem caiu, então anuncia só nela. */
    private fun onOpened(cell: String) {
        val atual = pref ?: return
        if (cell !in cells) return
        link.send(ClientMessage.Procurar(atual), cell)
        lastPoint?.let { link.send(posicao(it), cell) }
    }

    private fun onEvent(e: NearbyEvent) {
        val atual = pref ?: return
        if (e.celula !in cells) return
        conectou = true
        val m = e.msg
        if (m is ServerMessage.Erro && m.codigo == "nao_procurando") {
            // Esta conexão não conhece a procura (quadrado novo, ou a mensagem saiu antes de abrir): refaz só nela.
            link.send(ClientMessage.Procurar(atual), e.celula)
            lastPoint?.let { link.send(posicao(it), e.celula) }
        }
        state = NearbyState.reduce(state, m, e.celula)
        refresh()
    }

    private fun refresh() {
        if (pref == null) return
        _estado.value = when {
            indisponivel -> NearbyUiState.Indisponivel(NEARBY_INDISPONIVEL_TEXTO)
            !conectou -> NearbyUiState.Ligando
            else -> NearbyUiState.Procurando(state.vizinhos)
        }
    }

    override fun onCleared() {
        parar(null)
        scope.cancel()
        onDispose()
    }
}
