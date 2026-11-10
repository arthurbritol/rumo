package app.hunter.social

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeSocialStore : SocialStore {
    val profiles = LinkedHashMap<String, PublicProfile>()
    val friendships = mutableSetOf<Pair<String, String>>() // guardado nos dois sentidos
    val invites = mutableListOf<Invite>()
    val blocks = mutableSetOf<Pair<String, String>>() // (quem bloqueou, bloqueado)
    val rolesById = LinkedHashMap<String, Role>()
    val membersByRole = LinkedHashMap<String, MutableList<Member>>()
    val closed = mutableListOf<String>()
    var sendCount = 0
    var friendsCalls = 0
    val changes = MutableStateFlow(0)
    private fun bump() { changes.value++ }

    fun befriend(a: String, b: String) { friendships += a to b; friendships += b to a; bump() }
    fun addProfile(uid: String, name: String = uid) { profiles[uid] = PublicProfile(uid, name, null, null, null, true) }

    override suspend fun publishProfile(p: PublicProfile) { profiles[p.uid] = p }
    override suspend fun publishActivity(uid: String, a: Activity) {}
    override suspend fun search(prefixLower: String, limit: Int): List<SearchHit> =
        profiles.values.filter { it.searchable && SearchKey.normalize(it.displayName).startsWith(prefixLower) }
            .take(limit).map { SearchHit(it.uid, it.displayName, it.brand, it.model) }
    override suspend fun sendInvite(from: String, to: String) { sendCount++; invites += Invite(from, to, 0L); bump() }
    override suspend fun cancelInvite(from: String, to: String) { invites.removeAll { it.fromUid == from && it.toUid == to } }
    override suspend fun acceptInvite(from: String, to: String) {
        invites.removeAll { it.fromUid == from && it.toUid == to }
        befriend(from, to)
    }
    override suspend fun declineInvite(from: String, to: String) { invites.removeAll { it.fromUid == from && it.toUid == to }; bump() }
    override suspend fun unfriend(uid: String, other: String) { friendships -= uid to other; friendships -= other to uid }
    override suspend fun block(uid: String, other: String) {
        blocks += uid to other
        unfriend(uid, other)
        invites.removeAll { (it.fromUid == uid && it.toUid == other) || (it.fromUid == other && it.toUid == uid) }
    }
    override suspend fun unblock(uid: String, other: String) { blocks -= uid to other }
    override suspend fun invitesReceived(uid: String) = invites.filter { it.toUid == uid }
    override suspend fun invitesSent(uid: String): List<Invite> { yield(); return invites.filter { it.fromUid == uid } }
    override suspend fun friends(uid: String): List<Friend> {
        friendsCalls++
        return friendships.filter { it.first == uid }.map { Friend(profiles.getValue(it.second), null) }
    }
    override suspend fun friendUids(uid: String): Set<String> = friendships.filter { it.first == uid }.map { it.second }.toSet()
    override fun observeInvitesReceived(uid: String): Flow<List<Invite>> = changes.map { invites.filter { it.toUid == uid } }
    override fun observeFriends(uid: String): Flow<List<Friend>> =
        changes.map { friendships.filter { it.first == uid }.map { Friend(profiles.getValue(it.second), null) } }
    override fun observeRoles(uid: String): Flow<List<Role>> = changes.map { rolesById.values.filter { uid in it.participants } }
    override fun observeMembers(roleId: String): Flow<List<Member>> = changes.map { membersByRole[roleId].orEmpty().toList() }
    override suspend fun blocked(uid: String) = blocks.filter { it.first == uid }.map { it.second }.toSet()
    override suspend fun createRole(role: Role, creator: Member, invited: List<Member>) {
        rolesById[role.id] = role
        membersByRole[role.id] = (listOf(creator) + invited).toMutableList()
        bump()
    }
    override suspend fun acceptRole(roleId: String, uid: String) {
        val l = membersByRole.getValue(roleId)
        val i = l.indexOfFirst { it.uid == uid }
        l[i] = l[i].copy(state = MemberState.ACCEPTED)
        bump()
    }
    override suspend fun leaveRole(roleId: String, uid: String) {
        membersByRole[roleId]?.removeAll { it.uid == uid }
        rolesById[roleId]?.let { rolesById[roleId] = it.copy(participants = it.participants - uid) }
    }
    override suspend fun closeRole(roleId: String) { closed += roleId; rolesById.remove(roleId); membersByRole.remove(roleId); bump() }
    override suspend fun setMemberFigures(roleId: String, uid: String, budget: Double?, costPerKm: Double?) {}
    override suspend fun roles(uid: String) = rolesById.values.filter { uid in it.participants }
    override suspend fun members(roleId: String) = membersByRole[roleId].orEmpty().toList()
    override suspend fun deleteAllSocial(uid: String) {}
    override suspend fun member(roleId: String, uid: String): Member? = membersByRole[roleId]?.firstOrNull { it.uid == uid }
    override suspend fun searchEntry(uid: String): SearchHit? =
        profiles[uid]?.takeIf { it.searchable }?.let { SearchHit(it.uid, it.displayName, it.brand, it.model) }
    val removedFromSearch = mutableListOf<String>()
    override suspend fun removeFromSearch(uid: String) { removedFromSearch += uid }
    override suspend fun renewRole(roleId: String, newExpiresAtMs: Long) {
        rolesById[roleId]?.let { rolesById[roleId] = it.copy(expiresAtMs = newExpiresAtMs) }
        bump()
    }
}

