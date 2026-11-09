package app.hunter.app.account

import android.content.Context

/**
 * Marca local, síncrona e que não depende de rede: há uma exclusão a concluir para a conta [uid].
 * Valor sem uid (formato antigo) conta como ausente.
 */
interface PendingDeletionFlag {
    fun uid(): String?
    fun set(uid: String)
    fun clear()
}

/** Persistida em SharedPreferences com commit() síncrono, para sobreviver à morte do processo. */
class SharedPrefsPendingDeletionFlag(context: Context) : PendingDeletionFlag {
    private val prefs = context.applicationContext.getSharedPreferences("account_deletion", Context.MODE_PRIVATE)

    override fun uid(): String? = prefs.getString(KEY, null)?.takeIf { it.isNotEmpty() }

    override fun set(uid: String) {
        prefs.edit().remove(LEGACY_KEY).putString(KEY, uid).commit()
    }

    override fun clear() {
        prefs.edit().remove(KEY).remove(LEGACY_KEY).commit()
    }

    private companion object {
        const val KEY = "pending_uid"
        const val LEGACY_KEY = "pending" // booleano do formato antigo: ignorado
    }
}
