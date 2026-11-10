package app.hunter.app.social

import app.hunter.social.Activity
import app.hunter.social.Friend
import app.hunter.social.Member
import app.hunter.social.MemberState
import app.hunter.social.MemberWarning
import app.hunter.social.NoReach
import app.hunter.social.PublicProfile
import app.hunter.social.Refusal
import app.hunter.social.Role
import app.hunter.social.WeekMath
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SocialUiMapperTest {
    private val zone = ZoneId.of("America/Recife")
    private val now = ZonedDateTime.of(2026, 11, 11, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val thisWeek = WeekMath.weekStartMs(now, zone)
    private val lastWeek = WeekMath.weekStartMs(now - 7L * 24 * 3600 * 1000, zone)

    private fun friend(uid: String, name: String, brand: String? = "Honda", model: String? = "CG 160", activity: Activity? = null) =
        Friend(PublicProfile(uid, name, brand, model, null, true), activity)

    @Test
    fun `km da semana com uma casa e vírgula, e 0 km quando zero`() {
        assertEquals("0 km", weekKmLabel(0.0))
        assertEquals("12,3 km", weekKmLabel(12.34))
        assertEquals("5,0 km", weekKmLabel(5.0))
        assertEquals("1234,6 km", weekKmLabel(1234.56))
        assertEquals("0 km", weekKmLabel(0.04)) // arredonda para 0,0: sem casa decimal
    }

    @Test
    fun `atividade de semana passada ou ausente aparece como 0 km`() {
        val rows = friendRows(
            listOf(
                friend("a", "Ana", activity = Activity(lastWeek, 50.0)),
                friend("b", "Bia", activity = Activity(thisWeek, 12.34)),
                friend("c", "Caio", activity = null),
            ),
            now, zone,
        )
        assertEquals(mapOf("a" to "0 km", "b" to "12,3 km", "c" to "0 km"), rows.associate { it.uid to it.weekKmLabel })
    }

    @Test
    fun `rótulo da moto`() {
        assertEquals("Honda CG 160", bikeLabel("Honda", "CG 160"))
        assertEquals("Moto não informada", bikeLabel(null, null))
        assertEquals("Moto não informada", bikeLabel("  ", ""))
        assertEquals("CG 160", bikeLabel(null, "CG 160"))
        assertEquals("Honda", bikeLabel("Honda", null))
    }

    @Test
    fun `amigos ordenados por km da semana e depois por nome, nome vazio vira Amigo`() {
        val rows = friendRows(
            listOf(
                friend("z", "Zeca", activity = Activity(thisWeek, 10.0)),
                friend("a", "Ana", activity = Activity(thisWeek, 10.0)),
                friend("m", "Maria", activity = Activity(thisWeek, 90.0)),
                friend("x", "  ", brand = null, model = null),
            ),
            now, zone,
        )
        assertEquals(listOf("Maria", "Ana", "Zeca", "Amigo"), rows.map { it.name })
        assertEquals("Moto não informada", rows.last().bikeLabel)
    }

    @Test
    fun `convites usam o nome conhecido ou Alguém`() {
        val rows = inviteRows(listOf("u1", "u2"), mapOf("u1" to "Ana"))
        assertEquals(listOf(InviteRow("u1", "Ana"), InviteRow("u2", "Alguém")), rows)
    }

    private fun role(id: String, creator: String, expires: Long, vararg members: String) =
        Role(id, "Rolê $id", creator, listOf(creator) + members, expires)

    @Test
    fun `rolês - pendente, criador, vencido de outro some e vencido meu fica marcado`() {
        val roles = listOf(
            role("pend", "ana", now + 1000, "me"),
            role("meu", "me", now + 1000, "ana"),
            role("velhoDela", "ana", now, "me"),
            role("velhoMeu", "me", now - 1, "ana"),
        )
        val states = mapOf("pend" to MemberState.INVITED, "meu" to MemberState.ACCEPTED, "velhoMeu" to MemberState.ACCEPTED)
        val rows = roleRows(roles, "me", states, { if (it == "ana") "Ana" else null }, now)
        assertEquals(listOf("pend", "meu", "velhoMeu"), rows.map { it.id })
        val pend = rows[0]
        assertTrue(pend.pending)
        assertEquals("Ana", pend.creatorName)
        assertEquals(2, pend.participantCount)
        assertEquals(false, pend.isCreator)
        assertTrue(rows[1].isCreator)
        assertNull(rows[1].creatorName)
        assertEquals(false, rows[1].expired)
        assertTrue(rows[2].expired)
    }

    @Test
    fun `refusalMessage cobre todos os motivos com textos distintos`() {
        val texts = Refusal.entries.map { refusalMessage(it) }
        assertTrue(texts.all { it.isNotBlank() })
        assertEquals(Refusal.entries.size, texts.toSet().size)
        assertNotEquals(refusalMessage(Refusal.SELF), refusalMessage(Refusal.NOT_FRIENDS))
    }

    @Test
    fun `marcador da aba some com zero e satura em 99+`() {
        assertNull(friendsBadge(0))
        assertEquals("3", friendsBadge(3))
        assertEquals("99", friendsBadge(99))
        assertEquals("99+", friendsBadge(100))
        assertEquals(5, pendingCount(3, 2))
    }

    @Test
    fun `com o banner de offline as mensagens genéricas de rede somem`() {
        assertNull(messageToShow(true, OFFLINE_MESSAGE))
        assertNull(messageToShow(true, ACTION_FAILED_MESSAGE))
        assertEquals(OFFLINE_MESSAGE, messageToShow(false, OFFLINE_MESSAGE))
        assertEquals("Vocês já são amigos.", messageToShow(true, "Vocês já são amigos."))
    }

    @Test
    fun `seleção de amigos para no limite de 9`() {
        var sel = emptySet<String>()
        (1..12).forEach { sel = toggleSelection(sel, "u$it") }
        assertEquals(9, sel.size)
        assertTrue("u10" !in sel)
        assertEquals(8, toggleSelection(sel, "u1").size)
    }

    @Test
    fun `dinheiro em reais com vírgula e milhar`() {
        assertEquals("R$ 150,00", moneyLabel(150.0))
        assertEquals("R$ 1.234,50", moneyLabel(1234.5))
        assertEquals("150", budgetInputText(150.0))
        assertEquals("150,50", budgetInputText(150.5))
        assertEquals("", budgetInputText(null))
    }

    @Test
    fun `lê o orçamento digitado`() {
        assertEquals(150.0, parseBudget("150"))
        assertEquals(150.5, parseBudget("150,5"))
        assertEquals(1234.5, parseBudget("R$ 1.234,50"))
        assertEquals(1500.0, parseBudget("1.500"))
        assertEquals(90.25, parseBudget("90.25"))
        assertNull(parseBudget(""))
        assertNull(parseBudget("abc"))
        assertNull(parseBudget("-5"))
        assertNull(parseBudget("1,234"))
        assertNull(parseBudget("99999999"))
    }

    @Test
    fun `alcance arredonda para baixo`() {
        assertEquals("até 180 km", reachLabel(180.9))
        assertEquals("menos de 1 km", reachLabel(0.4))
    }

    @Test
    fun `avisos e motivos de não haver alcance`() {
        assertEquals("Passa do que você pode gastar", warningLabel(MemberWarning.OVER_BUDGET, true))
        assertEquals("Passa do que esta pessoa pode gastar", warningLabel(MemberWarning.OVER_BUDGET, false))
        assertEquals("Sem dados de consumo", warningLabel(MemberWarning.NO_CONSUMPTION_DATA, false))
        assertNull(warningLabel(null, true))
        val texts = NoReach.entries.map(::noReachMessage)
        assertEquals(NoReach.entries.size, texts.toSet().size)
        assertTrue(noReachMessage(NoReach.NO_CONSUMPTION_DATA).contains("consumo"))
    }

    private fun role(creator: Boolean = true) =
        RoleRow("r1", "Praia", null, creator, false, 3, now + 1000, false)

    @Test
    fun `detalhe do rolê mostra média, alcance, avisos e quem pode ser removido`() {
        val members = listOf(
            Member("me", "Eu", MemberState.ACCEPTED, 100.0, 0.5),
            Member("ana", "Ana", MemberState.ACCEPTED, 100.0, 1.0),
            Member("bia", "Bia", MemberState.ACCEPTED, 50.0, null),
            Member("caio", "Caio", MemberState.INVITED, null, null),
        )
        val d = roleDetailUi(role(), members, "me")
        // orçamento médio (100+100+50)/3 = 83,33; custo médio (0,5+1)/2 = 0,75 => alcance 111,1 km
        assertEquals("R$ 83,33", d.averageLabel)
        assertEquals("até 111 km", d.reachText)
        assertTrue(d.hasReach)
        assertEquals(listOf("me", "ana", "bia", "caio"), d.participants.map { it.uid })
        val byUid = d.participants.associateBy { it.uid }
        assertEquals("Você", byUid.getValue("me").name)
        assertEquals("R$ 100,00", byUid.getValue("me").budgetLabel)
        assertEquals(emptyList(), byUid.getValue("me").warnings)
        assertEquals(listOf("Passa do que esta pessoa pode gastar"), byUid.getValue("ana").warnings)
        // sem dados de consumo, bia é medida pelo custo médio do grupo (83,33 de média contra 50 informados)
        assertEquals(listOf("Passa do que esta pessoa pode gastar", "Sem dados de consumo"), byUid.getValue("bia").warnings)
        assertEquals(emptyList(), byUid.getValue("caio").warnings)
        assertEquals(listOf("ana", "bia", "caio"), d.participants.filter { it.canRemove }.map { it.uid })
    }

    @Test
    fun `sem dados de consumo ninguém tem alcance e o texto explica`() {
        val members = listOf(
            Member("me", "Eu", MemberState.ACCEPTED, 100.0, null),
            Member("ana", "Ana", MemberState.ACCEPTED, null, null),
            Member("bia", "Bia", MemberState.INVITED, null, null),
        )
        val d = roleDetailUi(role(creator = false), members, "me")
        assertTrue(!d.hasReach)
        assertEquals(noReachMessage(NoReach.NO_CONSUMPTION_DATA), d.reachText)
        assertEquals("R$ 100,00", d.averageLabel)
        assertEquals(listOf("Sem dados de consumo"), d.participants.first { it.uid == "me" }.warnings)
        assertEquals("Sem orçamento", d.participants.first { it.uid == "ana" }.budgetLabel)
        assertEquals("Aguardando resposta", d.participants.first { it.uid == "bia" }.budgetLabel)
        assertTrue(d.participants.none { it.canRemove })
        assertEquals(100.0, d.myBudget)
    }

    @Test
    fun `subtítulo do rolê na lista`() {
        assertEquals("Convite de Ana · 3 pessoas", roleSubtitle(RoleRow("r", "A", "Ana", false, true, 3, 0, false)))
        assertEquals("Você criou · 1 pessoa", roleSubtitle(RoleRow("r", "A", null, true, false, 1, 0, false)))
        assertTrue(roleSubtitle(RoleRow("r", "A", null, true, false, 1, 0, true)).startsWith("Expirado"))
    }

    @Test
    fun `amigo na estrada mapeia velocidade e timestamp de atualizacao`() {
        val liveLoc = app.hunter.social.LiveLocation(
            latitude = -8.05,
            longitude = -34.9,
            speedKmh = 72.5f,
            updatedAtMs = now - 15_000L,
        )
        val rows = friendRows(
            friends = listOf(friend("pilot1", "Piloto 1")),
            nowMs = now,
            zone = zone,
            activeFriendsOnRoad = mapOf("pilot1" to liveLoc),
        )
        assertEquals(1, rows.size)
        val row = rows[0]
        assertTrue(row.isLiveOnRoad)
        assertEquals(-8.05, row.liveLatitude)
        assertEquals(-34.9, row.liveLongitude)
        assertEquals(72.5f, row.liveSpeedKmh)
        assertEquals(now - 15_000L, row.liveUpdatedAtMs)
    }
}
