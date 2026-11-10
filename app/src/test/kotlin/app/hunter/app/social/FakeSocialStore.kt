package app.hunter.app.social

import app.hunter.social.Activity
import app.hunter.social.Friend
import app.hunter.social.Invite
import app.hunter.social.LiveLocation
import app.hunter.social.Member
import app.hunter.social.MemberState
import app.hunter.social.PublicProfile
import app.hunter.social.Role
import app.hunter.social.SearchHit
import app.hunter.social.SearchKey
import app.hunter.social.SocialStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** Chamada de [SocialStore.setMemberBudget] (custo nulo) ou [SocialStore.setMemberCost] (orçamento nulo) registrada pelo [FakeSocialStore]. */
data class FigureCall(val roleId: String, val uid: String, val budget: Double?, val costPerKm: Double?)

/**
 * [SocialStore] em memória para os testes do app. Com [failure] definido, toda operação (e todo flow observado)
 * lança essa exceção, para simular rede fora do ar, permissão negada etc.
 */
/** [SearchPrefs] em memória para os testes. */
class MemorySearchPrefs(var asked: Boolean = false, var searchable: Boolean = true) : SearchPrefs {
    override fun asked() = asked
    override fun searchable() = searchable
    override fun set(searchable: Boolean) {
        this.searchable = searchable
        asked = true
    }
}

class FakeSocialStore : SocialStore {
    var failure: Exception? = null
    private fun check() { failure?.let { throw it } }

    val profiles = LinkedHashMap<String, PublicProfile>()
    val searchIndex = mutableSetOf<String>()
    val removedFromSearch = mutableListOf<String>()
    val activities = LinkedHashMap<String, Activity>()
    val figureCalls = mutableListOf<FigureCall>()
    val rolesById = LinkedHashMap<String, Role>()
    val membersByRole = LinkedHashMap<String, MutableList<Member>>()
    val closed = mutableListOf<String>()
    val declined = mutableListOf<Pair<String, String>>()
    val sentInvites = mutableListOf<Pair<String, String>>()
    val friendsFlow = MutableStateFlow<List<Friend>>(emptyList())
    val invitesFlow = MutableStateFlow<List<Invite>>(emptyList())
    val rolesFlow = MutableStateFlow<List<Role>>(emptyList())
    val sentList = mutableListOf<Invite>()
    val blockedUids = mutableSetOf<String>()
    val searchData = mutableListOf<SearchHit>()
    var profileWrites = 0
    var activityWrites = 0
    var rolesCalls = 0
    val memberCalls = mutableListOf<String>()
    val searchEntryCalls = mutableListOf<String>()
    /** Falha só em [publishProfile] (para testar que as outras etapas seguem). */
    var profileFailure: Exception? = null
    /** Roles cuja leitura do próprio membro falha. */
    val memberFailureRoles = mutableSetOf<String>()
    var observeFriendsStarts = 0
    var searchCalls = 0
    /** Falhas restantes de [observeFriends] antes de funcionar (para testar a nova tentativa). */
    var friendObserverFailures = 0

    override suspend fun publishProfile(p: PublicProfile) {
        check()
        profileFailure?.let { throw it }
        profileWrites++
        profiles[p.uid] = p
        if (p.searchable) searchIndex += p.uid else searchIndex -= p.uid
    }
    override suspend fun removeFromSearch(uid: String) { check(); searchIndex -= uid; removedFromSearch += uid }
    override suspend fun publishActivity(uid: String, a: Activity) { check(); activityWrites++; activities[uid] = a }
    override suspend fun search(prefixLower: String, limit: Int): List<SearchHit> {
        check()
        searchCalls++
        return searchData.filter { SearchKey.normalize(it.displayName).startsWith(prefixLower) }.take(limit)
    }
    override suspend fun searchEntry(uid: String): SearchHit? { check(); searchEntryCalls += uid; return searchData.firstOrNull { it.uid == uid } }
    override suspend fun sendInvite(from: String, to: String) { check(); sentInvites += from to to; sentList += Invite(from, to, 0L) }
    override suspend fun cancelInvite(from: String, to: String) { check(); sentList.removeAll { it.fromUid == from && it.toUid == to } }
    override suspend fun acceptInvite(from: String, to: String) {
        check()
        invitesFlow.value = invitesFlow.value.filterNot { it.fromUid == from && it.toUid == to }
    }
    override suspend fun declineInvite(from: String, to: String) {
        check()
        declined += from to to
        invitesFlow.value = invitesFlow.value.filterNot { it.fromUid == from && it.toUid == to }
    }
    override suspend fun unfriend(uid: String, other: String) { check() }
    override suspend fun block(uid: String, other: String) { check(); blockedUids += other }
    override suspend fun unblock(uid: String, other: String) { check(); blockedUids -= other }
    override suspend fun invitesReceived(uid: String): List<Invite> { check(); return invitesFlow.value }
    override suspend fun invitesSent(uid: String): List<Invite> { check(); return sentList.toList() }
    var friendsCalls = 0
    override suspend fun friends(uid: String): List<Friend> { check(); friendsCalls++; return friendsFlow.value }
    override suspend fun blocked(uid: String): Set<String> { check(); return blockedUids.toSet() }
    override suspend fun friendUids(uid: String): Set<String> { check(); return friendsFlow.value.map { it.profile.uid }.toSet() }

