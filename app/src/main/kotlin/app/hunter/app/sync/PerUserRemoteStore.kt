package app.hunter.app.sync

import app.hunter.sync.OdometerDoc
import app.hunter.sync.RemoteSnapshot
import app.hunter.sync.RemoteStore
import app.hunter.sync.SyncDoc
import app.hunter.sync.UserProfile

/**
 * [RemoteStore] que resolve o usuário logado a cada chamada: o motor de sincronização é construído uma vez
 * e o armazenamento remoto (que depende do uid) é recriado quando a conta muda. Sem usuário, qualquer
 * chamada lança [IllegalStateException] (o agendador já não sincroniza sem usuário).
 */
class PerUserRemoteStore(
    private val currentUid: () -> String?,
    private val factory: (uid: String) -> RemoteStore,
) : RemoteStore {
    private val lock = Any()
    private var cachedUid: String? = null
    private var cached: RemoteStore? = null

    private fun store(): RemoteStore {
        val uid = currentUid() ?: throw IllegalStateException("Nenhum usuário logado.")
        return synchronized(lock) {
            if (cachedUid != uid || cached == null) {
                cached = factory(uid)
                cachedUid = uid
            }
            cached!!
        }
    }

    override suspend fun fetchAll(): RemoteSnapshot = store().fetchAll()
    override suspend fun push(docs: List<SyncDoc>) = store().push(docs)
    override suspend fun pushOdometer(o: OdometerDoc) = store().pushOdometer(o)
    override suspend fun pushProfile(p: UserProfile) = store().pushProfile(p)
    override suspend fun markDeletionPending() = store().markDeletionPending()
    override suspend fun deletionPending(): Boolean = store().deletionPending()
    override suspend fun deleteAllUserData() = store().deleteAllUserData()
}
