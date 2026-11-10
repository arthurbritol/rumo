package app.hunter.social

/** Perfil público de um usuário (o que os amigos e a busca enxergam). */
data class PublicProfile(
    val uid: String,
    val displayName: String,
    val brand: String?,
    val model: String?,
    val year: Int?,
    val searchable: Boolean,
)

/** Resultado de busca por nome. */
data class SearchHit(val uid: String, val displayName: String, val brand: String?, val model: String?)

/** Convite de amizade pendente de [fromUid] para [toUid]. */
data class Invite(val fromUid: String, val toUid: String, val createdAtMs: Long)

/** Amigo com a última atividade semanal publicada (se houver). */
data class Friend(val profile: PublicProfile, val activity: Activity?)

/** Rolê em grupo; [participants] inclui o criador e os convidados. */
data class Role(
    val id: String,
    val name: String,
    val creatorUid: String,
    val participants: List<String>,
    val expiresAtMs: Long,
)

/** Persistência social (Firestore no app, fake nos testes). Operações em "lote" devem ser atômicas. */
interface SocialStore {
    suspend fun publishProfile(p: PublicProfile)
    suspend fun publishActivity(uid: String, a: Activity)

    /** Perfis pesquisáveis cujo nome (em minúsculas) começa com [prefixLower], no máximo [limit]. */
    suspend fun search(prefixLower: String, limit: Int): List<SearchHit>

    suspend fun sendInvite(from: String, to: String)
    suspend fun cancelInvite(from: String, to: String)

    /** Lote: cria a amizade e apaga o convite. */
    suspend fun acceptInvite(from: String, to: String)
    suspend fun declineInvite(from: String, to: String)
    suspend fun unfriend(uid: String, other: String)

    /** Lote: bloqueio + apaga amizade e convites entre os dois. */
    suspend fun block(uid: String, other: String)
    suspend fun unblock(uid: String, other: String)

    suspend fun invitesReceived(uid: String): List<Invite>
    suspend fun invitesSent(uid: String): List<Invite>
    suspend fun friends(uid: String): List<Friend>
    suspend fun blocked(uid: String): Set<String>

    suspend fun createRole(role: Role, creator: Member, invited: List<Member>)
    suspend fun acceptRole(roleId: String, uid: String)
    suspend fun leaveRole(roleId: String, uid: String)
    suspend fun closeRole(roleId: String)
    suspend fun setMemberFigures(roleId: String, uid: String, budget: Double?, costPerKm: Double?)
    suspend fun roles(uid: String): List<Role>
    suspend fun members(roleId: String): List<Member>

    suspend fun deleteAllSocial(uid: String)
}

/** Resultado de uma operação que pode ser recusada por regra de negócio. */
sealed interface SocialResult {
    object Ok : SocialResult
    data class Refused(val reason: Refusal) : SocialResult
}

/** Motivos de recusa das regras sociais. */
enum class Refusal { SELF, ALREADY_FRIENDS, FRIEND_LIMIT, BLOCKED_BY_ME, TOO_SHORT, ROLE_FULL, NOT_FRIENDS, NOT_CREATOR, ROLE_EXPIRED }