    private fun <T> Flow<T>.guarded(): Flow<T> = flow {
        check()
        collect { emit(it) }
    }
    override fun observeInvitesReceived(uid: String): Flow<List<Invite>> = invitesFlow.guarded()
    override fun observeFriendUids(uid: String): Flow<Set<String>> = flow {
        observeFriendsStarts++
        if (friendObserverFailures > 0) { friendObserverFailures--; throw java.io.IOException("sem rede") }
        check()
        friendsFlow.map { l -> l.map { it.profile.uid }.toSet() }.distinctUntilChanged().collect { emit(it) }
    }
    override fun observeRoles(uid: String): Flow<List<Role>> = rolesFlow.guarded()
    override fun observeMembers(roleId: String): Flow<List<Member>> =
        MutableStateFlow(Unit).map { membersByRole[roleId].orEmpty().toList() }.guarded()

    override suspend fun createRole(role: Role, creator: Member, invited: List<Member>) {
        check()
        rolesById[role.id] = role
        membersByRole[role.id] = (listOf(creator) + invited).toMutableList()
        rolesFlow.value = rolesById.values.toList()
    }
    override suspend fun acceptRole(roleId: String, uid: String) {
        check()
        val l = membersByRole.getValue(roleId)
        val i = l.indexOfFirst { it.uid == uid }
        l[i] = l[i].copy(state = MemberState.ACCEPTED)
    }
    val leftRoles = mutableListOf<Pair<String, String>>()
    val renewed = mutableListOf<String>()
    override suspend fun leaveRole(roleId: String, uid: String) {
        check()
        leftRoles += roleId to uid
        membersByRole[roleId]?.removeAll { it.uid == uid }
    }
    override suspend fun closeRole(roleId: String) { check(); closed += roleId; rolesById.remove(roleId); rolesFlow.value = rolesById.values.toList() }
    override suspend fun renewRole(roleId: String, newExpiresAtMs: Long) {
        check()
        renewed += roleId
        rolesById[roleId]?.let { rolesById[roleId] = it.copy(expiresAtMs = newExpiresAtMs) }
    }
    override suspend fun setMemberBudget(roleId: String, uid: String, budget: Double?) {
        check()
        figureCalls += FigureCall(roleId, uid, budget, null)
    }
    override suspend fun setMemberCost(roleId: String, uid: String, costPerKm: Double?) {
        check()
        figureCalls += FigureCall(roleId, uid, null, costPerKm)
    }
    /** Chamadas de [publishedSearchable]; [publishedSearchableFailure] faz a leitura falhar (sem rede). */
    var publishedSearchableCalls = 0
    var publishedSearchableFailure: Exception? = null
    override suspend fun publishedSearchable(uid: String): Boolean? {
        publishedSearchableCalls++
        publishedSearchableFailure?.let { throw it }
        check()
        return profiles[uid]?.searchable
    }
    override suspend fun roles(uid: String): List<Role> { check(); rolesCalls++; return rolesById.values.filter { uid in it.participants } }
    override suspend fun members(roleId: String): List<Member> { check(); return membersByRole[roleId].orEmpty().toList() }
    override suspend fun member(roleId: String, uid: String): Member? {
        check()
        memberCalls += roleId
        if (roleId in memberFailureRoles) throw IOException("sem rede")
        return membersByRole[roleId]?.firstOrNull { it.uid == uid } }
    val deletedAll = mutableListOf<String>()
    override suspend fun deleteAllSocial(uid: String) { check(); deletedAll += uid }

    val grantedPermissions = mutableSetOf<String>()
    val grantedFlow = MutableStateFlow<Set<String>>(emptySet())
    val receivedFlow = MutableStateFlow<Set<String>>(emptySet())
    val friendLocations = mutableMapOf<String, MutableStateFlow<LiveLocation?>>()
    val grantedCalls = mutableListOf<String>()
    val revokedCalls = mutableListOf<String>()

    override suspend fun grantLocationPermission(friendUid: String) {
        check()
        grantedCalls += friendUid
        grantedPermissions += friendUid
        grantedFlow.value = grantedPermissions.toSet()
    }

    override suspend fun revokeLocationPermission(friendUid: String) {
        check()
        revokedCalls += friendUid
        grantedPermissions -= friendUid
        grantedFlow.value = grantedPermissions.toSet()
    }

    override fun observeGrantedPermissions(myUid: String): Flow<Set<String>> = grantedFlow.guarded()
    override fun observeReceivedPermissions(myUid: String): Flow<Set<String>> = receivedFlow.guarded()
    override fun observeFriendLocation(friendUid: String): Flow<LiveLocation?> =
        friendLocations.getOrPut(friendUid) { MutableStateFlow(null) }.guarded()
}
