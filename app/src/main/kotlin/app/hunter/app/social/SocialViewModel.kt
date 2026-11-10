package app.hunter.app.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.hunter.social.Friend
import app.hunter.social.Invite
import app.hunter.social.Member
import app.hunter.social.MemberState
import app.hunter.social.Refusal
import app.hunter.social.Role
import app.hunter.social.SearchHit
import app.hunter.social.SearchKey
import app.hunter.social.SocialEngine
import app.hunter.social.SocialResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId

/**
 * O que a aba Amigos mostra.
 *
 * - [signedIn]: o social pode rodar (há conta logada, o Firebase existe e não há exclusão pendente); falso mostra o
 *   convite para entrar.
 * - [searchPromptNeeded]: perguntar uma vez "Quer aparecer na busca por nome?" (responder com `answerSearchPrompt`).
 * - [friends], [invitesReceived] (sem os de bloqueados), [invitesSent], [roles]: listas já formatadas.
 * - [searchResults]/[searching]: resultado da última busca por nome e se uma está em andamento.
 * - [offline]: alguma leitura ou ação falhou por rede/permissão; as listas mostram o último dado carregado.
 * - [message]: aviso curto em pt-BR para exibir (recusa de regra, falha, valor inválido); `clearMessage` o limpa.
 * - [busy]: uma ação (convidar, aceitar, criar rolê...) está em andamento.
 */
data class SocialUi(
    val signedIn: Boolean = false,
    val searchPromptNeeded: Boolean = false,
    val friends: List<FriendRow> = emptyList(),
    val invitesReceived: List<InviteRow> = emptyList(),
    val invitesSent: List<InviteRow> = emptyList(),
    val searchResults: List<SearchHit> = emptyList(),
    val searching: Boolean = false,
    val roles: List<RoleRow> = emptyList(),
    val offline: Boolean = false,
    val message: String? = null,
    val busy: Boolean = false,
)

const val OFFLINE_MESSAGE = "Sem conexão. Mostrando o que já foi carregado."
const val ACTION_FAILED_MESSAGE = "Não deu para concluir agora. Confira a internet e tente de novo."
const val BUDGET_NOT_ACCEPTED_MESSAGE = "Aceite o convite do rolê antes de informar o orçamento."
const val INVALID_BUDGET_MESSAGE = "Informe um valor válido para o orçamento."

/**
 * Estado da aba Amigos. Combina os `observe*` do store com [friendRows] e demais mapeamentos puros.
 *
 * Nenhuma exceção chega à interface: qualquer falha (IOException, FirebaseFirestoreException, permissão negada)
 * vira `offline = true` e uma mensagem; só o cancelamento é relançado. Um observador que falha tenta de novo
 * depois de 2 s, 5 s e 15 s e então espera [refresh]. [scope] existe para os testes (padrão: `viewModelScope`).
 */
