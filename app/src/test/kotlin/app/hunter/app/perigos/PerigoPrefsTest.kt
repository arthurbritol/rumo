package app.hunter.app.perigos

import android.content.SharedPreferences
import app.hunter.nav.perigos.CategoriaPerigo
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

class PerigoPrefsTest {
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
    fun `padrao e tudo ligado menos pontos de atencao`() {
        assertEquals(CategoriaPerigo.values().toSet() - CategoriaPerigo.ATENCAO, PerigoPrefs.ler(memoryPrefs()))
    }

    @Test
    fun `cada interruptor e independente e persiste`() {
        val p = memoryPrefs()
        PerigoPrefs.gravar(p, CategoriaPerigo.LOMBADAS, false)
        assertEquals(setOf(CategoriaPerigo.RADARES, CategoriaPerigo.TRAVESSIAS), PerigoPrefs.ler(p))
        PerigoPrefs.gravar(p, CategoriaPerigo.ATENCAO, true)
        assertEquals(setOf(CategoriaPerigo.RADARES, CategoriaPerigo.TRAVESSIAS, CategoriaPerigo.ATENCAO), PerigoPrefs.ler(p))
        PerigoPrefs.gravar(p, CategoriaPerigo.ATENCAO, false)
        PerigoPrefs.gravar(p, CategoriaPerigo.RADARES, false)
        assertEquals(setOf(CategoriaPerigo.TRAVESSIAS), PerigoPrefs.ler(p))
        PerigoPrefs.gravar(p, CategoriaPerigo.LOMBADAS, true)
        assertEquals(setOf(CategoriaPerigo.LOMBADAS, CategoriaPerigo.TRAVESSIAS), PerigoPrefs.ler(p))
    }

    @Test
    fun `chaves sao distintas`() {
        assertEquals(4, CategoriaPerigo.values().map(PerigoPrefs::chave).toSet().size)
    }

    @Test
    fun `alternar liga e desliga sem tocar nas outras`() {
        val todas = CategoriaPerigo.values().toSet()
        assertEquals(todas - CategoriaPerigo.RADARES, PerigoPrefs.alternar(todas, CategoriaPerigo.RADARES, false))
        assertEquals(todas, PerigoPrefs.alternar(todas - CategoriaPerigo.RADARES, CategoriaPerigo.RADARES, true))
    }
}
