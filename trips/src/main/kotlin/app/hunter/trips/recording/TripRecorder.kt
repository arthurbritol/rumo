package app.hunter.trips.recording

import app.hunter.model.TrackPoint
import app.hunter.model.TrackingConfig
import app.hunter.model.TripStats
import app.hunter.model.TripStatsAccumulator
import app.hunter.trips.data.TripStore

/** Estado publicado para a UI/notificação enquanto grava. */
data class RecordingState(
    /** null quando não há gravação. */
    val tripId: Long? = null,
    val startedAtMs: Long = 0,
    val stats: TripStats = TripStats(),
    val lastPoint: TrackPoint? = null,
) {
    val isRecording: Boolean get() = tripId != null
}

/**
 * Orquestra a gravação de um trajeto: filtra pontos ([TripStatsAccumulator]), persiste cada ponto aceito e
 * atualiza as estatísticas no banco a cada [statsFlushEvery] pontos. Sem dependência de Android; não é
 * thread-safe (usar de uma coroutine só, como o serviço faz).
 */
class TripRecorder(
    private val store: TripStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val config: TrackingConfig = TrackingConfig(),
    private val statsFlushEvery: Int = 5,
    private val onState: (RecordingState) -> Unit = {},
) {
    private var tripId: Long? = null
    private var startedAtMs = 0L
    private var acc = TripStatsAccumulator(config)
    private var nextIndex = 0
    private var sinceFlush = 0
    private var lastPoint: TrackPoint? = null

    val isRecording: Boolean get() = tripId != null

    /** Retoma o trajeto aberto (processo morto no meio) ou cria um novo. @return id do trajeto. */
    suspend fun startOrResume(): Long {
        tripId?.let { return it }
        val active = store.activeTrip()
        val id: Long
        if (active != null) {
            val saved = store.points(active.id)
            acc = TripStatsAccumulator.fromPoints(saved, config)
            nextIndex = saved.size
            startedAtMs = active.startedAtMs
            lastPoint = saved.lastOrNull()
            id = active.id
        } else {
            acc = TripStatsAccumulator(config)
            nextIndex = 0
            startedAtMs = clock()
            lastPoint = null
            id = store.createTrip(startedAtMs)
        }
        tripId = id
        sinceFlush = 0
        publish()
        return id
    }

    /** Processa um ponto de GPS. Pontos rejeitados pelos filtros são ignorados e não vão ao banco. */
    suspend fun onPoint(point: TrackPoint) {
        val id = tripId ?: return
        if (!acc.add(point)) return
        store.appendPoint(id, nextIndex++, point)
        lastPoint = point
        if (++sinceFlush >= statsFlushEvery) {
            store.updateStats(id, acc.snapshot())
            sinceFlush = 0
        }
        publish()
    }

    /**
     * Encerra o trajeto. Um trajeto sem nenhum ponto aceito é descartado.
     * @return estatísticas finais, ou null se foi descartado / não havia gravação.
     */
    suspend fun finish(): TripStats? {
        val id = tripId ?: return null
        val stats = acc.snapshot()
        val kept = acc.acceptedCount > 0
        if (kept) store.finishTrip(id, clock(), stats) else store.deleteTrip(id)
        tripId = null
        lastPoint = null
        onState(RecordingState())
        return if (kept) stats else null
    }

    private fun publish() {
        onState(RecordingState(tripId, startedAtMs, acc.snapshot(), lastPoint))
    }
}
