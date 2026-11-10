package app.hunter.app.social

import app.hunter.social.Activity
import app.hunter.social.Friend
import app.hunter.social.MemberState
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
}
