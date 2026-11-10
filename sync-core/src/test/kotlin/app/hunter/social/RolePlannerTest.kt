package app.hunter.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RolePlannerTest {
    private fun m(uid: String, budget: Double?, cost: Double?, state: MemberState = MemberState.ACCEPTED) =
        Member(uid, uid, state, budget, cost)

    @Test
    fun `média simples dos orçamentos e do custo por km`() {
        val p = RolePlanner.plan(listOf(m("a", 100.0, 0.5), m("b", 200.0, 1.0)))
        assertEquals(150.0, p.averageBudget!!, 1e-9)
        assertEquals(0.75, p.averageCostPerKm!!, 1e-9)
        assertEquals(200.0, p.suggestedReachKm!!, 1e-9)
        assertNull(p.noReach)
    }

    @Test
    fun `orçamento zero entra na média`() {
        val p = RolePlanner.plan(listOf(m("a", 0.0, 1.0), m("b", 100.0, 1.0)))
        assertEquals(50.0, p.averageBudget!!, 1e-9)
    }

    @Test
    fun `orçamento ainda não informado fica fora`() {
        val p = RolePlanner.plan(listOf(m("a", null, 1.0), m("b", 100.0, 1.0)))
        assertEquals(100.0, p.averageBudget!!, 1e-9)
    }

    @Test
    fun `convidado pendente não entra`() {
        val p = RolePlanner.plan(listOf(m("a", 100.0, 1.0), m("b", 1000.0, 1.0, MemberState.INVITED)))
        assertEquals(100.0, p.averageBudget!!, 1e-9)
        assertEquals(listOf("a"), p.members.map { it.uid })
    }

    @Test
    fun `ninguém com consumo - sem alcance e motivo NO_CONSUMPTION_DATA`() {
        val p = RolePlanner.plan(listOf(m("a", 100.0, null), m("b", 50.0, 0.0)))
        assertNull(p.suggestedReachKm)
        assertNull(p.averageCostPerKm)
        assertEquals(NoReach.NO_CONSUMPTION_DATA, p.noReach)
    }

    @Test
    fun `só um aceito`() {
        val p = RolePlanner.plan(listOf(m("a", 80.0, 0.4), m("b", 10.0, 1.0, MemberState.INVITED)))
        assertEquals(200.0, p.suggestedReachKm!!, 1e-9)
        assertNull(p.members.single().warning)
    }

    @Test
    fun `aviso OVER_BUDGET para quem não cobre o alcance`() {
        val p = RolePlanner.plan(listOf(m("a", 100.0, 0.5), m("b", 200.0, 1.0)))
        // alcance 200 km: a precisa de 100 (ok, 0,5*200), b precisa de 200 (ok)
        assertNull(p.members.first { it.uid == "a" }.warning)
        val q = RolePlanner.plan(listOf(m("a", 50.0, 1.0), m("b", 150.0, 1.0)))
        // alcance 100 km: a precisa de 100 e tem 50
        assertEquals(MemberWarning.OVER_BUDGET, q.members.first { it.uid == "a" }.warning)
        assertNull(q.members.first { it.uid == "b" }.warning)
    }

    @Test
    fun `membro sem consumo usa o custo médio e recebe NO_CONSUMPTION_DATA`() {
        val p = RolePlanner.plan(listOf(m("a", 100.0, 1.0), m("b", 100.0, null)))
        assertEquals(100.0, p.suggestedReachKm!!, 1e-9)
        assertEquals(MemberWarning.NO_CONSUMPTION_DATA, p.members.first { it.uid == "b" }.warning)
    }

    @Test
    fun `ninguém aceitou - NO_ACCEPTED_MEMBERS`() {
        val p = RolePlanner.plan(listOf(m("a", 100.0, 1.0, MemberState.INVITED)))
        assertEquals(NoReach.NO_ACCEPTED_MEMBERS, p.noReach)
        assertNull(p.suggestedReachKm)
        assertEquals(emptyList(), p.members)
    }

    @Test
    fun `sem orçamentos - NO_BUDGETS`() {
        val p = RolePlanner.plan(listOf(m("a", null, 1.0)))
        assertEquals(NoReach.NO_BUDGETS, p.noReach)
    }
}
