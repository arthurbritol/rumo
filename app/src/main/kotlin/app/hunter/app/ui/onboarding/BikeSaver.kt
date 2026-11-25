package app.hunter.app.ui.onboarding

import android.content.SharedPreferences
import app.hunter.app.sync.ProfilePrefs
import app.hunter.maintenance.data.MaintenanceDao
import app.hunter.maintenance.data.OdometerEntity
import app.hunter.sync.UserProfile
import app.hunter.app.veiculo.VeiculoRepository
import app.hunter.model.TipoVeiculo
import app.hunter.trips.data.TripDao
import kotlinx.coroutines.flow.first

/** Grava o cadastro da moto. Interface pequena para a tela não conhecer Room nem preferências. */
interface BikeSaver {
    /** Só chamar com [BikeForm.validate] vazio. [name] é o nome da conta (vazio sem conta). */
    suspend fun save(form: BikeForm, name: String)
}

/** Mapeamento do formulário do onboarding para o [app.hunter.model.Veiculo] (puro, testável). */
object BikeFormMapping {
    /** Apelido digitado; no cadastro curto (sem marca nem modelo) um nome padrão do tipo, para a lista não ficar sem nome. */
    fun apelido(form: BikeForm): String =
        form.apelido.trim().ifEmpty { if (form.short) (if (form.tipo == TipoVeiculo.CARRO) "Meu carro" else "Minha moto") else "" }

    /** Aplica tipo, marca, modelo e ano do formulário completo a um veículo existente; o apelido só muda se foi digitado. */
    fun aplicar(v: app.hunter.model.Veiculo, form: BikeForm): app.hunter.model.Veiculo = v.copy(
        tipo = form.tipo, marca = form.brand.trim(), modelo = form.model.trim(), ano = form.year.trim().toIntOrNull(),
        apelido = form.apelido.trim().ifEmpty { v.apelido },
    )
}

/** Monta o perfil a gravar a partir do formulário (puro, testável). */
object BikeProfileMerge {
    const val MAX_NAME = 60

    /**
     * Um perfil local com nome (já sincronizado ou editado) mantém o nome; só moto muda. Sem isso o nome da
     * conta, aparado e limitado a 1..[MAX_NAME] (limite das regras do Firestore), com "Motociclista" se vazio.
     * Cadastro de CARRO não grava marca, modelo nem ano no perfil (esses campos só valem para a moto/veículo
     * migrado): mantém os que já existiam.
     */
    fun merge(existing: UserProfile?, form: BikeForm, accountName: String, voice: Boolean): UserProfile {
        val name = existing?.displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: accountName.trim().take(MAX_NAME).trim().ifEmpty { "Motociclista" }
        val base = form.toProfile(name)
        val perfil = if (form.tipo == TipoVeiculo.CARRO) base.copy(brand = existing?.brand, model = existing?.model, year = existing?.year) else base
        return perfil.copy(voice = voice)
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
    private val veiculos: VeiculoRepository,
    private val prefs: SharedPreferences,
    private val requestSync: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) : BikeSaver {
    override suspend fun save(form: BikeForm, name: String) {
        // O cadastro é do veículo ativo; sem nenhum veículo (instalação nova) cria o veículo do cadastro.
        val tanque = form.toOdometer(clock(), 0.0).tankLiters
        val criado = veiculos.ativoAgora() == null
        val ativo = veiculos.ativoAgora() ?: veiculos.adicionar(
            tipo = form.tipo, apelido = BikeFormMapping.apelido(form),
            marca = if (form.short) "" else form.brand.trim(), modelo = if (form.short) "" else form.model.trim(),
            ano = if (form.short) null else form.year.trim().toIntOrNull(),
            tanqueLitros = tanque,
        )
        // Veículo já existente (migrado, sem marca/modelo): o cadastro completa os dados que o usuário digitou.
        if (!criado && !form.short) veiculos.editar(BikeFormMapping.aplicar(ativo, form))
        val tripsM = tripDao.observeDistanceM(ativo.id).first()
        val doc = form.toOdometer(clock(), tripsM)
        dao.putOdometerRaw(OdometerEntity(ativo.id, doc.offsetKm, doc.tankLiters, doc.updatedAtMs, dirty = true))
        val atual = veiculos.ativoAgora() ?: ativo
        if (atual.tanqueLitros != doc.tankLiters) veiculos.editar(atual.copy(tanqueLitros = doc.tankLiters))
        if (!form.short) {
            val existing = ProfilePrefs.read(prefs)
            val voice = existing?.voice ?: prefs.getBoolean(ProfilePrefs.VOICE, true)
            ProfilePrefs.writeDirty(prefs, BikeProfileMerge.merge(existing, form, name, voice))
        }
        requestSync()
    }
}