class SocialEngineTest {
    private val store = FakeSocialStore()
    private var now = 1_000_000L
    private val engine = SocialEngine(store, "me", clock = { now }, idGen = { "role-1" })
    private val myFigures = Member("me", "Eu", MemberState.ACCEPTED, 100.0, 0.5)
    private val sevenDaysMs = 7L * 24 * 3600 * 1000

    private fun users(vararg uids: String) = uids.forEach { store.addProfile(it) }

    @Test
    fun `busca ignora menos de 3 letras, a si mesmo, bloqueados e amigos e limita a 20`() = runTest {
        store.addProfile("me", "Carlos Eu")
        store.addProfile("amigo", "Carlos Amigo")
        store.addProfile("bloq", "Carlos Bloq")
        for (i in 1..30) store.addProfile("u$i", "Carlos $i")
        store.befriend("me", "amigo")
        store.blocks += "me" to "bloq"
        assertEquals(emptyList(), engine.search("ca"))
        assertEquals(emptyList(), engine.search("  ca "))
        val hits = engine.search("  CAR ")
        assertEquals(20, hits.size)
        assertTrue(hits.none { it.uid in setOf("me", "amigo", "bloq") })
    }

    @Test
    fun `busca ignora acentos e caixa`() = runTest {
        store.addProfile("joao", "João Silva")
        assertEquals(listOf("joao"), engine.search("  JOAO  si").map { it.uid })
        assertEquals(listOf("joao"), engine.search("joão").map { it.uid })
    }

    @Test
    fun `convidar a si mesmo é recusado`() = runTest {
        assertEquals(SocialResult.Refused(Refusal.SELF), engine.invite("me"))
    }

    @Test
    fun `convidar amigo é recusado`() = runTest {
        users("me", "ana")
        store.befriend("me", "ana")
        assertEquals(SocialResult.Refused(Refusal.ALREADY_FRIENDS), engine.invite("ana"))
    }

    @Test
    fun `convite cruzado aceita o convite recebido`() = runTest {
        users("me", "ana")
        store.invites += Invite("ana", "me", 1)
        store.invites += Invite("me", "ana", 1) // convite meu pendente também some
        assertEquals(SocialResult.Ok, engine.invite("ana"))
        assertEquals(0, store.sendCount)
        assertEquals(listOf("ana"), store.friends("me").map { it.profile.uid })
        assertEquals(emptyList(), store.invites)
    }

    @Test
    fun `convite repetido não duplica`() = runTest {
        users("me", "ana")
        assertEquals(SocialResult.Ok, engine.invite("ana"))
        assertEquals(SocialResult.Ok, engine.invite("ana"))
        assertEquals(1, store.sendCount)
    }

    @Test
    fun `convidar com 50 amigos é recusado`() = runTest {
        users("me", "novo")
        for (i in 1..50) { store.addProfile("f$i"); store.befriend("me", "f$i") }
        assertEquals(SocialResult.Refused(Refusal.FRIEND_LIMIT), engine.invite("novo"))
    }

    @Test
    fun `convidar alguém que bloqueei é recusado`() = runTest {
        users("me", "ana")
        store.blocks += "me" to "ana"
        assertEquals(SocialResult.Refused(Refusal.BLOCKED_BY_ME), engine.invite("ana"))
    }

    @Test
    fun `visibleInvites esconde e apaga convites de bloqueados`() = runTest {
        users("me", "ana", "bia")
        store.invites += Invite("ana", "me", 1)
        store.invites += Invite("bia", "me", 2)
        store.blocks += "me" to "bia"
        assertEquals(listOf("ana"), engine.visibleInvites().map { it.fromUid })
        assertEquals(listOf("ana"), store.invites.map { it.fromUid })
    }

