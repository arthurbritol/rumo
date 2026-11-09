package app.hunter.app.sync

import app.hunter.sync.SyncEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Agenda a sincronização. Kotlin puro: não conhece Android.
 *
 * - [requestSync]: após uma alteração local. Chamadas dentro da janela de [debounceMs], contada a
 *   partir da primeira, viram uma única [SyncEngine.syncNow].
 * - [syncOnConnectivity]: a conexão voltou; sincroniza já.
 * Sem usuário logado ([isSignedIn] falso) nada é sincronizado. Qualquer falha do motor é engolida:
 * o local continua sujo e a próxima chamada tenta de novo (sem laço de retentativas).
 */
class SyncScheduler(
    private val engine: SyncEngine,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 5_000,
    private val isSignedIn: () -> Boolean,
) {
    private val lock = Any()
    private var pending: Job? = null
    private val running = Mutex()

    fun requestSync() {
        if (!isSignedIn()) return
        synchronized(lock) {
            if (pending?.isActive == true) return
            pending = scope.launch {
                delay(debounceMs)
                run()
            }
        }
    }

    fun syncOnConnectivity() {
        if (!isSignedIn()) return
        scope.launch { run() }
    }

    private suspend fun run() {
        running.withLock {
            if (!isSignedIn()) return
            try {
                engine.syncNow()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Falha de rede ou do remoto: tudo segue sujo; tenta na próxima chamada.
            }
        }
    }
}
