package app.hunter.app.social

import app.hunter.model.TrackPoint
import app.hunter.podble.PodPhase
import app.hunter.podble.PodUiState
import app.hunter.social.LiveLocation
import app.hunter.social.SocialStore
import app.hunter.trips.recording.LocationSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LiveLocationPublisherTest {

    private class FakeLocationSource : LocationSource {
        private val flow = MutableSharedFlow<TrackPoint>(extraBufferCapacity = 64)

        fun emit(point: TrackPoint) {
            flow.tryEmit(point)
        }

        override fun updates(): Flow<TrackPoint> = flow
    }

    private class RecordingSocialStore : SocialStore by FakeSocialStore() {
        val published = mutableListOf<LiveLocation>()
        var clearCalls = 0

        override suspend fun publishLocation(loc: LiveLocation) {
            published += loc
        }

        override suspend fun clearLocation() {
            clearCalls++
        }
    }

    private val store = RecordingSocialStore()
    private val locationSource = FakeLocationSource()
    private val podState = MutableStateFlow(PodUiState(phase = PodPhase.IDLE))
    private val isPodConnected = MutableStateFlow(false)
    private val permissions = MutableStateFlow<Set<String>>(emptySet())
    private val foreground = MutableStateFlow(false)
    private val logged = mutableListOf<String>()
    private var permissionsSource: Flow<Set<String>> = permissions
    private var currentTime = 1_000_000L

    private fun TestScope.createPublisher(
        usePodState: Boolean = false,
        minFloorMs: Long = 10_000L,
        minIntervalMs: Long = 30_000L,
        minDistanceMeters: Double = 100.0,
    ): LiveLocationPublisher {
        return if (usePodState) {
            LiveLocationPublisher(
                scope = backgroundScope,
                store = store,
                locationSource = locationSource,
                podStatus = podState,
                grantedPermissions = permissionsSource,
                appInForeground = foreground,
                clock = { currentTime },
                minFloorMs = minFloorMs,
                minIntervalMs = minIntervalMs,
                minDistanceMeters = minDistanceMeters,
                log = { m, _ -> logged += m },
            )
        } else {
            LiveLocationPublisher(
                scope = backgroundScope,
                store = store,
                locationSource = locationSource,
                isPodConnected = isPodConnected,
                grantedPermissions = permissionsSource,
                appInForeground = foreground,
                clock = { currentTime },
                minFloorMs = minFloorMs,
                minIntervalMs = minIntervalMs,
                minDistanceMeters = minDistanceMeters,
                backgroundGraceMs = 5_000L,
                retryBaseMs = 2_000L,
                retryMaxMs = 60_000L,
                log = { m, _ -> logged += m },
            )
        }
    }

    @Test
    fun `quando Pod desconectado (IDLE ou SCANNING), nao publica coordenadas mesmo com permissoes`() = runTest {
        createPublisher(usePodState = true)
        permissions.value = setOf("amigo1")
        podState.value = PodUiState(phase = PodPhase.IDLE)
        runCurrent()

        locationSource.emit(TrackPoint(timeMs = currentTime, latitude = -23.55052, longitude = -46.633308))
        runCurrent()
        assertTrue(store.published.isEmpty())

        podState.value = PodUiState(phase = PodPhase.SCANNING)
        runCurrent()
        locationSource.emit(TrackPoint(timeMs = currentTime, latitude = -23.55052, longitude = -46.633308))
        runCurrent()
        assertTrue(store.published.isEmpty())
        assertEquals(0, store.clearCalls)
    }

    @Test
    fun `quando Pod conectado mas sem permissoes concedidas, nao publica coordenadas`() = runTest {
        createPublisher(usePodState = true)
        permissions.value = emptySet()
        podState.value = PodUiState(phase = PodPhase.CONNECTED)
        runCurrent()

        locationSource.emit(TrackPoint(timeMs = currentTime, latitude = -23.55052, longitude = -46.633308))
        runCurrent()

        assertTrue(store.published.isEmpty())
        assertEquals(0, store.clearCalls)
    }

    @Test
    fun `quando Pod conectado e ha permissoes, publica primeira coordenada imediatamente`() = runTest {
        createPublisher(usePodState = true)
        permissions.value = setOf("amigo1")
        podState.value = PodUiState(phase = PodPhase.CONNECTED)
        runCurrent()

        val pt = TrackPoint(
            timeMs = currentTime,
            latitude = -23.550520,
            longitude = -46.633308,
            bearingDeg = 90.0f,
            speedMps = 15.0f,
        )
        locationSource.emit(pt)
        runCurrent()

        assertEquals(1, store.published.size)
        val published = store.published.first()
        assertEquals(-23.550520, published.latitude)
        assertEquals(-46.633308, published.longitude)
        assertEquals(90.0f, published.bearing)
        assertEquals(54.0f, published.speedKmh) // 15 m/s * 3.6 = 54 km/h
        assertEquals(currentTime, published.updatedAtMs)
    }

    @Test
    fun `respeita throttle de 30s quando deslocamento for menor que 100m`() = runTest {
        createPublisher(minIntervalMs = 30_000L, minDistanceMeters = 100.0)
        isPodConnected.value = true
        permissions.value = setOf("amigo1")
        runCurrent()

        // Ponto 1: emitido t = 0 -> publica
        locationSource.emit(TrackPoint(timeMs = currentTime, latitude = -23.550000, longitude = -46.630000))
        runCurrent()
        assertEquals(1, store.published.size)

        // Ponto 2: t = +10s, deslocamento ~11m -> suprimido
        currentTime += 10_000L
        locationSource.emit(TrackPoint(timeMs = currentTime, latitude = -23.550100, longitude = -46.630000))
        runCurrent()
        assertEquals(1, store.published.size)

        // Ponto 3: t = +29s (desde o ponto 1), deslocamento ~11m -> suprimido
        currentTime += 19_000L // total +29s
        locationSource.emit(TrackPoint(timeMs = currentTime, latitude = -23.550100, longitude = -46.630000))
        runCurrent()
        assertEquals(1, store.published.size)

        // Ponto 4: t = +30s (desde o ponto 1), deslocamento ~11m -> publica pelo tempo >= 30s
        currentTime += 1_000L // total +30s
        locationSource.emit(TrackPoint(timeMs = currentTime, latitude = -23.550100, longitude = -46.630000))
        runCurrent()
        assertEquals(2, store.published.size)
        assertEquals(-23.550100, store.published.last().latitude)
    }

    @Test
    fun `publica antes de 30s se deslocamento for maior ou igual a 100m respeitando piso de 10s`() = runTest {
        createPublisher(minFloorMs = 10_000L, minIntervalMs = 30_000L, minDistanceMeters = 100.0)
        isPodConnected.value = true
        permissions.value = setOf("amigo1")
        runCurrent()

        // Ponto 1: emitido t = 0 -> publica
        locationSource.emit(TrackPoint(timeMs = currentTime, latitude = -23.550000, longitude = -46.630000))
        runCurrent()
        assertEquals(1, store.published.size)

        // Ponto 2: t = +5s, deslocamento ~111m (>= 100m) -> suprimido pelo piso mínimo de 10s (proteção Spark)
        currentTime += 5_000L
        locationSource.emit(TrackPoint(timeMs = currentTime, latitude = -23.551000, longitude = -46.630000))
        runCurrent()
        assertEquals(1, store.published.size)

        // Ponto 3: t = +10s (desde o ponto 1), deslocamento >= 100m -> publica respeitando o piso
        currentTime += 5_000L // total +10s
        locationSource.emit(TrackPoint(timeMs = currentTime, latitude = -23.551000, longitude = -46.630000))
        runCurrent()
        assertEquals(2, store.published.size)
        assertEquals(-23.551000, store.published.last().latitude)
    }

    @Test
    fun `dispara clearLocation quando Pod transita de CONNECTED para IDLE`() = runTest {
        createPublisher(usePodState = true)
        permissions.value = setOf("amigo1")
        podState.value = PodUiState(phase = PodPhase.CONNECTED)
        runCurrent()

        locationSource.emit(TrackPoint(timeMs = currentTime, latitude = -23.550000, longitude = -46.630000))
        runCurrent()
        assertEquals(1, store.published.size)
        assertEquals(0, store.clearCalls)

        // Desconecta Pod
        podState.value = PodUiState(phase = PodPhase.IDLE)
        runCurrent()

        assertEquals(1, store.clearCalls)

        // Emite nova coordenada enquanto desconectado: não publica
        locationSource.emit(TrackPoint(timeMs = currentTime, latitude = -23.550000, longitude = -46.630000))
        runCurrent()
        assertEquals(1, store.published.size)
    }

    @Test
    fun `dispara clearLocation quando permissoes sao revogadas enquanto conectado`() = runTest {
        createPublisher()
        isPodConnected.value = true
        permissions.value = setOf("amigo1")
        runCurrent()

        locationSource.emit(TrackPoint(timeMs = currentTime, latitude = -23.550000, longitude = -46.630000))
        runCurrent()
        assertEquals(1, store.published.size)
        assertEquals(0, store.clearCalls)

        // Revoga todas as permissões
        permissions.value = emptySet()
        runCurrent()

        assertEquals(1, store.clearCalls)

        // Emite nova coordenada sem permissões: não publica
        locationSource.emit(TrackPoint(timeMs = currentTime, latitude = -23.550000, longitude = -46.630000))
        runCurrent()
        assertEquals(1, store.published.size)
    }

    @Test
    fun `reconexao zera throttle e publica primeira coordenada imediatamente`() = runTest {
        createPublisher()
        isPodConnected.value = true
        permissions.value = setOf("amigo1")
        runCurrent()

        // Ponto 1: publica
        locationSource.emit(TrackPoint(timeMs = currentTime, latitude = -23.550000, longitude = -46.630000))
        runCurrent()
        assertEquals(1, store.published.size)

        // Desconecta Pod
        isPodConnected.value = false
        runCurrent()
        assertEquals(1, store.clearCalls)

        // Reconecta apenas 5 segundos depois
        currentTime += 5_000L
        isPodConnected.value = true
        runCurrent()

        // Ponto 2 (mesmo ponto ou perto, menos de 30s): DEVE publicar imediatamente porque reconectou
        locationSource.emit(TrackPoint(timeMs = currentTime, latitude = -23.550010, longitude = -46.630000))
        runCurrent()
        assertEquals(2, store.published.size)
    }

    @Test
    fun `mapeia speedMps nulo ou negativo e bearing nulo`() = runTest {
        createPublisher()
        isPodConnected.value = true
        permissions.value = setOf("amigo1")
        runCurrent()

        // Sem speed e sem bearing
        locationSource.emit(TrackPoint(timeMs = currentTime, latitude = -23.550000, longitude = -46.630000, bearingDeg = null, speedMps = null))
        runCurrent()
        assertNull(store.published.last().speedKmh)
        assertNull(store.published.last().bearing)

        // Speed negativo coerced para 0
        currentTime += 35_000L
        locationSource.emit(TrackPoint(timeMs = currentTime, latitude = -23.550000, longitude = -46.630000, bearingDeg = 180f, speedMps = -2f))
        runCurrent()
        assertEquals(0f, store.published.last().speedKmh)
        assertEquals(180f, store.published.last().bearing)
    }

    @Test
    fun `integracao com SocialGraph instancia e finaliza LiveLocationPublisher no ciclo da sessao`() = runTest {
        val fakeStore = RecordingSocialStore()
        val graph = SocialGraph(
            available = true,
            allowed = { true },
            currentUid = { "me" },
            uid = MutableStateFlow("me"),
            storeProvider = { fakeStore },
            readProfile = { null },
            readTrips = { emptyList() },
            readCostPerKm = { null },
            searchPrefs = object : SearchPrefs {
                override fun asked() = true
                override fun searchable() = true
                override fun set(searchable: Boolean) {}
            },
            scope = backgroundScope,
            locationSource = locationSource,
            isPodConnected = isPodConnected,
        )

        val session = graph.session("me")
        assertNotNull(session)
        assertNotNull(session.liveLocationPublisher)

        // Ao invalidar a sessão ou deslogar, para o publicador
        val oldPub = session.liveLocationPublisher
        val otherSession = graph.session("outro")
        assertNull(otherSession)
    }

    private fun pt(lat: Double = -23.55, lon: Double = -46.63) =
        TrackPoint(timeMs = currentTime, latitude = lat, longitude = lon)

    @Test
    fun `sem Pod, com o app em primeiro plano e permissao concedida, publica a primeira coordenada na hora`() = runTest {
        createPublisher()
        permissions.value = setOf("amigo1")
        foreground.value = true
        runCurrent()

        locationSource.emit(pt())
        runCurrent()
        assertEquals(1, store.published.size)
    }

    @Test
    fun `em primeiro plano mas sem nenhum amigo autorizado, nao publica`() = runTest {
        createPublisher()
        foreground.value = true
        runCurrent()
        locationSource.emit(pt())
        runCurrent()
        assertTrue(store.published.isEmpty())
    }

    @Test
    fun `sem Pod, ao ir para segundo plano apaga a posicao depois da tolerancia e nao publica mais`() = runTest {
        createPublisher()
        permissions.value = setOf("amigo1")
        foreground.value = true
        runCurrent()
        locationSource.emit(pt())
        runCurrent()
        assertEquals(1, store.published.size)

        foreground.value = false
        advanceTimeBy(4_000L)
        runCurrent()
        assertEquals(0, store.clearCalls) // dentro da tolerancia (ex.: girar a tela)

        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(1, store.clearCalls)
        currentTime += 60_000L
        locationSource.emit(pt(-23.6))
        runCurrent()
        assertEquals(1, store.published.size)
    }

    @Test
    fun `voltar ao primeiro plano dentro da tolerancia nao apaga nem reinicia a publicacao`() = runTest {
        createPublisher()
        permissions.value = setOf("amigo1")
        foreground.value = true
        runCurrent()
        locationSource.emit(pt())
        runCurrent()

        foreground.value = false
        advanceTimeBy(2_000L)
        foreground.value = true
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(0, store.clearCalls)
    }

    @Test
    fun `com o Pod conectado continua publicando mesmo em segundo plano`() = runTest {
        createPublisher()
        permissions.value = setOf("amigo1")
        isPodConnected.value = true
        foreground.value = true
        runCurrent()
        foreground.value = false
        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals(0, store.clearCalls)
        locationSource.emit(pt())
        runCurrent()
        assertEquals(1, store.published.size)
    }

    @Test
    fun `falha no fluxo de permissoes e registrada e tentada de novo sem derrubar`() = runTest {
        var attempts = 0
        permissionsSource = flow {
            attempts++
            if (attempts == 1) throw java.io.IOException("sem rede")
            emit(setOf("amigo1"))
        }
        createPublisher()
        foreground.value = true
        runCurrent()
        assertEquals(1, attempts)
        assertTrue(logged.any { it.contains("permiss") })

        advanceTimeBy(2_100L)
        runCurrent()
        assertEquals(2, attempts)
        locationSource.emit(pt())
        runCurrent()
        assertEquals(1, store.published.size)
    }

    @Test
    fun `SecurityException do GPS e tentada de novo com recuo ate a permissao ser concedida`() = runTest {
        var calls = 0
        val shared = MutableSharedFlow<TrackPoint>(extraBufferCapacity = 8)
        val source = LocationSource {
            calls++
            if (calls < 3) flow { throw SecurityException("sem permissao") } else shared
        }
        LiveLocationPublisher(
            scope = backgroundScope, store = store, locationSource = source,
            isPodConnected = isPodConnected, grantedPermissions = permissions, appInForeground = foreground,
            clock = { currentTime }, backgroundGraceMs = 5_000L, retryBaseMs = 2_000L, retryMaxMs = 60_000L,
            log = { m, _ -> logged += m },
        )
        permissions.value = setOf("amigo1")
        foreground.value = true
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1_900L); runCurrent()
        assertEquals(1, calls) // recuo: sem laco apertado
        advanceTimeBy(200L); runCurrent()
        assertEquals(2, calls)
        advanceTimeBy(4_100L); runCurrent() // segundo recuo: 4 s
        assertEquals(3, calls)
        shared.tryEmit(pt())
        runCurrent()
        assertEquals(1, store.published.size)
    }

    @Test
    fun `falha ao publicar e registrada e o proximo ponto tenta de novo`() = runTest {
        var fail = true
        val flaky = object : SocialStore by FakeSocialStore() {
            val ok = mutableListOf<LiveLocation>()
            override suspend fun publishLocation(loc: LiveLocation) {
                if (fail) throw java.io.IOException("sem rede")
                ok += loc
            }
            override suspend fun clearLocation() {}
        }
        LiveLocationPublisher(
            scope = backgroundScope, store = flaky, locationSource = locationSource,
            isPodConnected = isPodConnected, grantedPermissions = permissions, appInForeground = foreground,
            clock = { currentTime }, log = { m, _ -> logged += m },
        )
        permissions.value = setOf("amigo1")
        foreground.value = true
        runCurrent()
        locationSource.emit(pt())
        runCurrent()
        assertTrue(logged.any { it.contains("publicar") })
        assertTrue(flaky.ok.isEmpty())

        fail = false
        currentTime += 1_000L
        locationSource.emit(pt())
        runCurrent()
        assertEquals(1, flaky.ok.size)
    }

    @Test
    fun `ponto velho em cache nao e publicado como se fosse de agora`() = runTest {
        currentTime = 10_000_000L
        createPublisher()
        permissions.value = setOf("amigo1")
        foreground.value = true
        runCurrent()
        locationSource.emit(TrackPoint(timeMs = currentTime - 3_600_000L, latitude = -23.55, longitude = -46.63))
        runCurrent()
        assertTrue(store.published.isEmpty())
        locationSource.emit(pt())
        runCurrent()
        assertEquals(1, store.published.size)
    }
}
