package app.hunter.app.social

import android.util.Log
import app.hunter.model.Geo
import app.hunter.model.TrackPoint
import app.hunter.podble.PodPhase
import app.hunter.podble.PodUiState
import app.hunter.social.LiveLocation
import app.hunter.social.SocialStore
import app.hunter.trips.recording.LocationSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

/**
 * Publicador reativo de localização geográfica para a nuvem.
 *
 * Transmite coordenadas em tempo real para [store] apenas quando:
 * 1) O Pod BLE estiver conectado ([isPodConnected]) OU o app estiver aberto, em primeiro plano
 *    ([appInForeground], com o GPS do celular; nunca em segundo plano sem o Pod, e sem pedir localização em
 *    segundo plano);
 * 2) Houver pelo menos um amigo autorizado ([grantedPermissions] não vazio).
 *
 * Ao ir para segundo plano sem o Pod, espera [backgroundGraceMs] (girar a tela recria a Activity e passa por
 * "segundo plano" por um instante) e então para e apaga a posição.
 *
 * Falhas (fluxo de permissões, GPS sem permissão, rede) são registradas em [log] e tentadas de novo com recuo
 * exponencial (de [retryBaseMs] até [retryMaxMs]), sem laço apertado e sem derrubar o processo.
 *
 * Filtra as leituras de [locationSource] para não sobrecarregar a cota da nuvem nem a bateria:
 * - Garante um piso temporal mínimo de [minFloorMs] (padrão: 10 s) para proteger a cota diária do plano Spark
 *   mesmo em alta velocidade (ex: 120 km/h).
 * - Só envia novo ponto se passaram >= [minIntervalMs] (padrão: 30 s) OU se o deslocamento
 *   for >= [minDistanceMeters] (padrão: 100 m), desde que respeitado o piso [minFloorMs].
 *
 * Ao desconectar do Pod ou revogar todas as permissões, cancela a coleta e invoca
 * [SocialStore.clearLocation] para que a moto não continue visível como ativa.
 */