    @Test
    fun `bloquear amigo apaga amizade e convites`() = runTest {
        users("me", "ana")
        store.befriend("me", "ana")
        store.invites += Invite("ana", "me", 1)
        engine.block("ana")
        assertEquals(emptyList(), store.friends("me"))
        assertEquals(emptyList(), store.invites)
        assertEquals(SocialResult.Refused(Refusal.BLOCKED_BY_ME), engine.invite("ana"))
    }

    @Test
    fun `criar rolê só com amigos`() = runTest {
        users("me", "ana", "estranho")
        store.befriend("me", "ana")
        assertEquals(SocialResult.Refused(Refusal.NOT_FRIENDS), engine.createRole("Praia", listOf("ana", "estranho"), myFigures))
        assertEquals(SocialResult.Ok, engine.createRole("  Praia ", listOf("ana"), myFigures))
        val role = store.rolesById.values.single()
        assertEquals("Praia", role.name)
        assertEquals("me", role.creatorUid)
        assertEquals(now + sevenDaysMs, role.expiresAtMs)
        val members = store.members(role.id)
        assertEquals(MemberState.ACCEPTED, members.first { it.uid == "me" }.state)
        assertEquals(100.0, members.first { it.uid == "me" }.budget)
        val ana = members.first { it.uid == "ana" }
        assertEquals(MemberState.INVITED, ana.state)
        assertEquals(null, ana.budget)
    }

    @Test
    fun `nome inválido é recusado`() = runTest {
        assertEquals(SocialResult.Refused(Refusal.NAME_INVALID), engine.createRole("   ", emptyList(), myFigures))
        assertEquals(SocialResult.Refused(Refusal.NAME_INVALID), engine.createRole("x".repeat(61), emptyList(), myFigures))
    }

    @Test
    fun `rolê com mais de 10 pessoas é recusado`() = runTest {
        for (i in 1..10) { store.addProfile("f$i"); store.befriend("me", "f$i") }
        val ten = (1..10).map { "f$it" }
        assertEquals(SocialResult.Refused(Refusal.ROLE_FULL), engine.createRole("Grande", ten, myFigures))
        assertEquals(SocialResult.Ok, engine.createRole("Cheio", ten.take(9), myFigures))
    }

    private suspend fun roleWithAna(): String {
        users("me", "ana")
        store.befriend("me", "ana")
        engine.createRole("Praia", listOf("ana"), myFigures)
        return store.rolesById.keys.single()
    }

    @Test
    fun `criador saindo encerra o rolê`() = runTest {
        val id = roleWithAna()
        engine.leaveRole(id)
        assertEquals(listOf(id), store.closed)
    }

    @Test
    fun `participante saindo só remove a si mesmo`() = runTest {
        val id = roleWithAna()
        SocialEngine(store, "ana", clock = { now }, idGen = { "r-ana" }).leaveRole(id)
        assertEquals(emptyList(), store.closed)
        assertEquals(listOf("me"), store.members(id).map { it.uid })
    }

    @Test
    fun `só o criador encerra o rolê`() = runTest {
        val id = roleWithAna()
        val ana = SocialEngine(store, "ana", clock = { now }, idGen = { "r-ana" })
        assertEquals(SocialResult.Refused(Refusal.NOT_CREATOR), ana.closeRole(id))
        assertEquals(SocialResult.Ok, engine.closeRole(id))
    }

    @Test
    fun `rolê expirado é recusado ao aceitar`() = runTest {
        val id = roleWithAna()
        val ana = SocialEngine(store, "ana", clock = { now }, idGen = { "r-ana" })
        now += sevenDaysMs // exatamente na expiração
        assertEquals(SocialResult.Refused(Refusal.ROLE_EXPIRED), ana.acceptRole(id))
        now -= 1
        assertEquals(SocialResult.Ok, ana.acceptRole(id))
        assertEquals(MemberState.ACCEPTED, store.members(id).first { it.uid == "ana" }.state)
    }

    @Test
    fun `busca devolve 20 válidos mesmo com muitos amigos e bloqueados nos primeiros resultados`() = runTest {
        for (i in 1..25) { store.addProfile("f$i", "Zeca a$i"); store.befriend("me", "f$i") }
        for (i in 1..5) { store.addProfile("b$i", "Zeca b$i"); store.blocks += "me" to "b$i" }
        for (i in 1..30) store.addProfile("v$i", "Zeca v$i")
        val hits = engine.search("zec")
        assertEquals(20, hits.size)
        assertTrue(hits.all { it.uid.startsWith("v") })
    }

