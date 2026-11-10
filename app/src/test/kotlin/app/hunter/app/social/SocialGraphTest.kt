package app.hunter.app.social

import app.hunter.sync.UserProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class SocialGraphTest {
    private val store = FakeSocialStore()
    private var profileReads = 0
    private var storeCreated = 0
    private var signedUid: String? = "me"
    private var available = true
    private var allowed = true
    private val base = 1_000_000L
    private val logged = mutableListOf<String>()

    private object Prefs : SearchPrefs {
        override fun asked() = true
        override fun searchable() = true
        override fun set(searchable: Boolean) {}
    }

    private fun TestScope.graph() = SocialGraph(
        available = available,
        allowed = { allowed },
        currentUid = { signedUid },
        uid = MutableStateFlow(signedUid),
        storeProvider = { storeCreated++; store },
        readProfile = { profileReads++; UserProfile("Ana", null, null, null) },
        readTrips = { emptyList() },
        readCostPerKm = { null },
        searchPrefs = Prefs,
        scope = backgroundScope,
        zone = { ZoneId.of("UTC") },
        clock = { base + testScheduler.currentTime },
        log = { m, _ -> logged += m },
    )

    @Test
    fun `sessão por uid, refeita quando o uid muda e nula sem permissão`() = runTest {
        val g = graph()
        val s1 = g.session("me")!!
        assertSame(s1, g.session("me"))
        assertNull(g.session("outro")) // não é a conta logada
        signedUid = "outro"
        val s2 = g.session("outro")!!
        assertNotSame(s1, s2)
        assertEquals("outro", s2.uid)
        allowed = false
        assertNull(g.session("outro")) // exclusão pendente ou antes da retomada
    }

    @Test
    fun `sem Firebase, deslogado ou com exclusão pendente nada social roda`() = runTest {
        available = false
        val g1 = graph()
        g1.publishSoon()
        runCurrent()
        available = true
        allowed = false
        val g2 = graph()
        g2.publishSoon()
        runCurrent()
        allowed = true
        signedUid = null
        val g3 = graph()
        g3.publishSoon()
        runCurrent()
        assertEquals(0, profileReads)
        assertEquals(0, storeCreated)
    }

    @Test
    fun `rajada vira uma publicação e a seguinte é adiada para last mais 60 s`() = runTest {
        val g = graph()
        g.publishSoon()
        g.publishSoon()
        g.onForeground()
        runCurrent()
        assertEquals(1, profileReads)
        advanceTimeBy(59_000)
        runCurrent()
        assertEquals(1, profileReads)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, profileReads) // o adiamento publicou, uma vez só
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(2, profileReads)
    }

    @Test
    fun `falha zera o intervalo e o próximo gatilho tenta de novo na hora`() = runTest {
        val g = graph()
        store.profileFailure = IOException("sem rede")
        g.publishSoon()
        runCurrent()
        assertEquals(1, profileReads)
        assertEquals(1, logged.size)
        store.profileFailure = null
        g.publishSoon()
        runCurrent()
        assertEquals(2, profileReads)
        assertEquals(1, store.profileWrites)
    }

    @Test
    fun `force ignora o intervalo`() = runTest {
        val g = graph()
        g.publishSoon()
        runCurrent()
        g.publishSoon(force = true)
        runCurrent()
        assertEquals(2, profileReads)
    }
}
