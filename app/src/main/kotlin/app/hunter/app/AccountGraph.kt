package app.hunter.app

import android.content.SharedPreferences
import android.util.Log
import androidx.room.InvalidationTracker
import app.hunter.app.account.AccountConfig
import app.hunter.app.account.AuthRepository
import app.hunter.app.account.AuthState
import app.hunter.app.account.FirebaseAuthGateway
import app.hunter.app.account.SharedPrefsPendingDeletionFlag
import app.hunter.app.data.AppDatabase
import app.hunter.app.sync.FirestoreRemoteStore
import app.hunter.app.sync.PerUserRemoteStore
import app.hunter.app.sync.RoomLocalStore
import app.hunter.app.sync.SyncGate
import app.hunter.app.sync.SyncScheduler
import app.hunter.app.sync.SyncTrigger
import app.hunter.sync.SyncEngine
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Conta e sincronização, construídas uma vez por processo (escopo da Application): gateway do Firebase Auth,
 * marca local de exclusão pendente, motor de sincronização sobre o Room, agendador e repositório de conta.
 *
 * O armazenamento remoto depende do uid: [PerUserRemoteStore] o resolve a cada chamada e recria o
 * [FirestoreRemoteStore] quando a conta muda; sem usuário nada sincroniza. Sem google-services.json
 * ([available] falso) o Firebase não existe: o estado é visitante, o login fica desabilitado e nada sincroniza.
 */
class AccountGraph(private val database: AppDatabase, private val app: HunterApp) {
    val available: Boolean = AccountConfig.isAvailable(app)
    private val prefs: SharedPreferences = app.getSharedPreferences("app", 0)

    val gateway = FirebaseAuthGateway(app)
    val flag = SharedPrefsPendingDeletionFlag(app)

    private val local = RoomLocalStore(database, prefs)
    private fun currentUid(): String? = (gateway.state.value as? AuthState.SignedIn)?.uid

    /** Trava única do motor: agendador, primeira sincronização, sair e apagar conta. */
    private val gate = SyncGate(::currentUid)
    private val engine = SyncEngine(
        local,
        PerUserRemoteStore(::currentUid, { gate.pinnedUid }) { uid ->
            FirestoreRemoteStore(FirebaseFirestore.getInstance(), uid)
        },
    )
    val repo = AuthRepository(gateway, engine, flag, gate)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Só vira verdadeiro depois de concluir uma exclusão interrompida: nenhuma sincronização antes disso. */
    @Volatile
    private var ready = false

    private fun signedUid(): String? = (gateway.state.value as? AuthState.SignedIn)?.uid

    val scheduler = SyncScheduler(
        engine, scope,
        gate = gate,
        onError = { Log.w("Rumo/sync", "Falha ao sincronizar", it) },
        isSignedIn = { ready && signedUid() != null && flag.uid() == null },
    )

    /** Há algo ainda não enviado à nuvem (a tela de perfil bloqueia "Sair" enquanto houver). */
    suspend fun hasUnsynced(): Boolean = !engine.canSignOut()

    /** Uma exclusão desta conta foi interrompida neste aparelho e ainda não terminou. */
    fun deletionPending(): Boolean = signedUid()?.let { flag.uid() == it } == true

    private var started = false

    /**
     * Ao abrir o app, ANTES de qualquer sincronização: conclui uma exclusão interrompida (marca local com uid
     * e marca da nuvem). Depois liga os gatilhos de sincronização. Sem Firebase não faz nada.
     */
    @Synchronized
    fun start() {
        if (started || !available) return
        started = true
        scope.launch {
            try {
                repo.resumeDeletionIfNeeded()
            } catch (e: Exception) {
                Log.w("Rumo/sync", "Falha ao retomar exclusão", e)
            }
            ready = true
            scheduler.requestSync() // nada a fazer se for visitante
        }
        // Entrou numa conta (login novo ou sessão restaurada): sincroniza.
        scope.launch { gateway.state.collect { if (it is AuthState.SignedIn) scheduler.requestSync() } }

        // Qualquer alteração local (abastecimento, manutenção, hodômetro, favorito, fim de viagem, lápide) pede
        // sincronização se sobrou algo sem enviar. Fora da thread principal; pedidos seguidos são coalescidos.
        val trigger = SyncTrigger(
            hasUnsynced = { signedUid() != null && hasUnsynced() },
            request = scheduler::requestSync,
        )
        val changes = Channel<Unit>(Channel.CONFLATED)
        scope.launch {
            database.invalidationTracker.addObserver(
                object : InvalidationTracker.Observer(arrayOf("fuelups", "maintenance_items", "places", "trips", "odometer", "tombstones")) {
                    override fun onInvalidated(tables: Set<String>) {
                        changes.trySend(Unit)
                    }
                },
            )
            for (c in changes) trigger.onLocalChange()
        }
    }
}
