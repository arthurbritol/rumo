package app.hunter.app.social

import app.hunter.social.Activity
import app.hunter.social.Friend
import app.hunter.social.LiveLocation
import app.hunter.social.PublicProfile
import app.hunter.social.SocialEngine
import app.hunter.social.WeekMath
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SocialViewModelLocationTest {
    private val zone = ZoneId.of("America/Recife")
    private val now = ZonedDateTime.of(2026, 11, 11, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val thisWeek = WeekMath.weekStartMs(now, zone)

    private val store = FakeSocialStore()

    private inner class FakeBackend(initialUid: String? = "me") : SocialBackend {
        override val uid = MutableStateFlow(initialUid)
        override val searchPrefs = MemorySearchPrefs()
        override fun session(uid: String): SocialSession {
            val publisher = SocialPublisher(store, uid, { null }, { emptyList() }, { null }, searchPrefs, { zone }) { now }
            return SocialSession(uid, store, SocialEngine(store, uid, clock = { now }, idGen = { "role-x" }), publisher)
        }
        override fun publishSoon(force: Boolean) {}
    }

    private val backend = FakeBackend()

    private fun TestScope.vm() =
        SocialViewModel(backend, backgroundScope, clock = { now }, zone = { zone }).also { it.showTab(true) }

    private fun friend(uid: String, name: String, km: Double? = null) =
        Friend(PublicProfile(uid, name, "Honda", "CG 160", null, true), km?.let { Activity(thisWeek, it) })

    @Test
    fun `toggleLocationSharing chama grantLocationPermission quando permissao ainda nao concedida`() = runTest {
        store.friendsFlow.value = listOf(friend("ana", "Ana"))
        val vm = vm()
        runCurrent()

        assertFalse(vm.grantedPermissions.value.contains("ana"))
        assertFalse(vm.state.value.friends.single().locationSharedWithHim)

        vm.toggleLocationSharing("ana")
        runCurrent()

        assertTrue(store.grantedCalls.contains("ana"))
        assertTrue(vm.grantedPermissions.value.contains("ana"))
        assertTrue(vm.state.value.friends.single().locationSharedWithHim)
    }

    @Test
    fun `toggleLocationSharing chama revokeLocationPermission quando permissao ja concedida`() = runTest {
        store.friendsFlow.value = listOf(friend("ana", "Ana"))
        store.grantedPermissions += "ana"
        store.grantedFlow.value = setOf("ana")
        val vm = vm()
        runCurrent()

        assertTrue(vm.grantedPermissions.value.contains("ana"))
        assertTrue(vm.state.value.friends.single().locationSharedWithHim)

        vm.toggleLocationSharing("ana")
        runCurrent()

        assertTrue(store.revokedCalls.contains("ana"))
        assertFalse(vm.grantedPermissions.value.contains("ana"))
        assertFalse(vm.state.value.friends.single().locationSharedWithHim)
    }

    @Test
    fun `amigos que nos concederam permissao e tem localizacao recente aparecem como na estrada`() = runTest {
        store.friendsFlow.value = listOf(friend("ana", "Ana"))
        store.receivedFlow.value = setOf("ana")
        val loc = LiveLocation(
            latitude = -8.05,
            longitude = -34.9,
            bearing = 90f,
            speedKmh = 45f,
            updatedAtMs = now - 60_000L, // 1 min atrás
        )
        val vm = vm()
        runCurrent()

        store.friendLocations.getOrPut("ana") { MutableStateFlow(null) }.value = loc
        runCurrent()

        val active = vm.activeFriendsOnRoad.value
        assertEquals(loc, active["ana"])

        val row = vm.state.value.friends.single()
        assertTrue(row.isLiveOnRoad)
        assertEquals(-8.05, row.liveLatitude)
        assertEquals(-34.9, row.liveLongitude)
    }

    @Test
    fun `amigos com localizacao expirada nao aparecem como na estrada`() = runTest {
        store.friendsFlow.value = listOf(friend("ana", "Ana"))
        store.receivedFlow.value = setOf("ana")
        val expiredLoc = LiveLocation(
            latitude = -8.05,
            longitude = -34.9,
            bearing = 90f,
            speedKmh = 0f,
            updatedAtMs = now - 6 * 60 * 1000L, // 6 min atrás (> 5 min)
        )
        val vm = vm()
        runCurrent()

        store.friendLocations.getOrPut("ana") { MutableStateFlow(null) }.value = expiredLoc
        runCurrent()

        val row = vm.state.value.friends.single()
        assertFalse(row.isLiveOnRoad)
        assertNull(row.liveLatitude)
        assertNull(row.liveLongitude)
    }

    @Test
    fun `revogacao de permissao recebida remove amigo de activeFriendsOnRoad`() = runTest {
        store.friendsFlow.value = listOf(friend("ana", "Ana"))
        store.receivedFlow.value = setOf("ana")
        val loc = LiveLocation(latitude = -8.05, longitude = -34.9, updatedAtMs = now - 10_000L)
        val vm = vm()
        runCurrent()

        store.friendLocations.getOrPut("ana") { MutableStateFlow(null) }.value = loc
        runCurrent()
        assertTrue(vm.state.value.friends.single().isLiveOnRoad)

        // Ana revoga permissão concedida a "me"
        store.receivedFlow.value = emptySet()
        runCurrent()

        assertFalse(vm.activeFriendsOnRoad.value.containsKey("ana"))
        assertFalse(vm.state.value.friends.single().isLiveOnRoad)
    }

    @Test
    fun `focusFriendOnMap emite coordenadas para o evento de foco no mapa`() = runTest {
        val vm = vm()
        runCurrent()

        vm.focusFriendOnMap(-8.05, -34.9)
        val event = vm.focusFriendOnMapEvent.first()
        assertEquals(-8.05 to -34.9, event)
    }
}
