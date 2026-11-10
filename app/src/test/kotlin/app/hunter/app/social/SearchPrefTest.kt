package app.hunter.app.social

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchPrefTest {
    /** SharedPreferences mínimo em memória (só getBoolean, edit, putBoolean, apply/commit). */
    private fun memoryPrefs(): SharedPreferences {
        val data = HashMap<String, Boolean>()
        val editorType = SharedPreferences.Editor::class.java
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, m, args ->
            when (m.name) {
                "getBoolean" -> data[args[0] as String] ?: (args[1] as Boolean)
                "edit" -> Proxy.newProxyInstance(javaClass.classLoader, arrayOf(editorType)) { editor, em, eargs ->
                    if (em.name == "putBoolean") data[eargs[0] as String] = eargs[1] as Boolean
                    if (em.returnType == editorType) editor else if (em.returnType == Boolean::class.java) true else null
                }
                else -> error("método não usado: ${m.name}")
            }
        } as SharedPreferences
    }

    @Test
    fun `resposta é por conta e sem conta conta como perguntado e não pesquisável`() {
        var uid: String? = "a"
        val p = SearchPref(memoryPrefs()) { uid }
        assertFalse(p.asked())
        assertTrue(p.searchable()) // padrão ligado, mas só vale depois de perguntar
        assertFalse(p.appearsInSearch())
        p.set(true)
        assertTrue(p.appearsInSearch())
        uid = "b" // outra conta no mesmo aparelho
        assertFalse(p.asked())
        assertFalse(p.appearsInSearch())
        p.set(false)
        assertTrue(p.asked())
        assertFalse(p.appearsInSearch())
        uid = "a"
        assertTrue(p.appearsInSearch())
        uid = null
        assertTrue(p.asked())
        assertFalse(p.searchable())
    }
}
