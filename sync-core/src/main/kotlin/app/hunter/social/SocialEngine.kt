package app.hunter.social

import java.util.Locale
import java.util.UUID

/** Regras sociais de [me] (convites, busca, bloqueio e rolês) sobre um [SocialStore], sem Android. */
class SocialEngine(
    private val store: SocialStore,
    private val me: String,
    private val clock: () -> Long = System::currentTimeMillis,
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

    /** Busca por nome: texto aparado e em minúsculas, mínimo de 3 letras; sem eu, bloqueados e amigos; até 20. */
    suspend fun search(text: String): List<SearchHit> {
        val q = text.trim().lowercase(Locale.ROOT)
        if (q.length < MIN_SEARCH_CHARS) return emptyList()
        val blocked = store.blocked(me)
        val friends = store.friends(me).map { it.profile.uid }.toSet()
        // Pede com margem porque o filtro acontece depois da consulta.
        return store.search(q, SEARCH_LIMIT * 2)
            .filter { it.uid != me && it.uid !in blocked && it.uid !in friends }
            .take(SEARCH_LIMIT)
    }

    /** Convida [to]. Convite cruzado aceita o recebido; repetido devolve Ok sem duplicar. */
    suspend fun invite(to: String): SocialResult {
        if (to == me) return SocialResult.Refused(Refusal.SELF)
        if (to in store.blocked(me)) return SocialResult.Refused(Refusal.BLOCKED_BY_ME)
        val friends = store.friends(me)
        if (friends.any { it.profile.uid == to }) return SocialResult.Refused(Refusal.ALREADY_FRIENDS)
        if (friends.size >= FRIEND_LIMIT) return SocialResult.Refused(Refusal.FRIEND_LIMIT)
        if (store.invitesReceived(me).any { it.fromUid == to }) {
            store.acceptInvite(to, me)
            return SocialResult.Ok
        }
        if (store.invitesSent(me).none { it.toUid == to }) store.sendInvite(me, to)
        return SocialResult.Ok
    }

    /** Convites recebidos sem os de bloqueados; esses também são recusados para não se acumularem. */
    suspend fun visibleInvites(): List<Invite> {
        val blocked = store.blocked(me)
        val (hidden, visible) = store.invitesReceived(me).partition { it.fromUid in blocked }
        hidden.forEach { store.declineInvite(it.fromUid, me) }
        return visible
    }

    /** Bloqueia [other] (apaga amizade e convites entre os dois, no store). */
    suspend fun block(other: String) = store.block(me, other)

    /** Cria um rolê com [friendUids] (todos precisam ser meus amigos); expira em 7 dias. */
    suspend fun createRole(name: String, friendUids: List<String>, myFigures: Member): SocialResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_ROLE_NAME) return SocialResult.Refused(Refusal.TOO_SHORT)
        val uids = friendUids.distinct()
        if (uids.size > MAX_ROLE_FRIENDS) return SocialResult.Refused(Refusal.ROLE_FULL)
        val friends = store.friends(me).associateBy { it.profile.uid }
        if (uids.any { it !in friends }) return SocialResult.Refused(Refusal.NOT_FRIENDS)
        val role = Role(UUID.randomUUID().toString(), trimmed, me, listOf(me) + uids, clock() + ROLE_TTL_MS)
        val creator = myFigures.copy(uid = me, state = MemberState.ACCEPTED)
        val invited = uids.map { Member(it, friends.getValue(it).profile.displayName, MemberState.INVITED, null, null) }
        store.createRole(role, creator, invited)
        return SocialResult.Ok
    }

    /**
     * Aceita o convite para o rolê. Rolê que não aparece mais para mim (encerrado) ou já expirado
     * devolve ROLE_EXPIRED.
     */
    suspend fun acceptRole(roleId: String): SocialResult {
        val role = store.roles(me).firstOrNull { it.id == roleId }
        if (role == null || role.expiresAtMs <= clock()) return SocialResult.Refused(Refusal.ROLE_EXPIRED)
        store.acceptRole(roleId, me)
        return SocialResult.Ok
    }

    /** Sair do rolê; se sou o criador, o rolê é encerrado. */
    suspend fun leaveRole(roleId: String) {
        val role = store.roles(me).firstOrNull { it.id == roleId } ?: return
        if (role.creatorUid == me) store.closeRole(roleId) else store.leaveRole(roleId, me)
    }

    /** Só o criador encerra o rolê. */
    suspend fun closeRole(roleId: String): SocialResult {
        val role = store.roles(me).firstOrNull { it.id == roleId }
        if (role == null || role.creatorUid != me) return SocialResult.Refused(Refusal.NOT_CREATOR)
        store.closeRole(roleId)
        return SocialResult.Ok
    }
}
