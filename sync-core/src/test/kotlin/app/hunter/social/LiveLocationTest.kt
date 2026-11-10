package app.hunter.social

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveLocationTest {

    @Test
    fun `isExpired calcula expiracao corretamente baseada no timestamp e idade maxima`() {
        val baseTime = 1_000_000L
        val maxAge = 5 * 60 * 1000L // 5 minutos = 300_000 ms

        val fresh = LiveLocation(
            latitude = -23.5505,
            longitude = -46.6333,
            updatedAtMs = baseTime,
        )

        // No mesmo instante: não expirado
        assertFalse(fresh.isExpired(nowMs = baseTime, maxAgeMs = maxAge))

        // 4 minutos depois: não expirado
        assertFalse(fresh.isExpired(nowMs = baseTime + 4 * 60 * 1000L, maxAgeMs = maxAge))

        // Exatamente no limite de 5 minutos: não expirado (now - updated > maxAge)
        assertFalse(fresh.isExpired(nowMs = baseTime + maxAge, maxAgeMs = maxAge))

        // 5 minutos e 1 ms depois: expirado
        assertTrue(fresh.isExpired(nowMs = baseTime + maxAge + 1L, maxAgeMs = maxAge))

        // Custom maxAgeMs
        assertFalse(fresh.isExpired(nowMs = baseTime + 500L, maxAgeMs = 1_000L))
        assertTrue(fresh.isExpired(nowMs = baseTime + 1_500L, maxAgeMs = 1_000L))
    }

    @Test
    fun `isValid e isValidCoordinates validam limites de latitude e longitude`() {
        val saoPaulo = LiveLocation(latitude = -23.5505, longitude = -46.6333)
        assertTrue(saoPaulo.isValid())
        assertTrue(saoPaulo.isValidCoordinates())

        // Limites extremos validos
        val poloSul = LiveLocation(latitude = -90.0, longitude = -180.0)
        assertTrue(poloSul.isValid())
        assertTrue(poloSul.isValidCoordinates())

        val poloNorte = LiveLocation(latitude = 90.0, longitude = 180.0)
        assertTrue(poloNorte.isValid())
        assertTrue(poloNorte.isValidCoordinates())

        // Latitude invalida (> 90 ou < -90)
        val latAlta = LiveLocation(latitude = 90.0001, longitude = 0.0)
        assertFalse(latAlta.isValid())
        assertFalse(latAlta.isValidCoordinates())

        val latBaixa = LiveLocation(latitude = -90.0001, longitude = 0.0)
        assertFalse(latBaixa.isValid())
        assertFalse(latBaixa.isValidCoordinates())

        // Longitude invalida (> 180 ou < -180)
        val lngAlta = LiveLocation(latitude = 0.0, longitude = 180.0001)
        assertFalse(lngAlta.isValid())
        assertFalse(lngAlta.isValidCoordinates())

        val lngBaixa = LiveLocation(latitude = 0.0, longitude = -180.0001)
        assertFalse(lngBaixa.isValid())
        assertFalse(lngBaixa.isValidCoordinates())
    }

    @Test
    fun `LocationPermission docId combina de e para com underscore`() {
        assertEquals("user123_user456", LocationPermission.docId("user123", "user456"))
        assertEquals("alice_bob", LocationPermission.docId("alice", "bob"))

        val perm = LocationPermission(de = "u1", para = "u2", criadoEm = 123456L)
        assertEquals("u1", perm.de)
        assertEquals("u2", perm.para)
        assertEquals(123456L, perm.criadoEm)
    }

    @Test
    fun `SocialStore possui implementacoes padrao seguras para operacoes de localizacao`() = runBlocking {
        val store = object : SocialStore {
            override suspend fun publishProfile(p: PublicProfile) {}
            override suspend fun publishedSearchable(uid: String): Boolean? = null
            override suspend fun publishActivity(uid: String, a: Activity) {}
            override suspend fun removeFromSearch(uid: String) {}
            override suspend fun search(prefixLower: String, limit: Int): List<SearchHit> = emptyList()
            override suspend fun searchEntry(uid: String): SearchHit? = null
            override suspend fun sendInvite(from: String, to: String) {}
            override suspend fun cancelInvite(from: String, to: String) {}
            override suspend fun acceptInvite(from: String, to: String) {}
            override suspend fun declineInvite(from: String, to: String) {}
            override suspend fun unfriend(uid: String, other: String) {}
            override suspend fun block(uid: String, other: String) {}
            override suspend fun unblock(uid: String, other: String) {}
            override suspend fun invitesReceived(uid: String): List<Invite> = emptyList()
            override suspend fun invitesSent(uid: String): List<Invite> = emptyList()
            override suspend fun friends(uid: String): List<Friend> = emptyList()
            override suspend fun blocked(uid: String): Set<String> = emptySet()
            override suspend fun friendUids(uid: String): Set<String> = emptySet()
            override fun observeInvitesReceived(uid: String) = kotlinx.coroutines.flow.emptyFlow<List<Invite>>()
            override fun observeFriendUids(uid: String) = kotlinx.coroutines.flow.emptyFlow<Set<String>>()
            override fun observeRoles(uid: String) = kotlinx.coroutines.flow.emptyFlow<List<Role>>()
            override fun observeMembers(roleId: String) = kotlinx.coroutines.flow.emptyFlow<List<Member>>()
            override suspend fun createRole(role: Role, creator: Member, invited: List<Member>) {}
            override suspend fun acceptRole(roleId: String, uid: String) {}
            override suspend fun leaveRole(roleId: String, uid: String) {}
            override suspend fun closeRole(roleId: String) {}
            override suspend fun renewRole(roleId: String, newExpiresAtMs: Long) {}
            override suspend fun setMemberBudget(roleId: String, uid: String, budget: Double?) {}
            override suspend fun setMemberCost(roleId: String, uid: String, costPerKm: Double?) {}
            override suspend fun roles(uid: String): List<Role> = emptyList()
            override suspend fun members(roleId: String): List<Member> = emptyList()
            override suspend fun member(roleId: String, uid: String): Member? = null
            override suspend fun deleteAllSocial(uid: String) {}
        }

        // Metodos suspend default nao devem lançar excecao
        store.grantLocationPermission("friend1")
        store.revokeLocationPermission("friend1")
        store.publishLocation(LiveLocation(latitude = 0.0, longitude = 0.0))
        store.clearLocation()

        // Flows default retornam vazios
        assertEquals(emptyList(), store.observeGrantedPermissions("myUid").toList())
        assertEquals(emptyList(), store.observeReceivedPermissions("myUid").toList())
        assertNull(store.observeFriendLocation("friend1").firstOrNull())
    }
}
