package app.hunter.app.social

import app.hunter.social.Activity
import app.hunter.social.Invite
import app.hunter.social.Member
import app.hunter.social.MemberState
import app.hunter.social.PublicProfile
import app.hunter.social.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SocialMappingTest {
    private val profile = PublicProfile("u1", "Ana Lima", "Honda", "CB 500X", 2022, true)

    @Test
    fun `id de amizade e igual nos dois sentidos`() {
        assertEquals("ana_beto", pairId("ana", "beto"))
        assertEquals("ana_beto", pairId("beto", "ana"))
    }

    @Test
    fun `ids de convite e de bloqueio sao de_para`() {
        assertEquals("ana_beto", inviteId("ana", "beto"))
        assertEquals("beto_ana", inviteId("beto", "ana"))
        assertEquals("ana_beto", blockId("ana", "beto"))
    }

    @Test
    fun `perfil vira mapa so com os campos das regras`() {
        val m = publicProfileToMap(profile)
        assertEquals(
            setOf("displayName", "displayNameLower", "brand", "model", "year", "searchable"),
            m.keys,
        )
        assertEquals("ana lima", m["displayNameLower"])
        assertEquals(true, m["searchable"])
        assertEquals(2022, m["year"])
    }

    @Test
    fun `displayNameLower e a chave normalizada sem acentos`() {
        val m = publicProfileToMap(profile.copy(displayName = "Ágata  Souza"))
        assertEquals("agata souza", m["displayNameLower"])
        assertEquals("Ágata  Souza", m["displayName"])
        assertEquals("joao silva", buscaToMap(profile.copy(displayName = "João Silva"))!!["displayNameLower"])
    }

    @Test
    fun `busca exige displayNameLower e some quando o nome normaliza para vazio`() {
        val m = buscaToMap(profile)!!
        assertEquals(setOf("displayName", "displayNameLower", "brand", "model"), m.keys)
        assertNull(buscaToMap(profile.copy(displayName = "́")))
    }

    @Test
    fun `perfil publico limita texto e omite ano fora da faixa`() {
        val m = publicProfileToMap(profile.copy(displayName = "  ", brand = " ", model = "x".repeat(80), year = 1900))
        assertEquals("Motociclista", m["displayName"])
        assertNull(m["brand"])
        assertEquals(60, (m["model"] as String).length)
        assertFalse("year" in m)
    }

    @Test
    fun `parsers do perfil invertem os mapas`() {
        val back = mapToPublicProfile("u1", publicProfileToMap(profile))
        assertEquals(profile, back)
        val hit = mapToSearchHit("u1", buscaToMap(profile)!!)
        assertEquals("u1", hit?.uid)
        assertEquals("Ana Lima", hit?.displayName)
        assertEquals("CB 500X", hit?.model)
        assertNull(mapToPublicProfile("u1", mapOf("searchable" to true)))
    }

    @Test
    fun `weekKm e weekStartMs numericos`() {
        val now = System.currentTimeMillis()
        val m = activityToMap(Activity(now, 123.4))
        assertEquals(setOf("weekStartMs", "weekKm"), m.keys)
        assertTrue(m["weekStartMs"] is Number)
        assertTrue(m["weekKm"] is Number)
        assertEquals(Activity(now, 123.4), mapToActivity(m))
        // valores fora das faixas das regras viram o limite (as regras recusariam o documento)
        assertEquals(99_999_999_999_999L, activityToMap(Activity(Long.MAX_VALUE, 1.0))["weekStartMs"])
        val bad = activityToMap(Activity(-5L, Double.NaN))
        assertEquals(0.0, (bad["weekKm"] as Number).toDouble())
        assertEquals(0L, (bad["weekStartMs"] as Number).toLong())
    }

    @Test
    fun `convite tem de para criadoEm e volta`() {
        val m = inviteToMap("ana", "beto", 9L)
        assertEquals(mapOf("de" to "ana", "para" to "beto", "criadoEm" to 9L), m)
        assertEquals(Invite("ana", "beto", 9L), mapToInvite(m))
        assertFailsWith<IllegalArgumentException> { inviteToMap("ana", "ana", 1L) }
        assertFailsWith<IllegalArgumentException> { inviteToMap("ana", "x".repeat(129), 1L) }
    }

    @Test
    fun `amizade grava a e b ordenados`() {
        assertEquals(mapOf("a" to "ana", "b" to "beto", "criadoEm" to 3L), friendshipToMap("beto", "ana", 3L))
    }

    @Test
    fun `bloqueio leva so criadoEm`() {
        assertEquals(mapOf("criadoEm" to 4L), blockToMap(4L))
    }

    @Test
    fun `membro INVITED nao leva orcamento`() {
        val m = memberToMap(Member("u2", "Beto", MemberState.INVITED, 500.0, 0.4), 7L)
        assertEquals(setOf("estado", "nome", "atualizadoEm"), m.keys)
        assertEquals("convidado", m["estado"])
    }

    @Test
    fun `membro ACCEPTED leva os numeros`() {
        val m = memberToMap(Member("u1", "Ana", MemberState.ACCEPTED, 500.0, 0.4), 7L)
        assertEquals("aceito", m["estado"])
        assertEquals(500.0, m["orcamento"])
        assertEquals(0.4, m["custoPorKm"])
        assertEquals(7L, m["atualizadoEm"])
        val nulls = memberToMap(Member("u1", "Ana", MemberState.ACCEPTED, null, Double.POSITIVE_INFINITY), 7L)
        assertNull(nulls["orcamento"])
        assertNull(nulls["custoPorKm"])
    }

    @Test
    fun `parser de membro inverte`() {
        val ok = Member("u1", "Ana", MemberState.ACCEPTED, 500.0, 0.4)
        assertEquals(ok, mapToMember("u1", memberToMap(ok, 1L)))
        val invited = Member("u2", "Beto", MemberState.INVITED, null, null)
        assertEquals(invited, mapToMember("u2", memberToMap(invited, 1L)))
    }

    @Test
    fun `rolê com 10 participantes e valido e volta`() {
        val ids = (1..10).map { "u$it" }
        val role = Role("r1", "Serra", "u1", ids, 99L)
        val m = roleToMap(role, 5L)
        assertEquals(setOf("nome", "criadorUid", "participantes", "expiraEm", "criadoEm"), m.keys)
        assertEquals(role, mapToRole("r1", m))
        assertFailsWith<IllegalArgumentException> { roleToMap(role.copy(participants = ids + "u11"), 5L) }
        assertFailsWith<IllegalArgumentException> { roleToMap(role.copy(participants = listOf("u1", "u1")), 5L) }
    }

    @Test
    fun `limite de busca e no maximo 20 e pede paginas de 20`() {
        assertEquals(emptyList(), searchPages(0))
        assertEquals(listOf(5), searchPages(5))
        assertEquals(listOf(20), searchPages(20))
        assertEquals(listOf(20, 20, 5), searchPages(45))
        assertTrue(searchPages(1000).all { it in 1..20 })
        assertEquals(MAX_SEARCH_TOTAL, searchPages(1000).sum())
    }
}
