package app.hunter.app.social

import app.hunter.sync.UserProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import app.hunter.model.TrackPoint
import app.hunter.social.Friend
import app.hunter.social.PublicProfile
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.launch

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
    private var tripsGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    private object Prefs : SearchPrefs {
        override fun asked() = true
        override fun searchable() = true
        override fun set(searchable: Boolean) {}
    }

    private fun TestScope.graph(
        uidFlow: MutableStateFlow<String?> = MutableStateFlow(signedUid),
        locationSource: app.hunter.trips.recording.LocationSource? = null,
    ) = SocialGraph(
        available = available,
        allowed = { allowed },
        currentUid = { signedUid },
        uid = uidFlow,
        locationSource = locationSource,
        isPodConnected = MutableStateFlow(false),
        storeProvider = { storeCreated++; store },
        readProfile = { profileReads++; UserProfile("Ana", null, null, null) },
        readTrips = { tripsGate?.await(); emptyList() },
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
        g.publishSoon()
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
    fun `apagar os dados sociais espera a publicação em andamento terminar`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        tripsGate = gate
        val g = graph()
        g.publishSoon()
        runCurrent() // a publicação segura o trinco, parada na leitura das viagens
        val deleting = launch { g.deleteAllSocial("me") }
        runCurrent()
        assertTrue(store.deletedAll.isEmpty())
        gate.complete(Unit)
        runCurrent()
        deleting.join()
        assertEquals(listOf("me"), store.deletedAll)
        assertTrue("me" in store.activities) // a publicação terminou ANTES da limpeza
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

    @Test
    fun `visto por ultimo e gravado ao voltar a frente e renovado a cada minuto enquanto na frente`() = runTest {
        val g = graph()
        g.setAppForeground(true)
        runCurrent()
        assertEquals(listOf(base), store.lastSeenWrites)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, store.lastSeenWrites.size)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(3, store.lastSeenWrites.size)
        g.setAppForeground(true) // ON_START repetido nao cria um segundo laco
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(4, store.lastSeenWrites.size)
    }

    @Test
    fun `em segundo plano nao grava visto por ultimo a partir de publishSoon, so uma vez ao sair`() = runTest {
        val g = graph()
        g.setAppForeground(true)
        runCurrent()
        g.setAppForeground(false)
        advanceTimeBy(3_100)
        runCurrent()
        assertEquals(2, store.lastSeenWrites.size) // frente + saida

        advanceTimeBy(200_000)
        g.publishSoon()
        g.publishSoon(force = true)
        runCurrent()
        assertEquals(2, store.lastSeenWrites.size) // o laco parou e publishSoon nao grava
    }

    @Test
    fun `girar a tela (sair e voltar logo) nao grava a saida`() = runTest {
        val g = graph()
        g.setAppForeground(true)
        runCurrent()
        g.setAppForeground(false)
        advanceTimeBy(1_000)
        g.setAppForeground(true)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, store.lastSeenWrites.size)
    }

    @Test
    fun `falha ao gravar visto por ultimo (regras nao publicadas) nao impede perfil e atividade`() = runTest {
        store.presenceFailure = IOException("PERMISSION_DENIED")
        val g = graph()
        g.setAppForeground(true)
        g.publishSoon()
        runCurrent()
        assertEquals(1, store.profileWrites)
        assertEquals(1, store.activityWrites)
        assertTrue(logged.any { it.contains("visto por último") })
    }

    @Test
    fun `sair da conta para a publicacao, limpa posicao e visto por ultimo (ainda logado)`() = runTest {
        val g = graph()
        val s = g.session("me")!!
        g.setAppForeground(true)
        runCurrent()
        g.stopSharing()
        assertEquals(1, store.clearLocationCalls)
        assertEquals(1, store.clearLastSeenCalls)
        advanceTimeBy(200_000)
        runCurrent()
        assertEquals(1, store.lastSeenWrites.size) // o laco de presenca parou
        assertNotNull(s)
    }

    @Test
    fun `sessao em cache cai quando o uid vira nulo`() = runTest {
        val uidFlow = MutableStateFlow<String?>("me")
        val src = LocationSourceStub()
        val g = graph(uidFlow, src)
        val s1 = g.session("me")!!
        runCurrent()
        uidFlow.value = null
        runCurrent()
        val s2 = g.session("me")!!
        assertNotSame(s1, s2) // refeita: o publicador antigo foi parado e descartado
    }

    @Test
    fun `permissao de quem ja nao e amigo nao mantem a publicacao`() = runTest {
        val src = LocationSourceStub()
        store.friendsFlow.value = listOf(
            Friend(PublicProfile("ana", "Ana", null, null, null, true), null),
        )
        store.grantedPermissions += "ana"
        store.grantedFlow.value = setOf("ana")
        val g = graph(locationSource = src)
        g.session("me")
        g.setAppForeground(true)
        runCurrent()
        src.flow.tryEmit(TrackPoint(timeMs = base + testScheduler.currentTime, latitude = -8.0, longitude = -35.0))
        runCurrent()
        assertEquals(1, store.publishedLocations.size)

        store.unfriend("me", "ana") // amizade desfeita: some a permissao e o amigo
        runCurrent()
        assertEquals(1, store.clearLocationCalls)

        // permissao orfa (sobrou so a autorizacao, sem amizade): continua sem publicar
        store.grantedPermissions += "ana"
        store.grantedFlow.value = setOf("ana")
        runCurrent()
        src.flow.tryEmit(TrackPoint(timeMs = base + testScheduler.currentTime, latitude = -8.1, longitude = -35.0))
        runCurrent()
        assertEquals(1, store.publishedLocations.size)
    }

    private class LocationSourceStub : app.hunter.trips.recording.LocationSource {
        val flow = kotlinx.coroutines.flow.MutableSharedFlow<TrackPoint>(extraBufferCapacity = 8)
        override fun updates() = flow
    }
}
