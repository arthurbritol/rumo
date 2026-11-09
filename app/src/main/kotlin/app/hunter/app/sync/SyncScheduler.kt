package app.hunter.app.sync

import app.hunter.sync.SyncEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Agenda a sincronização. Kotlin puro: não conhece Android.
 *
 * - [requestSync]: após uma alteração local. Chamadas dentro da janela de [debounceMs], contada a
 *   partir da primeira, viram uma única [SyncEngine.syncNow]. Um pedido que chega durante uma
 *   sincronização em andamento arma uma nova, que roda depois (nunca duas ao mesmo tempo).
 * - [syncOnConnectivity]: a conexão voltou; sincroniza já. Sinais repetidos são ignorados enquanto
 *   houver uma sincronização por conexão em andamento ou dentro de [connectivityCooldownMs] dela
 *   (evita uma leitura completa a cada oscilação da rede).
 * Sem usuário logado ([isSignedIn] falso) nada é sincronizado. Cada sincronização tem limite de
 * [SYNC_TIMEOUT_MS]. Falhas (inclusive o estouro do limite) vão para [onError] e o local continua
 * sujo; a próxima chamada tenta de novo, sem laço de retentativas. O cancelamento do escopo é
 * propagado e não é reportado.
 */
class SyncScheduler(
    private val engine: SyncEngine,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 5_000,
    private val connectivityCooldownMs: Long = 10_000,
    private val onError: (Throwable) -> Unit = {},
    private val gate: SyncGate = SyncGate(),
    private val isSignedIn: () -> Boolean,
) {
    private val lock = Any()
    private var pending: Job? = null
    private var connectivityJob: Job? = null

    @Volatile
    private var syncing = false

    fun requestSync() {
        if (!isSignedIn()) return
        synchronized(lock) {
            if (pending?.isActive == true) return
            pending = scope.launch {
                delay(debounceMs)
                // Libera a janela antes de sincronizar: um pedido durante a execução arma outra.
                synchronized(lock) { pending = null }
                run()
            }
        }
    }

    fun syncOnConnectivity() {
        if (!isSignedIn()) return
        synchronized(lock) {
            if (connectivityJob?.isActive == true || syncing) return
            connectivityJob = scope.launch {
                run()
                delay(connectivityCooldownMs)
            }
        }
    }

    private suspend fun run() {
        // A trava é a do grafo da conta: sair/apagar conta/primeiro sync não correm junto com esta sincronização.
        gate.pinned {
            if (!isSignedIn()) return@pinned
            syncing = true
            try {
                withTimeout(SYNC_TIMEOUT_MS) { engine.syncNow() }
            } catch (e: TimeoutCancellationException) {
                onError(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            } finally {
                syncing = false
            }
        }
    }

    companion object {
        const val SYNC_TIMEOUT_MS = 60_000L
    }
}
