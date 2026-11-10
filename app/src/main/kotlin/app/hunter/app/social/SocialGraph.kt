package app.hunter.app.social

import android.util.Log
import app.hunter.social.SocialEngine
import app.hunter.social.SocialStore
import app.hunter.social.TripSlice
import app.hunter.sync.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
) : SocialBackend {
    private val store: SocialStore by lazy(storeProvider)

    private var cached: SocialSession? = null
    private var lastPublishedAtMs = 0L
    private val publishLock = Mutex()

    @Synchronized
    override fun session(uid: String): SocialSession? {
        if (!available || !allowed() || currentUid() != uid) {
            cached = null
            return null
        }
        cached?.takeIf { it.uid == uid }?.let { return it }
        lastPublishedAtMs = 0L
        val publisher = SocialPublisher(
            store, uid, readProfile, readTrips, readCostPerKm, searchPrefs::searchable, zone, clock,
        )
        return SocialSession(uid, store, SocialEngine(store, uid, clock), publisher).also { cached = it }
    }

    /**
     * Agenda a publicação num escopo do app (nunca bloqueia a thread principal e nunca lança). Respeita
     * [SocialPublisher.shouldPublish]; publicações simultâneas são serializadas, então uma rajada de gatilhos vira
     * uma só. Se a publicação falha (sem rede), o intervalo é zerado para o próximo gatilho tentar de novo.
     */
    override fun publishSoon(force: Boolean) {
        scope.launch {
            try {
                val uid = currentUid() ?: return@launch
                val s = session(uid) ?: return@launch
                publishLock.withLock {
                    val now = clock()
                    if (!force && !s.publisher.shouldPublish(lastPublishedAtMs, now)) return@withLock
                    lastPublishedAtMs = now
                    try {
                        s.publisher.publishAll()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastPublishedAtMs = 0L
                        Log.w("Rumo/social", "Falha ao publicar perfil e atividade", e)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Rumo/social", "Falha ao publicar", e)
            }
        }
    }
}
