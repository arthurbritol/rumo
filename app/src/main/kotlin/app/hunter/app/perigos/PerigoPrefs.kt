package app.hunter.app.perigos

import android.content.SharedPreferences
import app.hunter.nav.perigos.CategoriaPerigo

/** Interruptores dos avisos de perigo nas preferências do app ("app"). Padrão: ligado, exceto "Pontos de atenção" ([CategoriaPerigo.ligadaPorPadrao]). */
object PerigoPrefs {
    fun chave(c: CategoriaPerigo): String = when (c) {
        CategoriaPerigo.RADARES -> "perigo_radares"
        CategoriaPerigo.LOMBADAS -> "perigo_lombadas"
        CategoriaPerigo.TRAVESSIAS -> "perigo_travessias"
        CategoriaPerigo.ATENCAO -> "perigo_atencao"
    }

    fun ler(p: SharedPreferences): Set<CategoriaPerigo> =
        CategoriaPerigo.values().filter { p.getBoolean(chave(it), it.ligadaPorPadrao) }.toSet()

    fun gravar(p: SharedPreferences, c: CategoriaPerigo, ligada: Boolean) {
        p.edit().putBoolean(chave(c), ligada).apply()
    }

    private const val CHAVE_PARDAL_ATIVO = "pardal_ativo"
    private const val CHAVE_PARDAL_SO_WIFI = "pardal_so_wifi"

    /** Usa e baixa os dados do Pardal (radares, lombadas, pontos de atenção)? false = só Overpass. Padrão: sim. */
    fun pardalAtivo(p: SharedPreferences): Boolean = p.getBoolean(CHAVE_PARDAL_ATIVO, true)
    fun gravarPardalAtivo(p: SharedPreferences, ativo: Boolean) { p.edit().putBoolean(CHAVE_PARDAL_ATIVO, ativo).apply() }

    /** Baixar o Pardal só no Wi-Fi (dados móveis pedem confirmação)? Padrão: sim. */
    fun pardalSoWifi(p: SharedPreferences): Boolean = p.getBoolean(CHAVE_PARDAL_SO_WIFI, true)
    fun gravarPardalSoWifi(p: SharedPreferences, soWifi: Boolean) { p.edit().putBoolean(CHAVE_PARDAL_SO_WIFI, soWifi).apply() }

    /** Resultado de ligar/desligar [c] em [atual]. */
    fun alternar(atual: Set<CategoriaPerigo>, c: CategoriaPerigo, ligada: Boolean): Set<CategoriaPerigo> =
        if (ligada) atual + c else atual - c
}
