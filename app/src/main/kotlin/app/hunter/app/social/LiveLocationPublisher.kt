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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Publicador reativo de localização geográfica para a nuvem.
 *
 * Transmite coordenadas em tempo real para [store] apenas quando:
 * 1) O Pod BLE estiver conectado ([isPodConnected]);
 * 2) Houver pelo menos um amigo autorizado ([grantedPermissions] não vazio).
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
    private val clock: () -> Long = System::currentTimeMillis,
    private val minFloorMs: Long = DEFAULT_MIN_FLOOR_MS,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val minDistanceMeters: Double = DEFAULT_MIN_DISTANCE_METERS,
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

    fun start(): Job {
        if (job?.isActive == true) return job!!
        val j = scope.launch {
            combine(isPodConnected, grantedPermissions) { connected, perms ->
                connected && perms.isNotEmpty()
            }.distinctUntilChanged().collectLatest { active ->
                if (active) {
                    wasActive = true
                    try {
                        locationSource.updates().collect { point ->
                            onLocationUpdate(point)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        log("Falha ao coletar pontos de localização", e)
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
                            log("Falha ao limpar localização após desconexão", e)
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
        val elapsed = now - lastSentMs
        val dist = lastSentPoint?.let { Geo.distanceM(it, point) } ?: Double.MAX_VALUE
        if (lastSentPoint == null || (elapsed >= minFloorMs && (elapsed >= minIntervalMs || dist >= minDistanceMeters))) {
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

        fun fromPodStatus(
            scope: CoroutineScope,
            store: SocialStore,
            locationSource: LocationSource,
            podStatus: Flow<PodUiState>,
            grantedPermissions: Flow<Set<String>>,
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
            clock = clock,
            minFloorMs = minFloorMs,
            minIntervalMs = minIntervalMs,
            minDistanceMeters = minDistanceMeters,
            log = log,
        )
    }
}
