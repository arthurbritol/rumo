package app.hunter.app.social

import app.hunter.social.Activity
import app.hunter.social.Friend
import app.hunter.social.Invite
import app.hunter.social.Member
import app.hunter.social.MemberState
import app.hunter.social.PublicProfile
import app.hunter.social.Role
import app.hunter.social.SearchHit
import app.hunter.social.SearchKey
import app.hunter.social.SocialStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** Chamada de [SocialStore.setMemberFigures] registrada pelo [FakeSocialStore]. */
data class FigureCall(val roleId: String, val uid: String, val budget: Double?, val costPerKm: Double?)

/**
 * [SocialStore] em memória para os testes do app. Com [failure] definido, toda operação (e todo flow observado)
 * lança essa exceção, para simular rede fora do ar, permissão negada etc.
 */
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
    var observeFriendsStarts = 0
    var searchCalls = 0
    /** Falhas restantes de [observeFriends] antes de funcionar (para testar a nova tentativa). */
    var friendObserverFailures = 0

    override suspend fun publishProfile(p: PublicProfile) {
        check()
        profiles[p.uid] = p
        if (p.searchable) searchIndex += p.uid else searchIndex -= p.uid
    }
    override suspend fun removeFromSearch(uid: String) { check(); searchIndex -= uid; removedFromSearch += uid }
    override suspend fun publishActivity(uid: String, a: Activity) { check(); activities[uid] = a }
    override suspend fun search(prefixLower: String, limit: Int): List<SearchHit> {
        check()
        searchCalls++
        return searchData.filter { SearchKey.normalize(it.displayName).startsWith(prefixLower) }.take(limit)
    }
    override suspend fun searchEntry(uid: String): SearchHit? { check(); return searchData.firstOrNull { it.uid == uid } }
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
    override suspend fun friends(uid: String): List<Friend> { check(); return friendsFlow.value }
    override suspend fun blocked(uid: String): Set<String> { check(); return blockedUids.toSet() }
    override suspend fun friendUids(uid: String): Set<String> { check(); return friendsFlow.value.map { it.profile.uid }.toSet() }

    private fun <T> Flow<T>.guarded(): Flow<T> = flow {
        check()
        collect { emit(it) }
    }
    override fun observeInvitesReceived(uid: String): Flow<List<Invite>> = invitesFlow.guarded()
    override fun observeFriends(uid: String): Flow<List<Friend>> = flow {
        observeFriendsStarts++
        if (friendObserverFailures > 0) { friendObserverFailures--; throw java.io.IOException("sem rede") }
        check()
        friendsFlow.collect { emit(it) }
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
    override suspend fun leaveRole(roleId: String, uid: String) { check() }
    override suspend fun closeRole(roleId: String) { check(); closed += roleId; rolesById.remove(roleId); rolesFlow.value = rolesById.values.toList() }
    override suspend fun renewRole(roleId: String, newExpiresAtMs: Long) {
        check()
        rolesById[roleId]?.let { rolesById[roleId] = it.copy(expiresAtMs = newExpiresAtMs) }
    }
    override suspend fun setMemberFigures(roleId: String, uid: String, budget: Double?, costPerKm: Double?) {
        check()
        figureCalls += FigureCall(roleId, uid, budget, costPerKm)
    }
    override suspend fun roles(uid: String): List<Role> { check(); return rolesById.values.filter { uid in it.participants } }
    override suspend fun members(roleId: String): List<Member> { check(); return membersByRole[roleId].orEmpty().toList() }
    override suspend fun member(roleId: String, uid: String): Member? { check(); return membersByRole[roleId]?.firstOrNull { it.uid == uid } }
    override suspend fun deleteAllSocial(uid: String) { check() }
}
