package app.hunter.app.nearby

import androidx.lifecycle.ViewModel
import app.hunter.model.TrackPoint
import app.hunter.nav.LatLon
import app.hunter.nearby.ClientMessage
import app.hunter.nearby.MotivoFim
import app.hunter.nearby.NearbyState
import app.hunter.nearby.Preferencia
import app.hunter.nearby.Proposta
import app.hunter.nearby.ServerMessage
import app.hunter.nearby.Vizinho
import app.hunter.nearby.cellId
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
import kotlinx.coroutines.withTimeoutOrNull

/** O que a tela do Rolê por perto mostra. */
sealed interface NearbyUiState {
    /** Não está procurando. [aviso] explica por que não ligou (ex.: sem conta). */
    data class Desligado(val aviso: String? = null) : NearbyUiState

    /** Ligado, ainda sem conexão ou sem a primeira lista. */
    data object Ligando : NearbyUiState

    /** Procurando; [vizinhos] só têm a posição aproximada e as escolhas, nunca quem são; [propostas] estão abertas por perto. */
    data class Procurando(val vizinhos: List<Vizinho>, val propostas: List<Proposta> = emptyList()) : NearbyUiState

    data class Indisponivel(val mensagem: String) : NearbyUiState
}

/** Onde o usuário está em relação a um encontro (proposta, entrada, ativo ou recém-acabado). */
sealed interface EncontroUiState {
    data object Nenhum : EncontroUiState

    /** Proposta ou entrada enviada, esperando a resposta do servidor. */
    data object Aguardando : EncontroUiState

    /** [encontro] traz o ponto e os participantes (só sessões opacas); `encontro.eu` é a própria sessão. */
    data class Ativo(val encontro: ServerMessage.Encontro, val souProponente: Boolean) : EncontroUiState

    data class Encerrado(val motivo: MotivoFim) : EncontroUiState
}

const val NEARBY_AVISO_ENTRAR = "Entre na sua conta para procurar rolê"
const val NEARBY_AVISO_SERVICO = "Não foi possível manter a busca em segundo plano"
const val NEARBY_AVISO_LOCALIZACAO = "Permita a localização para procurar rolê"

