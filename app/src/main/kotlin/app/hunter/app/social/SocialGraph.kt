package app.hunter.app.social

import android.util.Log
import app.hunter.social.SocialEngine
import app.hunter.social.SocialStore
import app.hunter.social.TripSlice
import app.hunter.sync.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

/** Tudo o que o social precisa para a conta [uid]: o store, as regras ([engine]) e o [publisher]. */
class SocialSession(
    val uid: String,
    val store: SocialStore,
    val engine: SocialEngine,
    internal val publisher: SocialPublisher,
)

/** O que o [SocialViewModel] enxerga do grafo social (uma interface para testar o ViewModel sem Android). */
interface SocialBackend {
    /** uid da conta em que o social pode rodar agora; nulo para visitante, sem Firebase ou exclusão pendente. */
    val uid: StateFlow<String?>

    val searchPrefs: SearchPrefs

    /** Sessão social de [uid], ou nulo se o social não pode rodar para ele agora. */
    fun session(uid: String): SocialSession?

    /** Publica perfil e atividade em segundo plano; [force] ignora o intervalo mínimo (mudança de opção de busca). */
    fun publishSoon(force: Boolean = false)
}

/**
 * Ponto único do social no app. O [SocialStore] real só nasce quando alguém precisa dele ([storeProvider] é chamado
 * uma vez, sob demanda) e nada acontece se o Firebase não existe ([available] falso), se não há conta logada
 * ou se uma exclusão de conta está pendente ([allowed] é o mesmo critério do agendador de sincronização).
 * A [SocialSession] é refeita quando o uid muda.
 */
class SocialGraph(
    private val available: Boolean,
    private val allowed: () -> Boolean,
    private val currentUid: () -> String?,
    override val uid: StateFlow<String?>,
    storeProvider: () -> SocialStore,
    private val readProfile: () -> UserProfile?,
    private val readTrips: suspend () -> List<TripSlice>,
    private val readCostPerKm: suspend () -> Double?,
    override val searchPrefs: SearchPrefs,
    private val scope: CoroutineScope,
    /** Lido a cada cálculo: trocar o fuso com o app aberto muda a semana. */
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String, Throwable) -> Unit = { m, e -> Log.w("Rumo/social", m, e) },
) : SocialBackend {
    private val store: SocialStore by lazy(storeProvider)

    private var cached: SocialSession? = null

    // Protegidos por [publishLock]: instante da última publicação e a conta a que ele se refere.
    private var lastPublishedAtMs = 0L
    private var lastPublishedUid: String? = null
    private val publishLock = Mutex()

    private val pendingLock = Any()
    private var pending: Job? = null

    @Synchronized
    override fun session(uid: String): SocialSession? {
        if (!available || !allowed() || currentUid() != uid) {
            cached = null
            return null
        }
        cached?.takeIf { it.uid == uid }?.let { return it }
        val publisher = SocialPublisher(
            store, uid, readProfile, readTrips, readCostPerKm, searchPrefs, zone, clock,
        )
        return SocialSession(uid, store, SocialEngine(store, uid, clock), publisher).also { cached = it }
    }

    /**
     * Agenda a publicação num escopo do app (nunca bloqueia a thread principal e nunca lança). Se o intervalo
     * mínimo ainda não venceu, a publicação é adiada para `última + 60 s` (um único adiamento pendente) em vez de
     * descartada, para o fim de uma viagem nunca ficar sem publicar. Publicações são serializadas. Se a publicação
     * falha ou é cancelada, o intervalo é zerado para o próximo gatilho tentar de novo.
     */
    override fun publishSoon(force: Boolean) {
        scope.launch { publishNow(force) }
    }

    /**
     * Apaga todos os dados sociais de [uid] (exclusão de conta) esperando qualquer publicação em andamento terminar:
     * assim ela não regrava perfil, atividade ou busca depois da limpeza. Depois a marca de exclusão (e as regras) a
     * impedem de publicar de novo.
     */
    suspend fun deleteAllSocial(uid: String) {
        publishLock.withLock { store.deleteAllSocial(uid) }
    }

    /** Volta do app para a frente: publica (respeitando o intervalo) para refletir a semana que pode ter virado. */
    fun onForeground() = publishSoon(force = false)

    private suspend fun publishNow(force: Boolean) {
        try {
            val uid = currentUid() ?: return
            val s = session(uid) ?: return
            var retryInMs: Long? = null
            publishLock.withLock {
                if (lastPublishedUid != uid) {
                    lastPublishedUid = uid
                    lastPublishedAtMs = 0L
                }
                val now = clock()
                if (!force && !s.publisher.shouldPublish(lastPublishedAtMs, now)) {
                    retryInMs = lastPublishedAtMs + SocialPublisher.MIN_INTERVAL_MS - now
                    return@withLock
                }
                lastPublishedAtMs = now
                try {
                    s.publisher.publishAll(force)
                } catch (e: Throwable) {
                    lastPublishedAtMs = 0L
                    if (e is CancellationException) throw e
                    log("Falha ao publicar perfil e atividade", e)
                }
            }
            retryInMs?.let { scheduleTrailing(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Falha ao publicar", e)
        }
    }

    private fun scheduleTrailing(delayMs: Long) {
        synchronized(pendingLock) {
            if (pending?.isActive == true) return
            pending = scope.launch {
                delay(delayMs.coerceAtLeast(0L))
                synchronized(pendingLock) { pending = null }
                publishNow(force = false)
            }
        }
    }
}
