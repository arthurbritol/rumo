package app.hunter.app.perigos

import android.content.SharedPreferences
import app.hunter.nav.perigos.CategoriaPerigo

/** Interruptores dos avisos de perigo nas preferências do app ("app"). Padrão: tudo ligado. */
object PerigoPrefs {
    fun chave(c: CategoriaPerigo): String = when (c) {
        CategoriaPerigo.RADARES -> "perigo_radares"
        CategoriaPerigo.LOMBADAS -> "perigo_lombadas"
        CategoriaPerigo.TRAVESSIAS -> "perigo_travessias"
    }

    fun ler(p: SharedPreferences): Set<CategoriaPerigo> =
        CategoriaPerigo.values().filter { p.getBoolean(chave(it), true) }.toSet()

    fun gravar(p: SharedPreferences, c: CategoriaPerigo, ligada: Boolean) {
        p.edit().putBoolean(chave(c), ligada).apply()
    }

    /** Resultado de ligar/desligar [c] em [atual]. */
    fun alternar(atual: Set<CategoriaPerigo>, c: CategoriaPerigo, ligada: Boolean): Set<CategoriaPerigo> =
        if (ligada) atual + c else atual - c
}
