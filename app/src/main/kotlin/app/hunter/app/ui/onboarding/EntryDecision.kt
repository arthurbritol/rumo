package app.hunter.app.ui.onboarding

import android.content.SharedPreferences
import app.hunter.app.data.SyncDao

/** Preferência: mostrar o aviso discreto "crie uma conta para proteger seus dados" (só para quem já usava o app). */
const val EXISTING_USER_NOTICE_KEY = "existing_user_notice"

/**
 * Decide o destino da abertura (veja [entryRoute]). Quem já usava o app é marcado como "apresentação vista" em
 * silêncio e ganha o aviso para criar conta. Deve rodar ANTES de o HunterViewModel existir: ele grava `seeded`
 * e semeia a manutenção, o que faria uma instalação nova parecer antiga. Fora da thread principal (consulta o banco).
 */
suspend fun resolveEntry(prefs: SharedPreferences, syncDao: SyncDao, onboarding: OnboardingPrefs): EntryRoute {
    val seen = onboarding.seen()
    val route = entryRoute(
        seen = seen,
        seeded = prefs.getBoolean("seeded", false),
        localRows = if (seen) 0 else syncDao.localRowCount(),
    )
    if (route == EntryRoute.EXISTING_USER) {
        onboarding.markSeen()
        prefs.edit().putBoolean(EXISTING_USER_NOTICE_KEY, true).apply()
        return EntryRoute.MAIN
    }
    return route
}