class SocialViewModel(
    private val backend: SocialBackend,
    scope: CoroutineScope? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Lido a cada cálculo (o fuso pode mudar com o app aberto). */
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val retryDelaysMs: List<Long> = listOf(2_000L, 5_000L, 15_000L),
    private val searchDebounceMs: Long = 300L,
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope

    private val _state = MutableStateFlow(SocialUi(signedIn = backend.uid.value != null))
    val state: StateFlow<SocialUi> = _state.asStateFlow()

    private val _detail = MutableStateFlow<RoleDetailUi?>(null)

    /** Rolê aberto na tela de detalhe (nulo: nenhum aberto, ou ainda carregando os participantes). */
    val roleDetail: StateFlow<RoleDetailUi?> = _detail.asStateFlow()
    private var detailJob: Job? = null
    private var detailRoleId: String? = null
    private var rawMembers: List<Member>? = null

    private var session: SocialSession? = null
    private var searchJob: Job? = null

    /** Observadores que estão falhando agora; com algum, o estado é offline. */
    private val failing = mutableSetOf<String>()
    private var actionFailed = false

    /** Sobe a cada [refresh]; quem espera para tentar de novo acorda. */
    private val refreshTick = MutableStateFlow(0)

    private val names = mutableMapOf<String, String>()
    private var friendNames: Map<String, String> = emptyMap()
    private var rawFriends: List<Friend> = emptyList()
    private var rawRoles: List<Role> = emptyList()
    private var roleStates: Map<String, MemberState?> = emptyMap()

    /** Quem não tem nome público (não pesquisável): não pergunta de novo a cada snapshot. */
    private val noName = mutableSetOf<String>()

    /** Remetentes dos convites já tratados (evita reler bloqueios a cada snapshot igual). */
    private var lastInviteUids: Set<String>? = null

    init {
        this.scope.launch { backend.uid.collectLatest { onUid(it) } }
    }

    // ------------------------------------------------------------------ sessão e observadores

    private suspend fun onUid(uid: String?) {
        failing.clear()
        actionFailed = false
        names.clear()
        noName.clear()
        lastInviteUids = null
        friendNames = emptyMap()
        rawFriends = emptyList()
        rawRoles = emptyList()
        roleStates = emptyMap()
        searchJob?.cancel()
        closeRoleDetail()
        val s = uid?.let { backend.session(it) }
        session = s
        if (s == null) {
            _state.value = SocialUi()
            return
        }
        _state.value = SocialUi(
            signedIn = true,
            searchPromptNeeded = SearchPrompt.shouldAsk(backend.searchPrefs.asked(), signedIn = true),
        )
        backend.publishSoon() // abrir a aba publica perfil e atividade (respeitando o intervalo mínimo)
        coroutineScope {
            launch { observe("amigos", { s.store.observeFriends(s.uid) }) { onFriends(it) } }
            launch { observe("convites", { s.store.observeInvitesReceived(s.uid) }) { onInvites(s, it) } }
            launch { observe("roles", { s.store.observeRoles(s.uid) }) { onRoles(s, it) } }
            launch { loadSent(s) }
            launch { closeExpiredRoles(s) }
            awaitCancellation()
        }
    }

    private suspend fun <T> observe(name: String, source: () -> Flow<T>, onValue: suspend (T) -> Unit) {
        var attempt = 0
        while (true) {
            try {
                source().collect {
                    onValue(it)
                    attempt = 0
                    markFailing(name, false)
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                markFailing(name, true)
                val seen = refreshTick.value
                if (attempt < retryDelaysMs.size) {
                    // Espera o intervalo, ou menos se o usuário pedir para atualizar.
                    withTimeoutOrNull(retryDelaysMs[attempt]) { refreshTick.first { it != seen } }
                    attempt++
                } else {
                    refreshTick.first { it != seen }
                    attempt = 0
                }
            }
        }
    }

    private fun markFailing(name: String, failed: Boolean) {
        val changed = if (failed) failing.add(name) else failing.remove(name)
        if (changed) syncOffline()
    }

    private fun syncOffline() = _state.update { cur ->
        val off = failing.isNotEmpty() || actionFailed
        cur.copy(
            offline = off,
            message = when {
                off && cur.message == null -> OFFLINE_MESSAGE
                !off && (cur.message == OFFLINE_MESSAGE || cur.message == ACTION_FAILED_MESSAGE) -> null
                else -> cur.message
            },
        )
    }

    private fun onFriends(friends: List<Friend>) {
        rawFriends = friends
        friendNames = friends.associate { it.profile.uid to it.profile.displayName }
        rebuildFriends()
        rebuildRoles()
    }

    private fun rebuildFriends() = _state.update { it.copy(friends = friendRows(rawFriends, clock(), zone())) }

    /** Volta do app para a frente: recalcula a semana na tela (ela pode ter virado) e publica respeitando o intervalo. */
    fun onForeground() {
        if (session == null) return
        recalcWeek()
        backend.publishSoon()
    }

    /** Refaz as listas com o relógio de agora: a semana (ou a validade de um rolê) pode ter virado com a tela aberta. */
    fun recalcWeek() {
        if (session == null) return
        rebuildFriends()
        rebuildRoles()
    }

    /** O criador encerra os rolês vencidos ao carregar (o TTL do Firestore não apaga os membros). Falha em silêncio. */
    private suspend fun closeExpiredRoles(s: SocialSession) {
        try {
            s.engine.closeExpiredRoles()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // tenta de novo no próximo carregamento
        }
    }

    private suspend fun onInvites(s: SocialSession, emitted: List<Invite>) {
        val emittedUids = emitted.map { it.fromUid }.toSet()
        if (emittedUids == lastInviteUids) return // mesmo conjunto de remetentes: nada novo para ler
        val visible = s.engine.visibleInvites() // também recusa os de bloqueados
        val uids = visible.map { it.fromUid }
        resolveNames(s, uids)
        _state.update { it.copy(invitesReceived = inviteRows(uids, nameMap())) }
        lastInviteUids = emittedUids
    }

    /**
     * Meu estado em cada rolê. Quem já aceitou não volta a ser convidado, então só leio o estado de quem ainda não
     * está no mapa como aceito. Falha ao ler um rolê (sem rede, permissão) não derruba os outros: fica o último
     * estado conhecido.
     */
    private suspend fun onRoles(s: SocialSession, roles: List<Role>) {
        val states = HashMap<String, MemberState?>()
        for (r in roles) {
            val known = roleStates[r.id]
            states[r.id] = if (known == MemberState.ACCEPTED) {
                known
            } else {
                try {
                    s.store.member(r.id, s.uid)?.state
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    known
                }
            }
        }
        roleStates = states
        rawRoles = roles
        rebuildRoles()
    }

    private fun rebuildRoles() {
        val me = session?.uid ?: return
        _state.update { it.copy(roles = roleRows(rawRoles, me, roleStates, { uid -> friendNames[uid] }, clock())) }
        rebuildDetail()
    }

    private fun rebuildDetail() {
        val id = detailRoleId ?: return
        val me = session?.uid ?: return
        val members = rawMembers
        val row = _state.value.roles.firstOrNull { it.id == id }
        _detail.value = if (members == null || row == null) null else roleDetailUi(row, members, me)
    }

    /**
     * Abre o rolê [id]: observa os participantes e, se sou o criador, renova a validade (agora + 7 dias).
     * Só vale para rolê que já aceitei (as regras só deixam listar os membros a quem aceitou).
     */
    fun openRole(id: String) {
        val s = session ?: return
        detailJob?.cancel()
        detailRoleId = id
        rawMembers = null
        _detail.value = null
        detailJob = scope.launch {
            if (rawRoles.firstOrNull { it.id == id }?.creatorUid == s.uid) {
                try {
                    s.engine.renewRole(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // sem rede: abre mesmo assim
                }
            }
            observe(DETAIL_OBSERVER, { s.store.observeMembers(id) }) {
                rawMembers = it
                rebuildDetail()
            }
        }
    }

    fun closeRoleDetail() {
        detailJob?.cancel()
        detailJob = null
        detailRoleId = null
        rawMembers = null
        _detail.value = null
        if (failing.remove(DETAIL_OBSERVER)) syncOffline()
    }

    /** O criador tira [uid] do rolê. */
    fun removeParticipant(roleId: String, uid: String) = act { s ->
        val role = rawRoles.firstOrNull { it.id == roleId }
        if (role == null || role.creatorUid != s.uid) return@act SocialResult.Refused(Refusal.NOT_CREATOR)
        s.store.leaveRole(roleId, uid)
        null
    }

    private fun nameMap(): Map<String, String> = names + friendNames

    /** Nomes que ainda não conheço, pela entrada de busca (quem não aparece na busca fica sem nome). */
    private suspend fun resolveNames(s: SocialSession, uids: List<String>) {
        for (uid in uids) {
            if (uid in friendNames || uid in names || uid in noName) continue
            try {
                val hit = s.store.searchEntry(uid)
                if (hit != null) names[uid] = hit.displayName else noName += uid
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // sem nome: a linha mostra "Alguém"
            }
        }
    }

    /** Convites enviados (leitura pontual: o store não os observa). Falha em silêncio e mantém a lista atual. */
    private suspend fun loadSent(s: SocialSession) {
        try {
            val uids = s.store.invitesSent(s.uid).map { it.toUid }
            resolveNames(s, uids)
            _state.update { it.copy(invitesSent = inviteRows(uids, nameMap())) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // mantém o que já havia
        }
    }

    // ------------------------------------------------------------------ ações

    /** Tenta de novo o que falhou: acorda os observadores parados e recarrega os enviados. */
    fun refresh() {
        val s = session ?: return
        actionFailed = false
        syncOffline()
        refreshTick.update { it + 1 }
        scope.launch { loadSent(s) }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /** Busca por nome: espera 300 ms sem digitar, mínimo de 3 letras (menos que isso limpa os resultados na hora). */
    fun search(text: String) {
        val s = session ?: return
        searchJob?.cancel()
        if (SearchKey.normalize(text).length < SocialEngine.MIN_SEARCH_CHARS) {
            _state.update { it.copy(searchResults = emptyList(), searching = false) }
            return
        }
        searchJob = scope.launch {
            delay(searchDebounceMs)
            _state.update { it.copy(searching = true) }
            try {
                val hits = s.engine.search(text)
                actionFailed = false
                _state.update { it.copy(searchResults = hits, searching = false) }
                syncOffline()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failAction()
                _state.update { it.copy(searching = false) }
            }
        }
    }

    fun invite(uid: String) = act { s ->
        s.engine.invite(uid).also { if (it == SocialResult.Ok) _state.update { u -> u.copy(searchResults = u.searchResults.filter { h -> h.uid != uid }) } }
    }

    fun acceptInvite(from: String) = act { it.engine.acceptInvite(from) }

    fun declineInvite(from: String) = act { s ->
        s.engine.declineInvite(from)
        null
    }

    fun cancelInvite(to: String) = act { s ->
        s.store.cancelInvite(s.uid, to)
        null
    }

    fun unfriend(uid: String) = act { s ->
        s.store.unfriend(s.uid, uid)
        null
    }

    fun block(uid: String) = act { it.engine.block(uid) }

    /** Cria o rolê com [friendUids]; [myFigures] são meu orçamento e custo por km iniciais. */
    fun createRole(name: String, friendUids: List<String>, myFigures: Member, onCreated: () -> Unit = {}) = act(onCreated) { s ->
        s.engine.createRole(name, friendUids, myFigures).also { if (it == SocialResult.Ok) backend.publishSoon(force = true) }
    }

    /** Aceita o convite do rolê e já publica meu custo por km nele. */
    fun acceptRole(id: String) = act { s ->
        s.engine.acceptRole(id).also { if (it == SocialResult.Ok) backend.publishSoon(force = true) }
    }

    fun leaveRole(id: String, onDone: () -> Unit = {}) = act(onDone, alsoOnNull = true) { s ->
        s.engine.leaveRole(id)
        null
    }

    fun closeRole(id: String, onDone: () -> Unit = {}) = act(onDone) { it.engine.closeRole(id) }

    /** Define meu orçamento no rolê, mantendo o custo por km que já estava publicado. Valor negativo ou inválido é recusado. */
    fun setBudget(roleId: String, budget: Double?) {
        if (budget != null && (!budget.isFinite() || budget < 0.0)) {
            _state.update { it.copy(message = INVALID_BUDGET_MESSAGE) }
            return
        }
        act { s ->
            val role = rawRoles.firstOrNull { it.id == roleId }
            if (role != null && role.expiresAtMs <= clock()) return@act SocialResult.Refused(Refusal.ROLE_EXPIRED)
            val me = s.store.member(roleId, s.uid)
            if (me == null || me.state != MemberState.ACCEPTED) {
                _state.update { it.copy(message = BUDGET_NOT_ACCEPTED_MESSAGE) }
            } else {
                s.store.setMemberFigures(roleId, s.uid, budget, me.costPerKm)
            }
            null
        }
    }

    /** Resposta da pergunta única da busca: guarda a escolha e publica já (sem esperar o intervalo mínimo). */
    fun answerSearchPrompt(searchable: Boolean) {
        backend.searchPrefs.set(searchable)
        _state.update { it.copy(searchPromptNeeded = false) }
        backend.publishSoon(force = true)
    }

    /**
     * Roda uma ação do social: uma por vez (toques repetidos são ignorados), com [SocialUi.busy]. Recusa das regras
     * vira mensagem; qualquer outra falha vira offline + mensagem. Depois do sucesso recarrega os convites enviados.
     */
    private fun act(
        onSuccess: (() -> Unit)? = null,
        alsoOnNull: Boolean = false,
        block: suspend (SocialSession) -> SocialResult?,
    ) {
        val s = session ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        scope.launch {
            try {
                val result = block(s)
                actionFailed = false
                if (result is SocialResult.Refused) _state.update { it.copy(message = refusalMessage(result.reason)) }
                if (result == SocialResult.Ok || (alsoOnNull && result == null)) onSuccess?.invoke()
                syncOffline()
                loadSent(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failAction()
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun failAction() {
        actionFailed = true
        _state.update { it.copy(offline = true, message = ACTION_FAILED_MESSAGE) }
    }
}

private const val DETAIL_OBSERVER = "membros"

/** Uso: `viewModel<SocialViewModel>(factory = socialViewModelFactory(app.account.social))`. */
fun socialViewModelFactory(backend: SocialBackend): ViewModelProvider.Factory = viewModelFactory {
    initializer { SocialViewModel(backend) }
}
