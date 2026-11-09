package app.hunter.app.sync

import android.content.SharedPreferences
import app.hunter.sync.UserProfile

/**
 * Perfil sincronizável guardado nas preferências do app ("app"), com marca própria de sujo.
 * Das preferências do aparelho, só `voice` viaja (em [UserProfile.voice]); `bottomMode`, `mac`, `update_*` e
 * `seeded` nunca sincronizam. Os campos `profile_*` pertencem à conta e são apagados ao sair.
 */
object ProfilePrefs {
    const val VOICE = "voice"
    private const val NAME = "profile_name"
    private const val BRAND = "profile_brand"
    private const val MODEL = "profile_model"
    private const val YEAR = "profile_year"
    private const val DIRTY = "profile_dirty"

    /** Preferências do aparelho que fazem parte do perfil na nuvem. */
    val SYNCED_KEYS: Set<String> = setOf(VOICE)

    /** Sem nome gravado, não há perfil (ainda não entrou numa conta). */
    fun read(p: SharedPreferences): UserProfile? {
        val name = p.getString(NAME, null) ?: return null
        return UserProfile(
            displayName = name,
            brand = p.getString(BRAND, null),
            model = p.getString(MODEL, null),
            year = if (p.contains(YEAR)) p.getInt(YEAR, 0) else null,
            voice = p.getBoolean(VOICE, true),
        )
    }

    /** Grava o perfil vindo da nuvem/mesclado e o marca como sincronizado. */
    fun write(p: SharedPreferences, profile: UserProfile) {
        p.edit().apply {
            putString(NAME, profile.displayName)
            if (profile.brand != null) putString(BRAND, profile.brand) else remove(BRAND)
            if (profile.model != null) putString(MODEL, profile.model) else remove(MODEL)
            profile.year?.let { putInt(YEAR, it) } ?: remove(YEAR)
            putBoolean(VOICE, profile.voice)
            putBoolean(DIRTY, false)
        }.commit()
    }

    /** Chamado quando o usuário muda algo que viaja no perfil (hoje: voz). */
    fun markDirty(p: SharedPreferences) {
        p.edit().putBoolean(DIRTY, true).apply()
    }

    /** Sujo só conta se existe perfil para enviar. */
    fun isDirty(p: SharedPreferences): Boolean = p.getBoolean(DIRTY, false) && p.contains(NAME)

    fun clear(p: SharedPreferences) {
        p.edit().remove(NAME).remove(BRAND).remove(MODEL).remove(YEAR).remove(DIRTY).commit()
    }
}