class LiveLocationPublisher(
    private val scope: CoroutineScope,
    private val store: SocialStore,
    private val locationSource: LocationSource,
    private val isPodConnected: Flow<Boolean>,
    private val grantedPermissions: Flow<Set<String>>,
    private val appInForeground: Flow<Boolean> = flowOf(false),
    private val clock: () -> Long = System::currentTimeMillis,
    private val minFloorMs: Long = DEFAULT_MIN_FLOOR_MS,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val minDistanceMeters: Double = DEFAULT_MIN_DISTANCE_METERS,
    private val backgroundGraceMs: Long = DEFAULT_BACKGROUND_GRACE_MS,
    private val retryBaseMs: Long = DEFAULT_RETRY_BASE_MS,
    private val retryMaxMs: Long = DEFAULT_RETRY_MAX_MS,
    /** Parado (velocidade conhecida < 1 m/s e sem sair do lugar) publica só a cada [stationaryRefreshMs] (a validade é 5 min). */
    private val stationaryRefreshMs: Long = DEFAULT_STATIONARY_REFRESH_MS,
    /** Relógio monotônico (para a idade do ponto via `elapsedRealtimeNanos`); só é lido se o ponto traz esse campo. */
    private val monotonicNanos: () -> Long = { android.os.SystemClock.elapsedRealtimeNanos() },
    private val log: (String, Throwable?) -> Unit = { msg, err ->
        if (err != null) Log.w(TAG, msg, err) else Log.d(TAG, msg)
    },
) {
    constructor(
        scope: CoroutineScope,
        store: SocialStore,
        locationSource: LocationSource,
        podStatus: StateFlow<PodUiState>,
        grantedPermissions: Flow<Set<String>>,
        appInForeground: Flow<Boolean> = flowOf(false),
        clock: () -> Long = System::currentTimeMillis,
        minFloorMs: Long = DEFAULT_MIN_FLOOR_MS,
        minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
        minDistanceMeters: Double = DEFAULT_MIN_DISTANCE_METERS,
        log: (String, Throwable?) -> Unit = { msg, err ->
            if (err != null) Log.w(TAG, msg, err) else Log.d(TAG, msg)
        },
    ) : this(
        scope = scope,
        store = store,
        locationSource = locationSource,
        isPodConnected = podStatus.map { it.phase == PodPhase.CONNECTED },
        grantedPermissions = grantedPermissions,
        appInForeground = appInForeground,
        clock = clock,
        minFloorMs = minFloorMs,
        minIntervalMs = minIntervalMs,
        minDistanceMeters = minDistanceMeters,
        log = log,
    )

    private var job: Job? = null

    @Volatile
    private var wasActive = false

    @Volatile
    private var lastSentMs = 0L

    @Volatile
    private var lastSentPoint: TrackPoint? = null

    private fun backoffMs(attempt: Int): Long {
        val shifted = if (attempt >= 20) Long.MAX_VALUE else retryBaseMs shl attempt
        return if (shifted <= 0L) retryMaxMs else shifted.coerceAtMost(retryMaxMs)
    }

    /** Primeiro plano com tolerância ao sair: só vira falso depois de [backgroundGraceMs] contínuos. */
    private fun foregroundWithGrace(): Flow<Boolean> {
        var last = false
        return appInForeground.distinctUntilChanged().transformLatest { fg ->
            if (!fg && last) delay(backgroundGraceMs)
            last = fg
            emit(fg)
        }
    }

    fun start(): Job {
        if (job?.isActive == true) return job!!
        val safePermissions = grantedPermissions.retryWhen { cause, attempt ->
            if (cause is CancellationException) return@retryWhen false
            log("Falha ao observar permissões de localização; tentando de novo", cause)
            delay(backoffMs(attempt.coerceAtMost(30L).toInt()))
            true
        }
        val j = scope.launch {
            combine(isPodConnected, foregroundWithGrace(), safePermissions) { pod, fg, perms ->
                (pod || fg) && perms.isNotEmpty()
            }.distinctUntilChanged().collectLatest { active ->
                if (active) {
                    wasActive = true
                    var attempt = 0
                    while (true) {
                        try {
                            locationSource.updates().collect { point ->
                                attempt = 0
                                onLocationUpdate(point)
                            }
                            break
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            // SecurityException (permissão de GPS ainda não concedida) também cai aqui.
                            log("Falha ao coletar pontos de localização; tentando de novo", e)
                            delay(backoffMs(attempt))
                            attempt++
                        }
                    }
                } else {
                    if (wasActive) {
                        wasActive = false
                        lastSentMs = 0L
                        lastSentPoint = null
                        try {
                            store.clearLocation()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            log("Falha ao limpar localização ao parar de compartilhar", e)
                        }
                    }
                }
            }
        }
        job = j
        return j
    }

    private suspend fun onLocationUpdate(point: TrackPoint) {
        val now = clock()
        // Posição em cache (última conhecida da rede, de horas atrás) não pode sair com a hora de agora.
        // A idade vem do relógio monotônico do ponto; sem ele, da hora do aparelho.
        val ageMs = point.elapsedRealtimeNanos?.let { (monotonicNanos() - it) / 1_000_000L }
            ?: if (point.timeMs > 0L) now - point.timeMs else 0L
        if (ageMs > MAX_POINT_AGE_MS) return
        // Posição de rede muito imprecisa (antena distante) não vale a pena.
        if ((point.accuracyM ?: 0f) > MAX_ACCURACY_M) return
        val elapsed = now - lastSentMs
        val dist = lastSentPoint?.let { Geo.distanceM(it, point) } ?: Double.MAX_VALUE
        val stationary = lastSentPoint != null && dist < minDistanceMeters && (point.speedMps ?: Float.MAX_VALUE).let { it >= 0f && it < STATIONARY_MPS }
        val dueByTime = if (stationary) elapsed >= stationaryRefreshMs else elapsed >= minIntervalMs
        if (lastSentPoint == null || (elapsed >= minFloorMs && (dueByTime || dist >= minDistanceMeters))) {
            val liveLoc = LiveLocation(
                latitude = point.latitude,
                longitude = point.longitude,
                bearing = point.bearingDeg,
                speedKmh = point.speedMps?.let { (it * 3.6f).coerceAtLeast(0f) },
                updatedAtMs = now,
            )
            try {
                store.publishLocation(liveLoc)
                lastSentMs = now
                lastSentPoint = point
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log("Falha ao publicar coordenada ativa", e)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        if (wasActive) {
            wasActive = false
            lastSentMs = 0L
            lastSentPoint = null
            scope.launch {
                try {
                    store.clearLocation()
                } catch (e: CancellationException) {
                    // Ignora cancelamento no escopo
                } catch (e: Throwable) {
                    log("Falha ao limpar localização no stop", e)
                }
            }
        }
    }

    init {
        start()
    }

    companion object {
        private const val TAG = "Rumo/LiveLoc"
        const val DEFAULT_MIN_FLOOR_MS = 10_000L
        const val DEFAULT_MIN_INTERVAL_MS = 30_000L
        const val DEFAULT_MIN_DISTANCE_METERS = 100.0
        /** Pontos mais velhos que isso (relógio do aparelho) não são publicados. */
        const val MAX_POINT_AGE_MS = 120_000L
        const val MAX_ACCURACY_M = 1_000f
        const val STATIONARY_MPS = 1f
        const val DEFAULT_STATIONARY_REFRESH_MS = 240_000L
        const val DEFAULT_BACKGROUND_GRACE_MS = 5_000L
        const val DEFAULT_RETRY_BASE_MS = 2_000L
        const val DEFAULT_RETRY_MAX_MS = 60_000L

        fun fromPodStatus(
            scope: CoroutineScope,
            store: SocialStore,
            locationSource: LocationSource,
            podStatus: Flow<PodUiState>,
            grantedPermissions: Flow<Set<String>>,
            appInForeground: Flow<Boolean> = flowOf(false),
            clock: () -> Long = System::currentTimeMillis,
            minFloorMs: Long = DEFAULT_MIN_FLOOR_MS,
            minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
            minDistanceMeters: Double = DEFAULT_MIN_DISTANCE_METERS,
            log: (String, Throwable?) -> Unit = { msg, err ->
                if (err != null) Log.w(TAG, msg, err) else Log.d(TAG, msg)
            },
        ): LiveLocationPublisher = LiveLocationPublisher(
            scope = scope,
            store = store,
            locationSource = locationSource,
            isPodConnected = podStatus.map { it.phase == PodPhase.CONNECTED },
            grantedPermissions = grantedPermissions,
            appInForeground = appInForeground,
            clock = clock,
            minFloorMs = minFloorMs,
            minIntervalMs = minIntervalMs,
            minDistanceMeters = minDistanceMeters,
            log = log,
        )
    }
}
