package app.hunter.app.social

import app.hunter.social.Activity
import app.hunter.social.Friend
import app.hunter.social.Invite
import app.hunter.social.Member
import app.hunter.social.MemberState
import app.hunter.social.PublicProfile
import app.hunter.social.Refusal
import app.hunter.social.Role
import app.hunter.social.SearchHit
import app.hunter.social.SocialEngine
import app.hunter.social.WeekMath
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SocialViewModelTest {
    private val zone = ZoneId.of("America/Recife")
    private val now = ZonedDateTime.of(2026, 11, 11, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val thisWeek = WeekMath.weekStartMs(now, zone)

    private val store = FakeSocialStore()

    private class MemoryPrefs(var asked: Boolean = false, var searchable: Boolean = true) : SearchPrefs {
        override fun asked() = asked
        override fun searchable() = searchable
        override fun set(searchable: Boolean) {
            this.searchable = searchable
            asked = true
        }
    }

    private inner class FakeBackend(initialUid: String? = "me") : SocialBackend {
        override val uid = MutableStateFlow(initialUid)
        override val searchPrefs = MemoryPrefs()
        var sessionCalls = 0
        val publishCalls = mutableListOf<Boolean>()
        override fun session(uid: String): SocialSession {
            sessionCalls++
            val publisher = SocialPublisher(store, uid, { null }, { emptyList() }, { null }, { true }, { zone }) { now }
            return SocialSession(uid, store, SocialEngine(store, uid, clock = { now }, idGen = { "role-x" }), publisher)
        }
        override fun publishSoon(force: Boolean) { publishCalls += force }
    }

    private val backend = FakeBackend()

    private fun TestScope.vm(b: SocialBackend = backend) =
        SocialViewModel(b, backgroundScope, clock = { now }, zone = { zone })

    private fun friend(uid: String, name: String, km: Double? = null) =
        Friend(PublicProfile(uid, name, "Honda", "CG 160", null, true), km?.let { Activity(thisWeek, it) })

    @Test
    fun `mostra amigos com km da semana, convites e rolês do store`() = runTest {
        store.friendsFlow.value = listOf(friend("ana", "Ana", 12.34))
        store.invitesFlow.value = listOf(Invite("bia", "me", 1))
        store.searchData += SearchHit("bia", "Bia", null, null)
        store.rolesFlow.value = listOf(Role("r1", "Praia", "ana", listOf("ana", "me"), now + 1000))
        store.membersByRole["r1"] = mutableListOf(Member("me", "Eu", MemberState.INVITED, null, null))
        val vm = vm()
        runCurrent()
        val ui = vm.state.value
        assertTrue(ui.signedIn)
        assertEquals("Honda CG 160", ui.friends.single().bikeLabel)
        assertEquals("12,3 km", ui.friends.single().weekKmLabel)
        assertEquals(listOf("bia"), ui.invitesReceived.map { it.uid })
        assertEquals("Praia", ui.roles.single().name)
        assertEquals("Ana", ui.roles.single().creatorName)
        assertTrue(ui.roles.single().pending)
        assertFalse(ui.offline)
        assertNull(ui.message)
    }

    @Test
    fun `visitante não toca no store`() = runTest {
        val b = FakeBackend(initialUid = null)
        val vm = vm(b)
        runCurrent()
        assertFalse(vm.state.value.signedIn)
        assertEquals(0, b.sessionCalls)
        assertEquals(0, store.observeFriendsStarts)
        assertTrue(b.publishCalls.isEmpty())
    }

    @Test
    fun `abrir logado publica em segundo plano e pergunta da busca aparece uma vez`() = runTest {
        val vm = vm()
        runCurrent()
        assertEquals(listOf(false), backend.publishCalls)
        assertTrue(vm.state.value.searchPromptNeeded)
        vm.answerSearchPrompt(false)
        assertFalse(vm.state.value.searchPromptNeeded)
        assertTrue(backend.searchPrefs.asked())
        assertFalse(backend.searchPrefs.searchable())
        assertEquals(listOf(false, true), backend.publishCalls) // resposta publica sem esperar o intervalo
    }

    @Test
    fun `store que lança IOException vira estado offline sem derrubar`() = runTest {
        store.failure = IOException("sem rede")
        val vm = vm()
        runCurrent()
        val ui = vm.state.value
        assertTrue(ui.offline)
        assertTrue(ui.message!!.isNotBlank())
        assertTrue(ui.signedIn)
        assertTrue(ui.friends.isEmpty())
    }

    @Test
    fun `ação que falha vira offline com mensagem e libera o busy`() = runTest {
        val vm = vm()
        runCurrent()
        store.failure = IOException("sem rede")
        vm.invite("ana")
        runCurrent()
        val ui = vm.state.value
        assertTrue(ui.offline)
        assertTrue(ui.message!!.isNotBlank())
        assertFalse(ui.busy)
    }

    @Test
    fun `recusa do engine vira mensagem em pt-BR`() = runTest {
        val vm = vm()
        runCurrent()
        vm.invite("me")
        runCurrent()
        assertEquals(refusalMessage(Refusal.SELF), vm.state.value.message)
        assertFalse(vm.state.value.offline)
    }

    @Test
    fun `convite de bloqueado fica escondido e é recusado`() = runTest {
        store.blockedUids += "chato"
        store.invitesFlow.value = listOf(Invite("chato", "me", 1), Invite("bia", "me", 2))
        val vm = vm()
        runCurrent()
        assertEquals(listOf("bia"), vm.state.value.invitesReceived.map { it.uid })
        assertEquals(listOf("chato" to "me"), store.declined)
    }

    @Test
    fun `observador que falha tenta de novo em 2 s, 5 s e 15 s e depois espera refresh`() = runTest {
        store.friendObserverFailures = 100
        val vm = vm()
        runCurrent()
        assertEquals(1, store.observeFriendsStarts)
        assertTrue(vm.state.value.offline)
        advanceTimeBy(1_999); runCurrent()
        assertEquals(1, store.observeFriendsStarts)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, store.observeFriendsStarts)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(3, store.observeFriendsStarts)
        advanceTimeBy(15_000); runCurrent()
        assertEquals(4, store.observeFriendsStarts)
        advanceTimeBy(600_000); runCurrent()
        assertEquals(4, store.observeFriendsStarts) // parou: só refresh() recomeça
        store.friendObserverFailures = 0
        store.friendsFlow.value = listOf(friend("ana", "Ana", 1.0))
        vm.refresh()
        runCurrent()
        assertEquals(5, store.observeFriendsStarts)
        assertEquals(listOf("Ana"), vm.state.value.friends.map { it.name })
        assertFalse(vm.state.value.offline)
    }

    @Test
    fun `volta ao normal quando a nova tentativa funciona`() = runTest {
        store.friendObserverFailures = 1
        store.friendsFlow.value = listOf(friend("ana", "Ana", 1.0))
        val vm = vm()
        runCurrent()
        assertTrue(vm.state.value.offline)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(listOf("Ana"), vm.state.value.friends.map { it.name })
        assertFalse(vm.state.value.offline)
        assertNull(vm.state.value.message)
    }

    @Test
    fun `busca espera 300 ms e exige 3 letras`() = runTest {
        store.searchData += SearchHit("c1", "Carlos", "Honda", "CG 160")
        val vm = vm()
        runCurrent()
        vm.search("ca")
        advanceTimeBy(1_000); runCurrent()
        assertEquals(0, store.searchCalls)
        vm.search("car")
        advanceTimeBy(299); runCurrent()
        assertEquals(0, store.searchCalls)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, store.searchCalls)
        assertEquals(listOf("c1"), vm.state.value.searchResults.map { it.uid })
        assertFalse(vm.state.value.searching)
        vm.search("ca") // apagou letras: limpa na hora
        assertTrue(vm.state.value.searchResults.isEmpty())
    }

    @Test
    fun `digitar de novo cancela a busca anterior`() = runTest {
        store.searchData += SearchHit("c1", "Carlos", null, null)
        val vm = vm()
        runCurrent()
        vm.search("car")
        advanceTimeBy(200)
        vm.search("carl")
        advanceTimeBy(299); runCurrent()
        assertEquals(0, store.searchCalls)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, store.searchCalls)
    }

    @Test
    fun `definir orçamento mantém o custo por km publicado`() = runTest {
        store.membersByRole["r1"] = mutableListOf(Member("me", "Eu", MemberState.ACCEPTED, 100.0, 0.25))
        val vm = vm()
        runCurrent()
        vm.setBudget("r1", 300.0)
        runCurrent()
        assertEquals(listOf(FigureCall("r1", "me", 300.0, 0.25)), store.figureCalls)
        vm.setBudget("r1", -5.0)
        runCurrent()
        assertEquals(1, store.figureCalls.size) // valor inválido não grava
        assertTrue(vm.state.value.message!!.isNotBlank())
    }

    @Test
    fun `aceitar rolê publica o custo na hora`() = runTest {
        store.rolesById["r1"] = Role("r1", "Praia", "ana", listOf("ana", "me"), now + 1000)
        store.membersByRole["r1"] = mutableListOf(Member("me", "Eu", MemberState.INVITED, null, null))
        val vm = vm()
        runCurrent()
        vm.acceptRole("r1")
        runCurrent()
        assertEquals(MemberState.ACCEPTED, store.membersByRole.getValue("r1").single().state)
        assertEquals(true, backend.publishCalls.last())
    }

    @Test
    fun `convite enviado entra na lista de enviados e cancelar tira`() = runTest {
        val vm = vm()
        runCurrent()
        vm.invite("ana")
        runCurrent()
        assertEquals(listOf("ana"), vm.state.value.invitesSent.map { it.uid })
        vm.cancelInvite("ana")
        runCurrent()
        assertTrue(vm.state.value.invitesSent.isEmpty())
    }

    @Test
    fun `estado ACCEPTED do rolê é lido uma vez só e snapshots repetidos não releem`() = runTest {
        store.membersByRole["r1"] = mutableListOf(Member("me", "Eu", MemberState.ACCEPTED, null, null))
        store.membersByRole["r2"] = mutableListOf(Member("me", "Eu", MemberState.INVITED, null, null))
        val r1 = Role("r1", "A", "me", listOf("me"), now + 1000)
        val r2 = Role("r2", "B", "ana", listOf("ana", "me"), now + 1000)
        store.rolesFlow.value = listOf(r1, r2)
        val vm = vm()
        runCurrent()
        assertEquals(listOf("r1", "r2"), store.memberCalls)
        store.rolesFlow.value = listOf(r1, r2.copy(participants = listOf("ana", "me", "bia")))
        runCurrent()
        assertEquals(listOf("r1", "r2", "r2"), store.memberCalls) // r1 aceito: não relê; r2 pendente: relê
        assertTrue(vm.state.value.roles.first { it.id == "r2" }.pending)
    }

    @Test
    fun `falha ao ler o estado de um rolê não derruba a lista`() = runTest {
        store.membersByRole["r1"] = mutableListOf(Member("me", "Eu", MemberState.ACCEPTED, null, null))
        store.memberFailureRoles += "r2"
        store.rolesFlow.value = listOf(
            Role("r1", "A", "me", listOf("me"), now + 1000),
            Role("r2", "B", "ana", listOf("ana", "me"), now + 1000),
        )
        val vm = vm()
        runCurrent()
        assertEquals(listOf("r1", "r2"), vm.state.value.roles.map { it.id })
        assertFalse(vm.state.value.offline)
    }

    @Test
    fun `quem não tem nome público não é consultado de novo a cada snapshot`() = runTest {
        store.invitesFlow.value = listOf(Invite("zed", "me", 1))
        val vm = vm()
        runCurrent()
        assertEquals("Alguém", vm.state.value.invitesReceived.single().name)
        store.invitesFlow.value = listOf(Invite("zed", "me", 1), Invite("bia", "me", 2))
        runCurrent()
        vm.refresh()
        runCurrent()
        assertEquals(1, store.searchEntryCalls.count { it == "zed" })
    }

    @Test
    fun `orçamento de convite pendente é recusado localmente sem virar offline`() = runTest {
        store.membersByRole["r1"] = mutableListOf(Member("me", "Eu", MemberState.INVITED, null, null))
        val vm = vm()
        runCurrent()
        vm.setBudget("r1", 100.0)
        runCurrent()
        assertTrue(store.figureCalls.isEmpty())
        assertEquals(BUDGET_NOT_ACCEPTED_MESSAGE, vm.state.value.message)
        assertFalse(vm.state.value.offline)
    }

    @Test
    fun `orçamento de rolê vencido é recusado`() = runTest {
        store.membersByRole["r1"] = mutableListOf(Member("me", "Eu", MemberState.ACCEPTED, null, null))
        store.rolesFlow.value = listOf(Role("r1", "A", "me", listOf("me"), now - 1))
        val vm = vm()
        runCurrent()
        vm.setBudget("r1", 100.0)
        runCurrent()
        assertTrue(store.figureCalls.isEmpty())
        assertEquals(refusalMessage(Refusal.ROLE_EXPIRED), vm.state.value.message)
    }

    @Test
    fun `onForeground recalcula a semana e publica sem forçar`() = runTest {
        var cur = now
        store.friendsFlow.value = listOf(friend("ana", "Ana", 12.34))
        val vm = SocialViewModel(backend, backgroundScope, clock = { cur }, zone = { zone })
        runCurrent()
        assertEquals("12,3 km", vm.state.value.friends.single().weekKmLabel)
        cur += 7L * 24 * 3600 * 1000
        vm.onForeground()
        assertEquals("0 km", vm.state.value.friends.single().weekKmLabel)
        assertEquals(false, backend.publishCalls.last())
        assertEquals(2, backend.publishCalls.size)
    }
}
