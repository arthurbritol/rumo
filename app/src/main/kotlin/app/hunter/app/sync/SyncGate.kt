package app.hunter.app.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Trava única de tudo que mexe no motor de sincronização (agendador, primeira sincronização do login, sair e
 * apagar conta): nunca duas dessas operações ao mesmo tempo. Não é reentrante: quem já está dentro de
 * [withLock]/[pinned] não pode chamar de novo.
 *
 * [pinned] também fixa o uid da conta durante toda a sincronização ([pinnedUid]); o [PerUserRemoteStore]
 * recusa qualquer chamada se a conta logada mudou desde o início.
 */
class SyncGate(private val currentUid: () -> String? = { null }) {
    private val mutex = Mutex()

    @Volatile
    var pinnedUid: String? = null
        private set

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun <T> pinned(block: suspend () -> T): T = mutex.withLock {
        pinnedUid = currentUid()
        try {
            block()
        } finally {
            pinnedUid = null
        }
    }

    /**
     * Como [pinned], mas fixa um uid JÁ conhecido por quem chama (apagar conta captura o uid antes da
     * reautenticação): se a conta logada não for [uid], o [PerUserRemoteStore] recusa toda chamada.
     */
    suspend fun <T> pinnedTo(uid: String, block: suspend () -> T): T = mutex.withLock {
        pinnedUid = uid
        try {
            block()
        } finally {
            pinnedUid = null
        }
    }
}
