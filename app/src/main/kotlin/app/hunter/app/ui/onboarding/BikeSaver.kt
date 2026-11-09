package app.hunter.app.ui.onboarding

import android.content.SharedPreferences
import app.hunter.app.sync.ProfilePrefs
import app.hunter.maintenance.data.MaintenanceDao
import app.hunter.maintenance.data.OdometerEntity
import app.hunter.sync.UserProfile
import app.hunter.trips.data.TripDao
import kotlinx.coroutines.flow.first

/** Grava o cadastro da moto. Interface pequena para a tela não conhecer Room nem preferências. */
interface BikeSaver {
    /** Só chamar com [BikeForm.validate] vazio. [name] é o nome da conta (vazio sem conta). */
    suspend fun save(form: BikeForm, name: String)
}

/** Monta o perfil a gravar a partir do formulário (puro, testável). */
object BikeProfileMerge {
    const val MAX_NAME = 60

    /**
     * Um perfil local com nome (já sincronizado ou editado) mantém o nome; só moto muda. Sem isso o nome da
     * conta, aparado e limitado a 1..[MAX_NAME] (limite das regras do Firestore), com "Motociclista" se vazio.
     */
    fun merge(existing: UserProfile?, form: BikeForm, accountName: String, voice: Boolean): UserProfile {
        val name = existing?.displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: accountName.trim().take(MAX_NAME).trim().ifEmpty { "Motociclista" }
        return form.toProfile(name).copy(voice = voice)
    }
}

/**
 * Hodômetro e perfil entram como SUJOS (o motor os envia na próxima sincronização) e então pede
 * a sincronização. O total de viagens vem do mesmo banco que o hodômetro da garagem usa, então o
 * hodômetro exibido fica igual ao km digitado. No cadastro curto (sem conta) só o hodômetro é gravado:
 * um perfil sem nome nem moto não existe e seria enviado vazio ao entrar numa conta.
 */
class AppBikeSaver(
    private val dao: MaintenanceDao,
    private val tripDao: TripDao,
    private val prefs: SharedPreferences,
    private val requestSync: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) : BikeSaver {
    override suspend fun save(form: BikeForm, name: String) {
        val tripsM = tripDao.observeTrips().first().sumOf { it.distanceM }
        val doc = form.toOdometer(clock(), tripsM)
        dao.putOdometerRaw(OdometerEntity(1, doc.offsetKm, doc.tankLiters, doc.updatedAtMs, dirty = true))
        if (!form.short) {
            val existing = ProfilePrefs.read(prefs)
            val voice = existing?.voice ?: prefs.getBoolean(ProfilePrefs.VOICE, true)
            ProfilePrefs.writeDirty(prefs, BikeProfileMerge.merge(existing, form, name, voice))
        }
        requestSync()
    }
}
