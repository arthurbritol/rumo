package app.hunter.app.account

import android.content.Context

/** Marca local, síncrona e que não depende de rede: há uma exclusão de conta a concluir. */
interface PendingDeletionFlag {
    fun isSet(): Boolean
    fun set()
    fun clear()
}

/** Persistida em SharedPreferences com commit() síncrono, para sobreviver a o processo ser morto. */
class SharedPrefsPendingDeletionFlag(context: Context) : PendingDeletionFlag {
    private val prefs = context.applicationContext.getSharedPreferences("account_deletion", Context.MODE_PRIVATE)
    override fun isSet() = prefs.getBoolean(KEY, false)
    override fun set() { prefs.edit().putBoolean(KEY, true).commit() }
    override fun clear() { prefs.edit().putBoolean(KEY, false).commit() }

    private companion object { const val KEY = "pending" }
}
