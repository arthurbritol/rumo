package app.hunter.app.ui.onboarding

import android.content.SharedPreferences
import app.hunter.app.sync.ProfilePrefs
import app.hunter.maintenance.data.MaintenanceDao
import app.hunter.maintenance.data.OdometerEntity

/** Grava o cadastro da moto. Interface pequena para a tela não conhecer Room nem preferências. */
interface BikeSaver {
    /** Só chamar com [BikeForm.validate] vazio. [name] é o nome da conta (vazio sem conta). */
    suspend fun save(form: BikeForm, name: String)
}

/**
 * Hodômetro e perfil entram como SUJOS (o motor os envia na próxima sincronização) e então pede
 * a sincronização. No cadastro curto (sem conta) só o hodômetro é gravado: um perfil sem nome nem
 * moto não existe e seria enviado vazio ao entrar numa conta.
 */
class AppBikeSaver(
    private val dao: MaintenanceDao,
    private val prefs: SharedPreferences,
    private val requestSync: () -> Unit,
    private val tripsDistanceM: suspend () -> Double = { 0.0 },
    private val clock: () -> Long = System::currentTimeMillis,
) : BikeSaver {
    override suspend fun save(form: BikeForm, name: String) {
        val doc = form.toOdometer(clock(), tripsDistanceM())
        dao.putOdometerRaw(OdometerEntity(1, doc.offsetKm, doc.tankLiters, doc.updatedAtMs, dirty = true))
        if (!form.short) {
            val voice = ProfilePrefs.read(prefs)?.voice ?: prefs.getBoolean(ProfilePrefs.VOICE, true)
            ProfilePrefs.write(prefs, form.toProfile(name.ifBlank { "Motociclista" }).copy(voice = voice))
            ProfilePrefs.markDirty(prefs)
        }
        requestSync()
    }
}
