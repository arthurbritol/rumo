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

/**
 * [SearchPrefs] nas preferências do app ("app"), uma resposta por conta (chaves com o uid): outra conta no mesmo
 * aparelho vê a pergunta de novo. Sem conta ([currentUid] nulo) conta como já perguntado e não pesquisável.
 */
class SearchPref(private val prefs: SharedPreferences, private val currentUid: () -> String?) : SearchPrefs {
    override fun asked(): Boolean = currentUid()?.let { prefs.getBoolean(ASKED + it, false) } ?: true

    override fun searchable(): Boolean = currentUid()?.let { prefs.getBoolean(SEARCHABLE + it, true) } ?: false

    override fun set(searchable: Boolean) {
        val uid = currentUid() ?: return
        prefs.edit().putBoolean(SEARCHABLE + uid, searchable).putBoolean(ASKED + uid, true).apply()
    }

    companion object {
        const val ASKED = "social_search_asked_"
        const val SEARCHABLE = "social_searchable_"
    }
}

/** Só aparece na busca quem respondeu a pergunta e disse sim: quem nunca respondeu não vira pesquisável. */
fun SearchPrefs.appearsInSearch(): Boolean = asked() && searchable()

/** Quando perguntar "Quer aparecer na busca por nome?": uma vez só e só para quem está logado. */
object SearchPrompt {
    fun shouldAsk(askedBefore: Boolean, signedIn: Boolean): Boolean = signedIn && !askedBefore
}
