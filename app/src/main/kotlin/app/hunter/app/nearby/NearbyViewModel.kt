package app.hunter.app.nearby

import androidx.lifecycle.ViewModel
import app.hunter.model.Geo
import app.hunter.model.TrackPoint
import app.hunter.app.social.ConviteDeEncontro
import app.hunter.nav.LatLon
import app.hunter.nearby.ClientMessage
import app.hunter.nearby.MotivoDenuncia
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
    /** Convida o uid pelo Social quando as duas pessoas marcaram; a tela liga isto ao `SocialViewModel` (sem ele, falha e o servidor reenvia depois). */
    var convidarAmigo: suspend (String) -> ConviteDeEncontro = { ConviteDeEncontro.FALHOU },
    /** Emite falso quando o tipo do veículo ativo muda (troca de veículo, até vinda da sincronização): o rolê desliga, porque cada tipo tem a sua Celula. */
    rolePermitido: Flow<Boolean> = emptyFlow(),
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

    private val _enviando = MutableStateFlow(false)

    /** Um bloqueio ou denúncia foi enviado e ainda não teve resposta: os botões ficam desabilitados. */
    val enviando: StateFlow<Boolean> = _enviando.asStateFlow()

    private val _fimPessoas = MutableStateFlow<List<String>>(emptyList())

    /** Sessões (opacas) de quem estava no encontro que acabou, para bloquear ou denunciar no cartão de fim. */
    val fimPessoas: StateFlow<List<String>> = _fimPessoas.asStateFlow()

    private val _amigosMarcados = MutableStateFlow<Set<String>>(emptySet())

    /** Sessões que o usuário marcou com "Quero ser amigo" (o servidor já confirmou o recebimento); o botão vira "Marcado". */
    val amigosMarcados: StateFlow<Set<String>> = _amigosMarcados.asStateFlow()

    /** Quem o usuário bloqueou nesta busca: some das listas mesmo se o servidor mandar a sessão de novo. Só em memória. */
    private val bloqueadas = mutableSetOf<String>()

    /** Uids já tratados (convite enviado ou ignorado) e os em andamento, nesta execução: um convite por uid. */
    private val convidados = mutableSetOf<String>()
    private val convidando = mutableMapOf<String, MutableList<NearbyEvent>>()
    private var pendente: Pendente? = null
    private var pendenteJob: Job? = null
    private var fimCell: String? = null
    private var fecharExtraAoConcluir = false

    private var encId: String? = null
    private var encCell: String? = null
    private var souProponente = false
    private var usandoExtra = false
    private var propondo = false
    private var aguardoJob: Job? = null
    private var reentradaJob: Job? = null
    private var reentradas = 0
    private var entradaTimeoutJob: Job? = null

    /** `entrar` enviado e ainda sem um `encontro` que o confirme: a conexão extra não leva posição nem trata nao_procurando como fatal. */
    private var entradaPendente = false

    // Rota até o ponto: uma em voo, reaproveitada por 2 min ou 500 m, com recuo depois de falha.
    private var etaJob: Job? = null
    private var rotaEta: Eta? = null
    private var rotaPonto: TrackPoint? = null
    private var rotaEm = 0L
    private var etaRecuoAte = 0L

    private var pref: Preferencia? = null
    private var state = NearbyState()
    private var cells: Set<String> = emptySet()
    private var lastPoint: TrackPoint? = null
    private var throttle = PositionThrottle(floorMs = PISO_ENVIO_MS)
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
        scope.launch { rolePermitido.collect { if (!it) desligar() } }
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
        throttle = PositionThrottle(floorMs = PISO_ENVIO_MS)
        needProcurar = true
        conectou = false
        indisponivel = false
        zerarEncontro()
        zerarModeracao()
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
        zerarModeracao()
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
        if (needProcurar) throttle = PositionThrottle(floorMs = PISO_ENVIO_MS)
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
     * Fora de encontro, a posição vai a todos os quadrados. Em encontro leva também a distância e o tempo até o ponto:
     * a rota do roteador quando há uma recente (2 min ou 500 m), senão a linha reta, que é instantânea. O envio nunca
     * espera o roteador; a rota nova é pedida em segundo plano (uma por vez, com recuo de 60 s se falhar).
     */
    private fun enviarPosicao(p: TrackPoint) {
        val ponto = state.encontro?.ponto
        if (ponto == null) {
            link.send(posicao(p))
            return
        }
        val de = LatLon(p.latitude, p.longitude)
        val para = LatLon(ponto.lat, ponto.lon)
        val agora = clock()
        val rota = rotaEta
        val ref = rotaPonto
        val valida = rota != null && ref != null && agora - rotaEm < ROTA_VALIDA_MS &&
            Geo.distanceM(ref.latitude, ref.longitude, p.latitude, p.longitude) <= ROTA_VALIDA_M
        if (!valida) pedirRota(p, de, para, agora)
        val eta = if (valida) rota!! else etaCalculator.linhaReta(de, para)
        if (usandoExtra) {
            // Quem entrou: o tempo vai só pela conexão do encontro; aos quadrados da busca, a posição simples.
            if (!entradaPendente) link.sendEncounter(posicao(p, eta.distKm, eta.etaMin)) // sem sessão no servidor, só daria erro
            link.send(posicao(p))
        } else {
            // Quem propôs: o tempo vai só ao quadrado do ponto; aos outros, a posição simples.
            val cell = encCell
            if (cell == null) {
                link.send(posicao(p, eta.distKm, eta.etaMin))
            } else {
                link.send(posicao(p, eta.distKm, eta.etaMin), cell)
                for (c in cells) if (c != cell) link.send(posicao(p), c)
            }
        }
    }

    private fun pedirRota(p: TrackPoint, de: LatLon, para: LatLon, agora: Long) {
        if (etaJob?.isActive == true || agora < etaRecuoAte) return
        etaJob = scope.launch {
            val r = etaCalculator.etaRota(de, para)
            if (pref == null || state.encontro == null) return@launch
            if (r == null) {
                etaRecuoAte = clock() + ETA_RECUO_MS
            } else {
                rotaEta = r
                rotaPonto = p
                rotaEm = clock()
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
        if (id == encId && usandoExtra) {
            entradaPendente = true
            link.sendEncounter(ClientMessage.Entrar(id))
            armarTimeoutDaEntrada(id)
        }
    }

    /** Sem `encontro` em [ENTRADA_TIMEOUT_MS] depois de mandar `entrar`: reenvia (até o limite) e, esgotado, encerra. */
    private fun armarTimeoutDaEntrada(id: String) {
        entradaTimeoutJob?.cancel()
        entradaTimeoutJob = scope.launch {
            delay(ENTRADA_TIMEOUT_MS)
            if (encId == id && entradaPendente && state.encontro != null) reentrar()
        }
    }

    private fun onEvent(e: NearbyEvent) {
        val atual = pref ?: return
        val m = e.msg
        // Conta suspensa por denúncias: vale a qualquer momento e desliga a busca.
        if (m is ServerMessage.Erro && m.codigo == "suspenso") {
            parar(TEXTO_SUSPENSO)
            return
        }
        if (e.celula !in cells && !e.extra) return
        when (m) {
            is ServerMessage.Vizinhos, is ServerMessage.Propostas -> {
                if (e.extra || e.celula !in cells) return // a conexão extra do encontro não alimenta as listas
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
                setEnc(EncontroUiState.Ativo(m.semBloqueadas(), souProponente))
                reentradas = 0
                entradaPendente = false
                entradaTimeoutJob?.cancel()
                // Entra no quadro já com distância e tempo, mas pelo mesmo limitador das posições (piso do servidor).
                if (primeiro) lastPoint?.let { if (throttle.shouldSend(clock(), it.latitude, it.longitude, true)) enviarPosicao(it) }
            }
            is ServerMessage.Fim -> {
                if (m.id != encId) return // fim atrasado de um encontro que já acabou aqui
                val extra = usandoExtra
                guardarPessoasDoFim()
                state = NearbyState.reduce(state, m, e.celula)
                zerarEncontro()
                setEnc(EncontroUiState.Encerrado(m.motivo))
                if (extra) fecharConexaoExtra()
            }
            is ServerMessage.Ok -> {
                val p = pendente
                if (p != null && p.ref == m.ref && mesmoCanal(e, p)) {
                    concluir()
                    if (p.ref == REF_DENUNCIA) _aviso.value = TEXTO_DENUNCIA_ENVIADA
                    if (p.ref == REF_AMIGO) _amigosMarcados.value = _amigosMarcados.value + p.sessao
                }
            }
            is ServerMessage.AmigoConfirmado -> aoAmigoConfirmado(m.uid, e)
            is ServerMessage.Erro -> {
                val p = pendente
                if (p != null && m.codigo in ERROS_DE_MODERACAO && mesmoCanal(e, p) && !(propondo && !e.extra && e.celula == encCell)) {
                    erroDeModeracao(p, m)
                    refresh()
                    return
                }
                if (!e.extra && e.celula in cells) conectou = true
                if (m.codigo == "nao_procurando" && !e.extra && e.celula in cells) {
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
        if (e.extra && usandoExtra && encId != null) {
            if (state.encontro != null && entradaPendente) {
                // Qualquer erro com a reentrada pendente: tenta de novo (até 3 vezes) e, esgotadas, encerra aqui.
                reentrar()
            } else if (state.encontro != null) {
                // Reconexão da conexão extra: o `entrar` reenviado pode esbarrar na sessão antiga que o servidor ainda não soltou.
                when (m.codigo) {
                    "ja_em_encontro" -> reentrar()
                    "inexistente", "cheio" -> sairSemServidor()
                    "nao_procurando" -> sairSemServidor()
                    else -> if (texto != null) _aviso.value = texto
                }
            } else if (m.codigo in ERROS_DE_ENTRADA || m.codigo == "nao_procurando") {
                link.disconnectEncounter()
                zerarEncontro()
                _aviso.value = if (m.codigo == "espera") TEXTO_ESPERA_ENTRAR else texto ?: textoDoErro("outro", null)
            }
            return
        }
        if (propondo && !e.extra && e.celula == encCell && m.codigo in ERROS_DE_PROPOSTA) {
            zerarEncontro()
            _aviso.value = texto ?: textoDoErro("indisponivel", null)
        } else if (texto != null && !(m.codigo == "inexistente" && encId == null)) {
            _aviso.value = texto
        }
    }

    /** Até 3 novas tentativas de `entrar` (a cada ~3 s); esgotadas, o encontro acaba aqui com "Você saiu do encontro". */
    private fun reentrar() {
        val id = encId ?: return
        if (reentradas >= MAX_REENTRADAS) {
            sairSemServidor()
            return
        }
        reentradas++
        entradaPendente = true
        reentradaJob?.cancel()
        reentradaJob = scope.launch {
            delay(REENTRADA_MS)
            if (encId == id && usandoExtra && state.encontro != null) {
                link.sendEncounter(ClientMessage.Entrar(id))
                armarTimeoutDaEntrada(id)
            }
        }
    }

    /** O servidor não nos tem mais no encontro: encerra aqui com cartão de fim e fecha a conexão extra. */
    private fun sairSemServidor() {
        val id = encId ?: return
        encerrarLocal(id, MotivoFim.SAIU)
        fecharConexaoExtra()
        refresh()
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
        setEnc(EncontroUiState.Aguardando)
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
        setEnc(EncontroUiState.Aguardando)
        entradaPendente = true
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
        if (extra) fecharConexaoExtra() // entrega antes o que está na fila (o `sair`)
        refresh()
    }

    fun limparAviso() {
        _aviso.value = null
    }

    /** Fecha o aviso de "o encontro terminou". */
    fun limparFim() {
        state = state.limparFim()
        fimCell = null
        _fimPessoas.value = emptyList()
        if (_encontro.value is EncontroUiState.Encerrado) setEnc(EncontroUiState.Nenhum)
    }

    private fun encerrarLocal(id: String, motivo: MotivoFim) {
        guardarPessoasDoFim()
        state = state.encerrar(id, motivo)
        zerarEncontro()
        setEnc(EncontroUiState.Encerrado(motivo))
    }

    private fun setEnc(v: EncontroUiState) {
        _encontro.value = v
        link.setEncounterActive(v is EncontroUiState.Aguardando || v is EncontroUiState.Ativo)
    }

    private fun zerarEncontro() {
        reentradaJob?.cancel()
        reentradaJob = null
        reentradas = 0
        entradaPendente = false
        entradaTimeoutJob?.cancel()
        entradaTimeoutJob = null
        etaJob?.cancel()
        etaJob = null
        rotaEta = null
        rotaPonto = null
        etaRecuoAte = 0L
        aguardoJob?.cancel()
        aguardoJob = null
        encId = null
        encCell = null
        souProponente = false
        usandoExtra = false
        propondo = false
        setEnc(EncontroUiState.Nenhum)
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

    // ---- bloquear e denunciar ----

    /** Por onde a mensagem sai: a conexão extra do encontro ([extra]) ou a do quadrado [cell]. */
    private data class Destino(val cell: String?, val extra: Boolean)

    private class Pendente(val msg: ClientMessage, val ref: String, val sessao: String, val destino: Destino, val tentou: Boolean)

    /**
     * Bloqueia [sessao] (só o id opaco vai ao servidor). A moto some na hora, sem esperar a resposta, e continua escondida
     * enquanto durar a busca. Se o servidor recusar por algo que não seja "a pessoa já não existe", ela volta para poder tentar de novo.
     */
    fun bloquear(sessao: String) = moderar(ClientMessage.Bloquear(sessao), REF_BLOQUEIO, sessao)

    fun denunciar(sessao: String, motivo: MotivoDenuncia) = moderar(ClientMessage.Denunciar(sessao, motivo), REF_DENUNCIA, sessao)

    /** "Quero ser amigo" de [sessao]: vai pela conexão do quadrado do encontro; "Marcado" só depois do `ok` do servidor. */
    fun querSerAmigo(sessao: String) {
        if (sessao in _amigosMarcados.value) return
        moderar(ClientMessage.Amigo(sessao), REF_AMIGO, sessao)
    }

    /**
     * As duas pessoas marcaram: convida uma vez por uid (memória desta execução) e responde `amigo_ack` pela mesma
     * conexão de onde veio. Repetição (o servidor reenvia sem ack) só reenvia o ack. Se o convite falha, nada é
     * confirmado nem repetido: o servidor volta a entregar na próxima conexão.
     */
    private fun aoAmigoConfirmado(uid: String, e: NearbyEvent) {
        if (uid in convidados) {
            enviarAck(uid, e)
            return
        }
        convidando[uid]?.let { it += e; return } // em andamento: o ack sai, por esta conexão também, quando terminar
        val origens = mutableListOf(e)
        convidando[uid] = origens
        scope.launch {
            val r = try {
                convidarAmigo(uid)
            } catch (c: CancellationException) {
                throw c
            } catch (x: Exception) {
                ConviteDeEncontro.FALHOU
            } finally {
                convidando.remove(uid)
            }
            if (r == ConviteDeEncontro.FALHOU) return@launch
            convidados += uid // mesmo que a busca tenha desligado no meio: o convite já foi feito
            if (pref == null) return@launch // a conexão já foi fechada: sem ack (o servidor reentrega e só o ack sai)
            origens.forEach { enviarAck(uid, it) }
            when (r) {
                ConviteDeEncontro.ENVIADO -> _aviso.value = TEXTO_CONVITE_ENVIADO
                ConviteDeEncontro.AMIGOS_AGORA -> _aviso.value = TEXTO_AMIGOS_AGORA
                else -> Unit
            }
        }
    }

    private fun enviarAck(uid: String, e: NearbyEvent) {
        val ack = ClientMessage.AmigoAck(uid)
        if (e.extra) link.sendEncounter(ack) else link.send(ack, e.celula)
    }

    private fun moderar(msg: ClientMessage, ref: String, sessao: String) {
        if (pref == null || pendente != null) return
        val destino = if (ref == REF_AMIGO) destinoDoEncontro(sessao) else destinoDe(sessao)
        if (destino == null) {
            _aviso.value = textoDaModeracao("inexistente", null, ref)
            return
        }
        if (!destino.extra && destino.cell !in cells) {
            // Sem conexão com o quadrado: o pedido nem sairia; avisa já em vez de esperar o tempo limite.
            _aviso.value = textoDaModeracao("indisponivel", null, ref)
            return
        }
        _aviso.value = null
        if (ref == REF_BLOQUEIO) {
            bloqueadas += sessao
            atualizarAtivo()
            refresh()
        }
        val p = Pendente(msg, ref, sessao, destino, tentou = false)
        pendente = p
        _enviando.value = true
        enviar(p)
        armarTimeoutDaModeracao()
    }

    /** Onde a sessão vive: o encontro (conexão extra de quem entrou, quadrado do ponto para quem propôs), a lista de vizinhos ou o encontro que acabou. */
    private fun destinoDe(sessao: String): Destino? {
        val enc = state.encontro
        if (enc != null && sessao != enc.eu && enc.participantes.any { it.sessao == sessao }) {
            return if (usandoExtra) Destino(null, true) else encCell?.let { Destino(it, false) }
        }
        state.celulaDoVizinho(sessao)?.let { return Destino(it, false) }
        if (sessao in _fimPessoas.value) return fimCell?.let { Destino(it, false) }
        return null
    }

    /**
     * `amigo` só vale na Celula do encontro (é lá que ficam os pares de 24 h): nunca no quadrado onde a pessoa aparece
     * como vizinha. Só vale para quem esteve no encontro (em andamento ou que acabou).
     */
    private fun destinoDoEncontro(sessao: String): Destino? {
        val enc = state.encontro
        if (enc != null && sessao != enc.eu && enc.participantes.any { it.sessao == sessao }) {
            return if (usandoExtra) Destino(null, true) else encCell?.let { Destino(it, false) }
        }
        if (sessao in _fimPessoas.value) return fimCell?.let { Destino(it, false) }
        return null
    }

    private fun enviar(p: Pendente) {
        if (p.destino.extra) link.sendEncounter(p.msg) else link.send(p.msg, p.destino.cell)
    }

    private fun mesmoCanal(e: NearbyEvent, p: Pendente): Boolean =
        if (p.destino.extra) e.extra else !e.extra && e.celula == p.destino.cell

    private fun armarTimeoutDaModeracao() {
        pendenteJob?.cancel()
        pendenteJob = scope.launch {
            delay(respostaMs)
            val p = pendente ?: return@launch
            concluir()
            desfazerOcultar(p)
            _aviso.value = TEXTO_SEM_RESPOSTA
            refresh()
        }
    }

    private fun concluir() {
        pendenteJob?.cancel()
        pendenteJob = null
        pendente = null
        _enviando.value = false
        if (fecharExtraAoConcluir) {
            fecharExtraAoConcluir = false
            // Só fecha se não houver outro encontro usando a conexão extra nesse meio tempo.
            if (!usandoExtra && encId == null) link.disconnectEncounter()
        }
    }

    /** Fecha a conexão extra do encontro; se um bloqueio/denúncia dela ainda espera resposta, fecha só depois dela. */
    private fun fecharConexaoExtra() {
        if (pendente?.destino?.extra == true) fecharExtraAoConcluir = true else link.disconnectEncounter()
    }

    private fun erroDeModeracao(p: Pendente, m: ServerMessage.Erro) {
        if (m.codigo == "indisponivel" && !p.tentou) {
            // Uma nova tentativa, depois do intervalo de 2 s que o servidor exige entre pedidos.
            val de = Pendente(p.msg, p.ref, p.sessao, p.destino, tentou = true)
            pendente = de
            pendenteJob?.cancel()
            pendenteJob = scope.launch {
                delay(REPETIR_MODERACAO_MS)
                if (pendente !== de) return@launch
                enviar(de)
                armarTimeoutDaModeracao()
            }
            return
        }
        concluir()
        if (m.codigo != "inexistente") desfazerOcultar(p)
        _aviso.value = textoDaModeracao(m.codigo, m.restanteMs, p.ref)
    }

    private fun desfazerOcultar(p: Pendente) {
        if (p.ref == REF_BLOQUEIO && bloqueadas.remove(p.sessao)) atualizarAtivo()
    }

    private fun ServerMessage.Encontro.semBloqueadas() = copy(participantes = participantes.filter { it.sessao !in bloqueadas })

    private fun atualizarAtivo() {
        val atual = state.encontro ?: return
        val ativo = _encontro.value as? EncontroUiState.Ativo ?: return
        _encontro.value = ativo.copy(encontro = atual.semBloqueadas())
    }

    /** Antes de o encontro acabar aqui: guarda quem estava nele e o quadrado, para bloquear ou denunciar no cartão de fim. */
    private fun guardarPessoasDoFim() {
        val enc = state.encontro ?: return
        fimCell = encCell
        _fimPessoas.value = enc.participantes.map { it.sessao }.filter { it != enc.eu && it !in bloqueadas }
    }

    private fun zerarModeracao() {
        fecharExtraAoConcluir = false
        concluir()
        bloqueadas.clear()
        _amigosMarcados.value = emptySet()
        fimCell = null
        _fimPessoas.value = emptyList()
    }

    private fun refresh() {
        if (pref == null) return
        _estado.value = when {
            indisponivel -> NearbyUiState.Indisponivel(NEARBY_INDISPONIVEL_TEXTO)
            !conectou -> NearbyUiState.Ligando
            else -> NearbyUiState.Procurando(state.vizinhos.filter { it.sessao !in bloqueadas }, state.propostas.filter { state.encontro == null || it.id != encId })
        }
    }

    private companion object {
        const val PISO_ENVIO_MS = 11_000L // o servidor descarta posição antes de 10 s: 1 s de folga
        const val ROTA_VALIDA_MS = 120_000L
        const val ROTA_VALIDA_M = 500.0
        const val ETA_RECUO_MS = 60_000L
        const val REENTRADA_MS = 3_000L
        const val MAX_REENTRADAS = 3
        const val ENTRADA_TIMEOUT_MS = 15_000L
        val ERROS_DE_PROPOSTA = setOf("espera", "ja_tem_proposta", "indisponivel", "ponto_invalido", "ja_em_encontro", "mensagem_invalida", "nao_procurando")
        val ERROS_DE_MODERACAO = setOf("inexistente", "indisponivel", "limite", "espera", "sessao_propria", "mensagem_invalida")
        const val REPETIR_MODERACAO_MS = 2_500L
        val ERROS_DE_ENTRADA = setOf("cheio", "inexistente", "ja_em_encontro", "espera", "mensagem_invalida")
    }

    override fun onCleared() {
        parar(null)
        scope.cancel()
        onDispose()
    }
}
