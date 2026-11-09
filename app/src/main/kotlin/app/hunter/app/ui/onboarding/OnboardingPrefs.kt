package app.hunter.app.ui.onboarding

import android.content.Context

/** Preferência "já viu a apresentação". */
interface OnboardingPrefs {
    fun seen(): Boolean
    fun markSeen()
}

/** Mesma preferência "app" usada pelo resto do app. */
class SharedPrefsOnboardingPrefs(context: Context) : OnboardingPrefs {
    private val prefs = context.applicationContext.getSharedPreferences("app", Context.MODE_PRIVATE)
    override fun seen(): Boolean = prefs.getBoolean(KEY, false)
    override fun markSeen() {
        prefs.edit().putBoolean(KEY, true).apply()
    }

    private companion object {
        const val KEY = "onboarding_seen"
    }
}
