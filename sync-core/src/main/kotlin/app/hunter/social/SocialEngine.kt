package app.hunter.social

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Regras sociais de [me] (convites, busca, bloqueio e rolês) sobre um [SocialStore], sem Android.
 *
 * Os métodos que alteram dados são serializados por um [Mutex], para que toques repetidos
 * (por exemplo, dois "convidar" seguidos) não gerem operações duplicadas.
 */
class SocialEngine(
    private val store: SocialStore,
    private val me: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGen: () -> String = { UUID.randomUUID().toString() },
) {
    companion object {
        const val MIN_SEARCH_CHARS = 3
        const val SEARCH_LIMIT = 20
        const val FRIEND_LIMIT = 50
        const val MAX_ROLE_NAME = 60

        /** Máximo de amigos convidados (10 pessoas com o criador). */
        const val MAX_ROLE_FRIENDS = 9
        const val ROLE_TTL_MS = 7L * 24 * 3600 * 1000
    }

    private val mutex = Mutex()

    /**
     * Busca por nome: texto normalizado por [SearchKey] (minúsculas, sem acentos), mínimo de 3 letras; sem eu, bloqueados e amigos; até 20.
     * Pede ao store hits suficientes para sobrarem 20 mesmo que todos os amigos e bloqueados apareçam.
     */
    suspend fun search(text: String): List<SearchHit> {
        val q = SearchKey.normalize(text)
        if (q.length < MIN_SEARCH_CHARS) return emptyList()
        val blocked = store.blocked(me)
        val friends = store.friendUids(me)
        return store.search(q, SEARCH_LIMIT + friends.size + blocked.size + 1)
            .filter { it.uid != me && it.uid !in blocked && it.uid !in friends }
            .take(SEARCH_LIMIT)
    }

    /** Convida [to]. Convite cruzado aceita o recebido; repetido devolve Ok sem duplicar. */
    suspend fun invite(to: String): SocialResult = mutex.withLock {
        if (to == me) return SocialResult.Refused(Refusal.SELF)
        if (to in store.blocked(me)) return SocialResult.Refused(Refusal.BLOCKED_BY_ME)
        val friends = store.friendUids(me)
        if (to in friends) return SocialResult.Refused(Refusal.ALREADY_FRIENDS)
        if (friends.size >= FRIEND_LIMIT) return SocialResult.Refused(Refusal.FRIEND_LIMIT)
        if (store.invitesReceived(me).any { it.fromUid == to }) {
            store.acceptInvite(to, me)
            // Convite meu para a mesma pessoa não deve ficar pendurado (melhor esforço).
            runCatching { store.cancelInvite(me, to) }
            return SocialResult.Ok
        }
        if (store.invitesSent(me).none { it.toUid == to }) store.sendInvite(me, to)
        SocialResult.Ok
    }

    /** Aceita o convite recebido de [from]; recusa se o convite sumiu (INVITE_GONE) ou se já tenho 50 amigos. */
    suspend fun acceptInvite(from: String): SocialResult = mutex.withLock {
        if (store.invitesReceived(me).none { it.fromUid == from }) return SocialResult.Refused(Refusal.INVITE_GONE)
        if (store.friendUids(me).size >= FRIEND_LIMIT) return SocialResult.Refused(Refusal.FRIEND_LIMIT)
        store.acceptInvite(from, me)
        SocialResult.Ok
    }

    /** Recusa o convite recebido de [from]. */
    suspend fun declineInvite(from: String) = mutex.withLock { store.declineInvite(from, me) }

    /** Convites recebidos sem os de bloqueados; esses também são recusados para não se acumularem. */
    suspend fun visibleInvites(): List<Invite> = mutex.withLock {
        val blocked = store.blocked(me)
        val (hidden, visible) = store.invitesReceived(me).partition { it.fromUid in blocked }
        hidden.forEach { store.declineInvite(it.fromUid, me) }
        visible
    }

    /** Bloqueia [other] (apaga amizade e convites entre os dois, no store). Bloquear a si mesmo é recusado. */
    suspend fun block(other: String): SocialResult = mutex.withLock {
        if (other == me) return SocialResult.Refused(Refusal.SELF)
        store.block(me, other)
        SocialResult.Ok
    }

    /** Cria um rolê com [friendUids] (todos precisam ser meus amigos); expira em 7 dias. Nome vazio ou longo: NAME_INVALID. */
    suspend fun createRole(name: String, friendUids: List<String>, myFigures: Member): SocialResult = mutex.withLock {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_ROLE_NAME) return SocialResult.Refused(Refusal.NAME_INVALID)
        val uids = friendUids.distinct()
        if (uids.size > MAX_ROLE_FRIENDS) return SocialResult.Refused(Refusal.ROLE_FULL)
        val friends = store.friends(me).associateBy { it.profile.uid }
        if (uids.any { it !in friends }) return SocialResult.Refused(Refusal.NOT_FRIENDS)
        val role = Role(idGen(), trimmed, me, listOf(me) + uids, clock() + ROLE_TTL_MS)
        val creator = myFigures.copy(uid = me, state = MemberState.ACCEPTED)
        val invited = uids.map { Member(it, friends.getValue(it).profile.displayName, MemberState.INVITED, null, null) }
        store.createRole(role, creator, invited)
        SocialResult.Ok
    }

    /**
     * Aceita o convite para o rolê. Rolê que não aparece mais para mim (encerrado ou inexistente)
     * ou já expirado devolve ROLE_EXPIRED.
     */
    suspend fun acceptRole(roleId: String): SocialResult = mutex.withLock {
        val role = store.roles(me).firstOrNull { it.id == roleId }
        if (role == null || role.expiresAtMs <= clock()) return SocialResult.Refused(Refusal.ROLE_EXPIRED)
        store.acceptRole(roleId, me)
        SocialResult.Ok
    }

    /** Sair do rolê; se sou o criador, o rolê é encerrado. Rolê inexistente (ou que não é meu): não faz nada. */
    suspend fun leaveRole(roleId: String) = mutex.withLock {
        val role = store.roles(me).firstOrNull { it.id == roleId }
        if (role != null) {
            if (role.creatorUid == me) store.closeRole(roleId) else store.leaveRole(roleId, me)
        }
    }

    /** Só o criador encerra o rolê; rolê inexistente ou de outro criador devolve NOT_CREATOR. */
    suspend fun closeRole(roleId: String): SocialResult = mutex.withLock {
        val role = store.roles(me).firstOrNull { it.id == roleId }
        if (role == null || role.creatorUid != me) return SocialResult.Refused(Refusal.NOT_CREATOR)
        store.closeRole(roleId)
        SocialResult.Ok
    }

    /** Só o criador renova: a nova validade é agora + 7 dias. Rolê ausente ou de outro criador devolve NOT_CREATOR. */
    suspend fun renewRole(roleId: String): SocialResult = mutex.withLock {
        val role = store.roles(me).firstOrNull { it.id == roleId }
        if (role == null || role.creatorUid != me) return SocialResult.Refused(Refusal.NOT_CREATOR)
        store.renewRole(roleId, clock() + ROLE_TTL_MS)
        SocialResult.Ok
    }

    /** Encerra (apaga membros e rolê) os rolês vencidos em que sou o criador; o TTL do Firestore não apaga os membros. */
    suspend fun closeExpiredRoles() = mutex.withLock {
        val now = clock()
        store.roles(me).filter { it.creatorUid == me && it.expiresAtMs <= now }.forEach { store.closeRole(it.id) }
    }
}
