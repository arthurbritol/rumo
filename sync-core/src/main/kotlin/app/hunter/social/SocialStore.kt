package app.hunter.social

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

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

    /** O `searchable` já publicado no perfil público de [uid]; nulo se ele nunca publicou (a pessoa nunca respondeu). */
    suspend fun publishedSearchable(uid: String): Boolean?
    suspend fun publishActivity(uid: String, a: Activity)

    /** Apaga o índice de busca de [uid] (quem não quer aparecer na busca por nome); o perfil público continua. */
    suspend fun removeFromSearch(uid: String)

    /** Perfis pesquisáveis cuja chave [SearchKey] do nome começa com [prefixLower], no máximo [limit]. */
    suspend fun search(prefixLower: String, limit: Int): List<SearchHit>

    /** Entrada de busca de [uid] (nome e moto), ou nulo se a pessoa não aparece na busca; serve para dar nome a um convite. */
    suspend fun searchEntry(uid: String): SearchHit?

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

    /** Leitura barata: só os ids dos amigos, sem carregar perfis. */
    suspend fun friendUids(uid: String): Set<String>

    /** Observação em tempo real (as leituras pontuais acima continuam valendo). */
    fun observeInvitesReceived(uid: String): Flow<List<Invite>>

    /** Os ids dos amigos, só quando o conjunto muda (sem ler perfis); quem usa relê [friends] ao mudar. */
    fun observeFriendUids(uid: String): Flow<Set<String>>
    fun observeRoles(uid: String): Flow<List<Role>>
    fun observeMembers(roleId: String): Flow<List<Member>>

    suspend fun createRole(role: Role, creator: Member, invited: List<Member>)
    suspend fun acceptRole(roleId: String, uid: String)
    suspend fun leaveRole(roleId: String, uid: String)
    suspend fun closeRole(roleId: String)

    /** O criador estende a validade do rolê: grava o novo instante de expiração. */
    suspend fun renewRole(roleId: String, newExpiresAtMs: Long)

    /** Só o orçamento do próprio membro (o custo por km não é tocado). */
    suspend fun setMemberBudget(roleId: String, uid: String, budget: Double?)

    /** Só o custo por km do próprio membro (o orçamento não é tocado). */
    suspend fun setMemberCost(roleId: String, uid: String, costPerKm: Double?)
    suspend fun roles(uid: String): List<Role>
    suspend fun members(roleId: String): List<Member>

    /**
     * Só o documento de membro de [uid] (nulo se não existe). Diferente de [members], vale também para quem ainda
     * está só convidado (as regras só deixam listar os membros a quem já aceitou).
     */
    suspend fun member(roleId: String, uid: String): Member?

    suspend fun deleteAllSocial(uid: String)

    /** Concede permissão para [friendUid] ver a localização do usuário logado. */
    suspend fun grantLocationPermission(friendUid: String) {}

    /** Revoga permissão para [friendUid] ver a localização do usuário logado. */
    suspend fun revokeLocationPermission(friendUid: String) {}

    /** Observa conjunto de UIDs para os quais [myUid] concedeu permissão de localização. */
    fun observeGrantedPermissions(myUid: String): Flow<Set<String>> = emptyFlow()

    /** Observa conjunto de UIDs que concederam permissão de localização para [myUid]. */
    fun observeReceivedPermissions(myUid: String): Flow<Set<String>> = emptyFlow()

    /** Publica a coordenada ativa do usuário logado em `localizacoes/{uid}`. */
    suspend fun publishLocation(loc: LiveLocation) {}

    /** Remove a coordenada ativa do usuário logado ao desconectar do Pod. */
    suspend fun clearLocation() {}

    /** Observa em tempo real a coordenada de [friendUid], se houver autorização. */
    fun observeFriendLocation(friendUid: String): Flow<LiveLocation?> = emptyFlow()
}

/** Resultado de uma operação que pode ser recusada por regra de negócio. */
sealed interface SocialResult {
    object Ok : SocialResult
    data class Refused(val reason: Refusal) : SocialResult
}

/** Motivos de recusa das regras sociais. */
enum class Refusal {
    SELF, ALREADY_FRIENDS, FRIEND_LIMIT, BLOCKED_BY_ME,

    /** Texto de busca curto demais. */
    TOO_SHORT,
    ROLE_FULL, NOT_FRIENDS, NOT_CREATOR, ROLE_EXPIRED,

    /** O convite recebido não existe mais (cancelado ou já tratado). */
    INVITE_GONE,

    /** Nome de rolê vazio ou com mais de 60 caracteres. */
    NAME_INVALID,
}
