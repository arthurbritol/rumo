package app.hunter.app.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.hunter.social.Friend
import app.hunter.social.Member
import app.hunter.social.MemberState
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
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val retryDelaysMs: List<Long> = listOf(2_000L, 5_000L, 15_000L),
    private val searchDebounceMs: Long = 300L,
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope

    private val _state = MutableStateFlow(SocialUi(signedIn = backend.uid.value != null))
    val state: StateFlow<SocialUi> = _state.asStateFlow()

    private var session: SocialSession? = null
    private var searchJob: Job? = null

    /** Observadores que estão falhando agora; com algum, o estado é offline. */
    private val failing = mutableSetOf<String>()
    private var actionFailed = false

    /** Sobe a cada [refresh]; quem espera para tentar de novo acorda. */
    private val refreshTick = MutableStateFlow(0)

    private val names = mutableMapOf<String, String>()
    private var friendNames: Map<String, String> = emptyMap()
    private var rawRoles: List<Role> = emptyList()
    private var roleStates: Map<String, MemberState?> = emptyMap()

    init {
        this.scope.launch { backend.uid.collectLatest { onUid(it) } }
    }

    // ------------------------------------------------------------------ sessão e observadores

    private suspend fun onUid(uid: String?) {
        failing.clear()
        actionFailed = false
        names.clear()
        friendNames = emptyMap()
        rawRoles = emptyList()
        roleStates = emptyMap()
        searchJob?.cancel()
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
            launch { observe("convites", { s.store.observeInvitesReceived(s.uid) }) { onInvites(s) } }
            launch { observe("roles", { s.store.observeRoles(s.uid) }) { onRoles(s, it) } }
            launch { loadSent(s) }
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
        friendNames = friends.associate { it.profile.uid to it.profile.displayName }
        _state.update { it.copy(friends = friendRows(friends, clock(), zone)) }
        rebuildRoles()
    }

    private suspend fun onInvites(s: SocialSession) {
        val visible = s.engine.visibleInvites() // também recusa os de bloqueados
        val uids = visible.map { it.fromUid }
        resolveNames(s, uids)
        _state.update { it.copy(invitesReceived = inviteRows(uids, nameMap())) }
    }

    private suspend fun onRoles(s: SocialSession, roles: List<Role>) {
        roleStates = roles.associate { it.id to s.store.member(it.id, s.uid)?.state }
        rawRoles = roles
        rebuildRoles()
    }

    private fun rebuildRoles() {
        val me = session?.uid ?: return
        _state.update { it.copy(roles = roleRows(rawRoles, me, roleStates, { uid -> friendNames[uid] }, clock())) }
    }

    private fun nameMap(): Map<String, String> = names + friendNames

    /** Nomes que ainda não conheço, pela entrada de busca (quem não aparece na busca fica sem nome). */
    private suspend fun resolveNames(s: SocialSession, uids: List<String>) {
        for (uid in uids) {
            if (uid in friendNames || uid in names) continue
            try {
                s.store.searchEntry(uid)?.let { names[uid] = it.displayName }
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
    fun createRole(name: String, friendUids: List<String>, myFigures: Member) = act { s ->
        s.engine.createRole(name, friendUids, myFigures).also { if (it == SocialResult.Ok) backend.publishSoon(force = true) }
    }

    /** Aceita o convite do rolê e já publica meu custo por km nele. */
    fun acceptRole(id: String) = act { s ->
        s.engine.acceptRole(id).also { if (it == SocialResult.Ok) backend.publishSoon(force = true) }
    }

    fun leaveRole(id: String) = act { s ->
        s.engine.leaveRole(id)
        null
    }

    fun closeRole(id: String) = act { it.engine.closeRole(id) }

    /** Define meu orçamento no rolê, mantendo o custo por km que já estava publicado. Valor negativo ou inválido é recusado. */
    fun setBudget(roleId: String, budget: Double?) {
        if (budget != null && (!budget.isFinite() || budget < 0.0)) {
            _state.update { it.copy(message = INVALID_BUDGET_MESSAGE) }
            return
        }
        act { s ->
            val me = s.store.member(roleId, s.uid)
            if (me != null) s.store.setMemberFigures(roleId, s.uid, budget, me.costPerKm)
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
    private fun act(block: suspend (SocialSession) -> SocialResult?) {
        val s = session ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        scope.launch {
            try {
                val result = block(s)
                actionFailed = false
                _state.update { it.copy(message = (result as? SocialResult.Refused)?.let { r -> refusalMessage(r.reason) }) }
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

/** Uso: `viewModel<SocialViewModel>(factory = socialViewModelFactory(app.account.social))`. */
fun socialViewModelFactory(backend: SocialBackend): ViewModelProvider.Factory = viewModelFactory {
    initializer { SocialViewModel(backend) }
}
