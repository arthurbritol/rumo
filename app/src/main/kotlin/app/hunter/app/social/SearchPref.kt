package app.hunter.app.social

import android.content.SharedPreferences

/** Escolha da pessoa sobre aparecer na busca por nome. */
interface SearchPrefs {
    /** A pergunta "Quer aparecer na busca por nome?" já foi respondida. */
    fun asked(): Boolean

    /** Aparecer na busca; ligado até a pessoa dizer o contrário. */
    fun searchable(): Boolean

    /** Grava a resposta e marca a pergunta como feita. */
    fun set(searchable: Boolean)
}

/** [SearchPrefs] nas preferências do app ("app"). */
class SearchPref(private val prefs: SharedPreferences) : SearchPrefs {
    override fun asked(): Boolean = prefs.getBoolean(ASKED, false)

    override fun searchable(): Boolean = prefs.getBoolean(SEARCHABLE, true)

    override fun set(searchable: Boolean) {
        prefs.edit().putBoolean(SEARCHABLE, searchable).putBoolean(ASKED, true).commit()
    }

    companion object {
        const val ASKED = "social_search_asked"
        const val SEARCHABLE = "social_searchable"
    }
}

/** Quando perguntar "Quer aparecer na busca por nome?": uma vez só e só para quem está logado. */
object SearchPrompt {
    fun shouldAsk(askedBefore: Boolean, signedIn: Boolean): Boolean = signedIn && !askedBefore
}