    @Test
    fun `invite e search não carregam perfis de amigos`() = runTest {
        users("me", "ana", "bia")
        store.befriend("me", "bia")
        engine.invite("ana")
        engine.search("ana")
        assertEquals(0, store.friendsCalls)
    }

    @Test
    fun `acceptInvite feliz, sem convite e no limite`() = runTest {
        users("me", "ana")
        assertEquals(SocialResult.Refused(Refusal.INVITE_GONE), engine.acceptInvite("ana"))
        store.invites += Invite("ana", "me", 1)
        assertEquals(SocialResult.Ok, engine.acceptInvite("ana"))
        assertEquals(setOf("ana"), store.friendUids("me"))
        assertEquals(emptyList(), store.invites)
        users("cheio")
        for (i in 1..49) { store.addProfile("g$i"); store.befriend("me", "g$i") }
        store.invites += Invite("cheio", "me", 2)
        assertEquals(SocialResult.Refused(Refusal.FRIEND_LIMIT), engine.acceptInvite("cheio"))
        assertEquals(1, store.invites.size)
        assertEquals(0, store.friendsCalls)
    }

    @Test
    fun `declineInvite apaga o convite recebido`() = runTest {
        users("me", "ana")
        store.invites += Invite("ana", "me", 1)
        engine.declineInvite("ana")
        assertEquals(emptyList(), store.invites)
    }

    @Test
    fun `dois toques seguidos em convidar mandam um convite só`() = runTest {
        users("me", "ana")
        listOf(async { engine.invite("ana") }, async { engine.invite("ana") }).awaitAll()
        assertEquals(1, store.sendCount)
    }

    @Test
    fun `observeInvitesReceived propaga mudança`() = runTest {
        val seen = mutableListOf<List<Invite>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.observeInvitesReceived("me").collect { seen += it }
        }
        assertEquals(emptyList(), seen.last())
        store.sendInvite("ana", "me")
        assertEquals(listOf("ana"), seen.last().map { it.fromUid })
    }

    @Test
    fun `bloquear a si mesmo é recusado`() = runTest {
        assertEquals(SocialResult.Refused(Refusal.SELF), engine.block("me"))
    }

    @Test
    fun `rolê usa o gerador de id`() = runTest {
        roleWithAna()
        assertEquals("role-1", store.rolesById.keys.single())
    }

    @Test
    fun `rolê ausente - aceitar expira, encerrar não é do criador, sair não faz nada`() = runTest {
        assertEquals(SocialResult.Refused(Refusal.ROLE_EXPIRED), engine.acceptRole("nada"))
        assertEquals(SocialResult.Refused(Refusal.NOT_CREATOR), engine.closeRole("nada"))
        engine.leaveRole("nada")
        assertEquals(emptyList(), store.closed)
    }

    @Test
    fun `criador renova o rolê por mais 7 dias a partir de agora`() = runTest {
        val id = roleWithAna()
        now += 3L * 24 * 3600 * 1000
        assertEquals(SocialResult.Ok, engine.renewRole(id))
        assertEquals(now + sevenDaysMs, store.rolesById.getValue(id).expiresAtMs)
    }

    @Test
    fun `renovar sem ser criador ou rolê ausente devolve NOT_CREATOR`() = runTest {
        val id = roleWithAna()
        val before = store.rolesById.getValue(id).expiresAtMs
        val ana = SocialEngine(store, "ana", clock = { now + 1000 }, idGen = { "r-ana" })
        assertEquals(SocialResult.Refused(Refusal.NOT_CREATOR), ana.renewRole(id))
        assertEquals(SocialResult.Refused(Refusal.NOT_CREATOR), engine.renewRole("nada"))
        assertEquals(before, store.rolesById.getValue(id).expiresAtMs)
    }

    @Test
    fun `closeExpiredRoles encerra só os vencidos em que sou criador`() = runTest {
        users("me", "ana")
        store.befriend("me", "ana")
        store.rolesById["velho"] = Role("velho", "Velho", "me", listOf("me", "ana"), now - 1)
        store.rolesById["vencendo"] = Role("vencendo", "Agora", "me", listOf("me"), now)
        store.rolesById["novo"] = Role("novo", "Novo", "me", listOf("me"), now + 1)
        store.rolesById["dela"] = Role("dela", "Dela", "ana", listOf("ana", "me"), now - 1)
        engine.closeExpiredRoles()
        assertEquals(setOf("velho", "vencendo"), store.closed.toSet())
        assertEquals(setOf("novo", "dela"), store.rolesById.keys)
    }
}
