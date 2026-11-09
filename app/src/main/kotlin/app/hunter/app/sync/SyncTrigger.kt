package app.hunter.app.sync

import kotlinx.coroutines.CancellationException

/**
 * Decide se uma alteração no banco local pede sincronização: só quando sobrou algo sem enviar. As escritas do
 * próprio sync (que deixam tudo limpo) não geram novo pedido, então não há laço. Nunca lança.
 */
class SyncTrigger(
    private val hasUnsynced: suspend () -> Boolean,
    private val request: () -> Unit,
) {
    suspend fun onLocalChange() {
        val pending = try {
            hasUnsynced()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        if (pending) request()
    }
}