/**
 * Liga e desliga a procura por rolê: conecta os quadrados do raio, anuncia a preferência (`procurar`) e a posição
 * (no ritmo do [PositionThrottle]), junta as listas de vizinhos e fecha tudo ao desligar. Também propõe, entra e
 * acompanha um encontro (um por vez, identificado pelo id).
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
    /** Rota e tempo até o ponto do encontro; sem roteador, linha reta a 40 km/h. */
    private val etaCalculator: EtaCalculator = EtaCalculator { _, _ -> null },
    /** Quanto esperar a resposta de uma proposta ou entrada antes de desistir. */
    private val respostaMs: Long = 15_000,
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _estado = MutableStateFlow<NearbyUiState>(NearbyUiState.Desligado())
    val estado: StateFlow<NearbyUiState> = _estado.asStateFlow()

    private val _preferencia = MutableStateFlow<Preferencia?>(null)

    /** A preferência ativa (com o raio atual); nula quando não está procurando. */
    val preferencia: StateFlow<Preferencia?> = _preferencia.asStateFlow()

    private val _encontro = MutableStateFlow<EncontroUiState>(EncontroUiState.Nenhum)

    /** A proposta ou o encontro do usuário. */
    val encontro: StateFlow<EncontroUiState> = _encontro.asStateFlow()

    private val _aviso = MutableStateFlow<String?>(null)

    /** Erro do servidor em português (ex.: "Esse encontro já está cheio"); [limparAviso] apaga. */
    val aviso: StateFlow<String?> = _aviso.asStateFlow()

    private var encId: String? = null
    private var encCell: String? = null
    private var souProponente = false
    private var usandoExtra = false
    private var propondo = false
    private var aguardoJob: Job? = null

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
    private var openedEncJob: Job? = null
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
        zerarEncontro()
        _aviso.value = null
        _estado.value = NearbyUiState.Ligando
        eventsJob = scope.launch { link.events.collect(::onEvent) }
        openedJob = scope.launch { link.opened.collect(::onOpened) }
        openedEncJob = scope.launch { link.openedEncounter.collect(::onOpenedEncounter) }
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
        // Por último: se o serviço falhar na hora, `parar` roda aqui dentro e já encontra os jobs para cancelar.
        onActive(true)
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
        link.disconnect() // fecha também a conexão extra do encontro: o servidor tira a pessoa dele
        locationJob?.cancel()
        statusJob?.cancel()
        eventsJob?.cancel()
        openedJob?.cancel()
        openedEncJob?.cancel()
        debounceJob?.cancel()
        locationJob = null
        statusJob = null
        eventsJob = null
        openedJob = null
        openedEncJob = null
        debounceJob = null
        pref = null
        _preferencia.value = null
        state = NearbyState() // não há `limpar`: um estado novo descarta as listas antigas
        cells = emptySet()
        lastPoint = null
        needProcurar = false
        conectou = false
        indisponivel = false
        zerarEncontro()
        _aviso.value = null
        onActive(false)
        _estado.value = NearbyUiState.Desligado(aviso)
    }

    private fun onPoint(p: TrackPoint) {
        val atual = pref ?: return
        lastPoint = p
        val novas = cellsInRadius(p.latitude, p.longitude, atual.raioKm.toDouble())
        if (novas != cells) {
            cells = novas
            state = state.semListas() // quadrados novos: listas de quadrados que saíram não valem mais
            // Quem propôs perde a proposta se o quadrado dela sai do raio (a conexão fecha e o servidor cancela).
            val enc = encCell
            if (souProponente && !usandoExtra && state.encontro != null && enc != null && enc !in novas) {
                encId?.let { encerrarLocal(it, MotivoFim.CANCELADO) }
            }
            needProcurar = true
            link.connect(novas)
            refresh()
        }
        publicar(p, atual)
    }

    /** `procurar` + posição quando necessário (conexão nova, raio novo); depois só a posição, no ritmo do throttle. */
    private fun publicar(p: TrackPoint, atual: Preferencia) {
        if (needProcurar) throttle = PositionThrottle()
        // Em encontro a posição vai a cada 10 s (piso do servidor), com a distância e o tempo até o ponto.
        val due = throttle.shouldSend(clock(), p.latitude, p.longitude, state.encontro != null)
        if (needProcurar) {
            needProcurar = false
            link.send(ClientMessage.Procurar(atual))
            enviarPosicao(p)
        } else if (due) {
            enviarPosicao(p)
        }
    }

    private fun posicao(p: TrackPoint, distKm: Double? = null, etaMin: Double? = null) = ClientMessage.Posicao(
        lat = p.latitude,
        lon = p.longitude,
        rumo = p.bearingDeg?.toDouble(),
        vel = p.speedMps?.let { (it * 3.6).coerceAtLeast(0.0) },
        distKm = distKm,
        etaMin = etaMin,
    )

    /**
     * Fora de encontro, a posição vai a todos os quadrados. Em encontro, calcula antes a rota e o tempo até o ponto
     * (o servidor decide sozinho se mostra a posição aproximada ou a real). Quem entrou pela conexão extra manda
     * a posição com o tempo por ela e, aos quadrados da busca, a posição simples.
     */
    private fun enviarPosicao(p: TrackPoint) {
        val ponto = state.encontro?.ponto
        if (ponto == null) {
            link.send(posicao(p))
            return
        }
        val de = LatLon(p.latitude, p.longitude)
        val para = LatLon(ponto.lat, ponto.lon)
        scope.launch {
            val eta = withTimeoutOrNull(ETA_TIMEOUT_MS) { etaCalculator.eta(de, para) } ?: etaCalculator.linhaReta(de, para)
            if (pref == null || state.encontro == null) return@launch
            if (usandoExtra) {
                link.sendEncounter(posicao(p, eta.distKm, eta.etaMin))
                link.send(posicao(p))
            } else {
                link.send(posicao(p, eta.distKm, eta.etaMin))
            }
        }
    }

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
        // Quem propôs e vê o quadrado da proposta reabrir: a conexão antiga caiu e o servidor já cancelou.
        if (souProponente && !usandoExtra && cell == encCell && state.encontro != null) {
            encId?.let { encerrarLocal(it, MotivoFim.CANCELADO) }
            refresh()
        }
        link.send(ClientMessage.Procurar(atual), cell)
        lastPoint?.let { link.send(posicao(it), cell) }
    }

    /** A conexão extra do encontro abriu (ou reabriu): entra nele. */
    private fun onOpenedEncounter(id: String) {
        if (id == encId && usandoExtra) link.sendEncounter(ClientMessage.Entrar(id))
    }

    private fun onEvent(e: NearbyEvent) {
        val atual = pref ?: return
        val m = e.msg
        val doEncontro = usandoExtra && e.celula == encCell // conexão extra do encontro
        if (e.celula !in cells && !doEncontro) return
        when (m) {
            is ServerMessage.Vizinhos, is ServerMessage.Propostas -> {
                if (e.celula !in cells) return // a conexão extra do encontro não alimenta as listas
                conectou = true
                state = NearbyState.reduce(state, m, e.celula)
            }
            is ServerMessage.Encontro -> {
                val esperado = (propondo && encId == null && e.celula == encCell) || m.id == encId
                if (!esperado) return
                conectou = true
                val primeiro = state.encontro == null
                if (encId == null) encId = m.id
                propondo = false
                aguardoJob?.cancel()
                state = NearbyState.reduce(state, m, e.celula)
                _encontro.value = EncontroUiState.Ativo(m, souProponente)
                if (primeiro) lastPoint?.let(::enviarPosicao) // entra no quadro já com distância e tempo
            }
            is ServerMessage.Fim -> {
                if (m.id != encId) return // fim atrasado de um encontro que já acabou aqui
                val extra = usandoExtra
                state = NearbyState.reduce(state, m, e.celula)
                zerarEncontro()
                _encontro.value = EncontroUiState.Encerrado(m.motivo)
                if (extra) link.disconnectEncounter()
            }
            is ServerMessage.Erro -> {
                if (e.celula in cells) conectou = true
                if (m.codigo == "nao_procurando" && e.celula in cells) {
                    // Esta conexão não conhece a procura (quadrado novo, ou a mensagem saiu antes de abrir): refaz só nela.
                    link.send(ClientMessage.Procurar(atual), e.celula)
                    lastPoint?.let { link.send(posicao(it), e.celula) }
                }
                state = NearbyState.reduce(state, m, e.celula)
                onErro(e, m)
            }
        }
        refresh()
    }

    private fun onErro(e: NearbyEvent, m: ServerMessage.Erro) {
        val texto = textoDoErro(m.codigo, m.restanteMs)
        if (propondo && e.celula == encCell && m.codigo in ERROS_DE_PROPOSTA) {
            zerarEncontro()
            _aviso.value = texto ?: textoDoErro("indisponivel", null)
        } else if (usandoExtra && encId != null && state.encontro == null && e.celula == encCell && m.codigo in ERROS_DE_ENTRADA) {
            link.disconnectEncounter()
            zerarEncontro()
            _aviso.value = texto
        } else if (texto != null && !(m.codigo == "inexistente" && encId == null)) {
            _aviso.value = texto
        }
    }

    // ---- encontros ----

    /**
     * Propõe um encontro no ponto marcado (2 a 8 vagas, com quem propõe). A proposta vai só ao quadrado do ponto, que
     * precisa estar entre os da busca. A resposta chega como `encontro` (vira o encontro) ou `erro` (vira [aviso]).
     */
    fun proporPonto(lat: Double, lon: Double, vagas: Int) {
        if (pref == null) return
        if (propondo || encId != null) {
            _aviso.value = textoDoErro("ja_em_encontro", null)
            return
        }
        val cell = cellId(lat, lon)
        if (cell !in cells) {
            _aviso.value = TEXTO_FORA_DA_AREA
            return
        }
        _aviso.value = null
        propondo = true
        encCell = cell
        souProponente = true
        usandoExtra = false
        _encontro.value = EncontroUiState.Aguardando
        link.send(ClientMessage.Proposta(lat, lon, vagas.coerceIn(2, 8)), cell)
        aguardarResposta()
    }

    /**
     * Entra na proposta [id]: abre UMA conexão extra ao quadrado dela e manda `entrar` quando ela abre. Entrar de novo
     * na mesma proposta não abre outra; entrar em outra, já estando em encontro, também não.
     */
    fun entrar(id: String) {
        if (pref == null) return
        if (id == encId) return
        if (propondo || encId != null) {
            _aviso.value = textoDoErro("ja_em_encontro", null)
            return
        }
        val proposta = state.propostas.firstOrNull { it.id == id }
        if (proposta == null) {
            _aviso.value = textoDoErro("inexistente", null)
            return
        }
        val cell = cellId(proposta.lat, proposta.lon)
        _aviso.value = null
        encId = id
        encCell = cell
        souProponente = false
        usandoExtra = true
        _encontro.value = EncontroUiState.Aguardando
        link.connectEncounter(cell, id)
        aguardarResposta()
        refresh() // a proposta some da lista de quem está por perto
    }

    /** Quem propôs cancela; quem entrou sai. O fim aparece na hora e o servidor avisa os outros. */
    fun sair() {
        val id = encId ?: return
        if (state.encontro != null) {
            if (souProponente) encCell?.let { link.send(ClientMessage.Cancelar, it) } else link.sendEncounter(ClientMessage.Sair)
        }
        val extra = usandoExtra
        encerrarLocal(id, if (souProponente) MotivoFim.CANCELADO else MotivoFim.SAIU)
        if (extra) link.disconnectEncounter() // entrega antes o que está na fila (o `sair`)
        refresh()
    }

    fun limparAviso() {
        _aviso.value = null
    }

    /** Fecha o aviso de "o encontro terminou". */
    fun limparFim() {
        state = state.limparFim()
        if (_encontro.value is EncontroUiState.Encerrado) _encontro.value = EncontroUiState.Nenhum
    }

    private fun encerrarLocal(id: String, motivo: MotivoFim) {
        state = state.encerrar(id, motivo)
        zerarEncontro()
        _encontro.value = EncontroUiState.Encerrado(motivo)
    }

    private fun zerarEncontro() {
        aguardoJob?.cancel()
        aguardoJob = null
        encId = null
        encCell = null
        souProponente = false
        usandoExtra = false
        propondo = false
        _encontro.value = EncontroUiState.Nenhum
    }

    private fun aguardarResposta() {
        aguardoJob?.cancel()
        aguardoJob = scope.launch {
            delay(respostaMs)
            if (state.encontro != null) return@launch
            // Sem resposta: se a proposta tiver saído tarde demais, pede para cancelar (melhor esforço).
            if (propondo) encCell?.let { link.send(ClientMessage.Cancelar, it) }
            if (usandoExtra) link.disconnectEncounter()
            zerarEncontro()
            _aviso.value = TEXTO_SEM_RESPOSTA
            refresh()
        }
    }

    private fun refresh() {
        if (pref == null) return
        _estado.value = when {
            indisponivel -> NearbyUiState.Indisponivel(NEARBY_INDISPONIVEL_TEXTO)
            !conectou -> NearbyUiState.Ligando
            else -> NearbyUiState.Procurando(state.vizinhos, state.propostas.filter { it.id != encId })
        }
    }

    private companion object {
        const val ETA_TIMEOUT_MS = 5_000L
        val ERROS_DE_PROPOSTA = setOf("espera", "ja_tem_proposta", "indisponivel", "ponto_invalido", "ja_em_encontro", "mensagem_invalida", "nao_procurando")
        val ERROS_DE_ENTRADA = setOf("cheio", "inexistente", "ja_em_encontro", "espera", "mensagem_invalida")
    }

    override fun onCleared() {
        parar(null)
        scope.cancel()
        onDispose()
    }
}
