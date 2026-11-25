package app.hunter.app.veiculo

import android.content.SharedPreferences
import app.hunter.app.sync.ProfilePrefs
import app.hunter.maintenance.data.MaintenanceDao
import app.hunter.maintenance.data.VeiculoEntity
import app.hunter.model.TipoVeiculo
import app.hunter.model.Veiculo
import app.hunter.model.resolverVeiculoAtivo
import app.hunter.sync.UserProfile
import app.hunter.sync.VEICULO_LEGADO_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

/** Onde os veículos ficam (Room, no app; um fake nos testes). */
interface VeiculoStore {
    /** Veículos não removidos. */
    fun observeVeiculos(): Flow<List<VeiculoEntity>>
    suspend fun getVeiculos(): List<VeiculoEntity>
    suspend fun getVeiculo(id: String): VeiculoEntity?

    /** Grava a linha como está (quem chama decide se é uma alteração do usuário: [VeiculoEntity.touch]). */
    suspend fun salvar(v: VeiculoEntity)
}

class RoomVeiculoStore(private val dao: MaintenanceDao) : VeiculoStore {
    override fun observeVeiculos() = dao.observeVeiculos()
    override suspend fun getVeiculos() = dao.getVeiculos()
    override suspend fun getVeiculo(id: String) = dao.getVeiculo(id)
    override suspend fun salvar(v: VeiculoEntity) = dao.putVeiculoRaw(v)
}

/** Preferência local do veículo ativo (viaja no perfil da conta). */
interface VeiculoAtivoStore {
    fun ler(): String?
    fun gravar(id: String?)

    /** Valor atual e cada mudança (inclusive a que a sincronização do perfil grava). */
    fun mudancas(): Flow<String?>
}

/**
 * Guarda o id nas preferências do app ("app"), na mesma chave do perfil sincronizável ([ProfilePrefs.VEICULO_ATIVO]):
 * escolher outro veículo marca o perfil como sujo para o outro aparelho da conta abrir no mesmo veículo.
 */
class SharedPrefsVeiculoAtivo(private val prefs: SharedPreferences) : VeiculoAtivoStore {
    override fun ler(): String? = prefs.getString(ProfilePrefs.VEICULO_ATIVO, null)

    override fun gravar(id: String?) {
        if (id == null) prefs.edit().remove(ProfilePrefs.VEICULO_ATIVO).apply() else prefs.edit().putString(ProfilePrefs.VEICULO_ATIVO, id).apply()
        if (id != null) ProfilePrefs.markDirty(prefs)
    }

    override fun mudancas(): Flow<String?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == ProfilePrefs.VEICULO_ATIVO) trySend(ler())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(ler())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}

/**
 * Veículos da conta e o veículo ativo. O ativo é o escolhido (se existe e não foi removido) ou, senão, o primeiro
 * não removido ([resolverVeiculoAtivo]); sem veículos, nulo. Toda alteração pede sincronização.
 */
class VeiculoRepository(
    private val store: VeiculoStore,
    private val ativoStore: VeiculoAtivoStore,
    scope: CoroutineScope,
    private val requestSync: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val novoId: () -> String = { UUID.randomUUID().toString() },
) {
    /** Veículos não removidos, do mais antigo ao mais novo. */
    val veiculos: Flow<List<Veiculo>> = store.observeVeiculos().map { l -> l.map { it.toModel() } }

    val veiculoAtivo: StateFlow<Veiculo?> =
        combine(veiculos, ativoStore.mudancas()) { lista, id -> resolverVeiculoAtivo(lista, id) }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /** Lê o ativo direto do banco (para ações e trabalhos em segundo plano que não esperam o fluxo). */
    suspend fun ativoAgora(): Veiculo? =
        resolverVeiculoAtivo(store.getVeiculos().map { it.toModel() }, ativoStore.ler())

    /** Cadastra um veículo. O primeiro vira o ativo; os demais só se [tornarAtivo]. */
    suspend fun adicionar(
        tipo: TipoVeiculo,
        apelido: String,
        marca: String = "",
        modelo: String = "",
        ano: Int? = null,
        tanqueLitros: Double,
        consumoPadraoKmL: Double? = null,
        tornarAtivo: Boolean = false,
    ): Veiculo {
        val primeiro = ativoAgora() == null
        val agora = clock()
        val e = VeiculoEntity(
            id = novoId(), tipo = tipo.name, apelido = apelido, marca = marca, modelo = modelo, ano = ano,
            tanqueLitros = tanqueLitros, consumoPadraoKmL = consumoPadraoKmL, criadoEm = agora, updatedAtMs = agora, dirty = true,
        )
        store.salvar(e)
        if (primeiro || tornarAtivo) ativoStore.gravar(e.id)
        requestSync()
        return e.toModel()
    }

    /** Altera os dados do veículo (id e data de criação não mudam). Inexistente: não faz nada. */
    suspend fun editar(v: Veiculo) {
        val cur = store.getVeiculo(v.id) ?: return
        store.salvar(
            cur.copy(
                tipo = v.tipo.name, apelido = v.apelido, marca = v.marca, modelo = v.modelo, ano = v.ano,
                tanqueLitros = v.tanqueLitros, consumoPadraoKmL = v.consumoPadraoKmL, updatedAtMs = clock(), dirty = true,
            ),
        )
        requestSync()
    }

    /** Remoção lógica: some das listas, os dados dele ficam. Se era o ativo, passa para o primeiro que sobrar. */
    suspend fun remover(id: String) {
        val cur = store.getVeiculo(id) ?: return
        if (cur.removidoEm != null) return
        val agora = clock()
        store.salvar(cur.copy(removidoEm = agora, updatedAtMs = agora, dirty = true))
        if (ativoStore.ler() == id) ativoStore.gravar(ativoAgora()?.id)
        requestSync()
    }

    /** Escolhe o veículo ativo. Id inexistente ou removido: ignora. */
    suspend fun definirAtivo(id: String) {
        val v = store.getVeiculo(id) ?: return
        if (v.removidoEm != null) return
        ativoStore.gravar(id)
        requestSync()
    }

    /**
     * Rotina da primeira abertura depois da migração do banco: o veículo migrado nasce sem marca/modelo (o SQL não
     * alcança o perfil); aqui eles vêm do perfil local, se houver.
     */
    suspend fun completarLegadoDoPerfil(perfil: UserProfile?) {
        val v = store.getVeiculo(VEICULO_LEGADO_ID) ?: return
        completarVeiculoLegado(v, perfil, clock())?.let {
            store.salvar(it)
            requestSync()
        }
    }
}

/**
 * Preenche marca, modelo e ano do veículo migrado a partir do perfil, só quando marca e modelo estão vazios
 * (nunca sobrescreve o que o usuário digitou) e o perfil tem marca ou modelo. Devolve a linha já marcada como
 * alterada, ou nulo se não há o que fazer.
 */
fun completarVeiculoLegado(v: VeiculoEntity, perfil: UserProfile?, agora: Long = System.currentTimeMillis()): VeiculoEntity? {
    if (v.id != VEICULO_LEGADO_ID || perfil == null) return null
    if (v.marca.isNotBlank() || v.modelo.isNotBlank()) return null
    val marca = perfil.brand?.trim().orEmpty()
    val modelo = perfil.model?.trim().orEmpty()
    if (marca.isEmpty() && modelo.isEmpty()) return null
    return v.copy(marca = marca, modelo = modelo, ano = v.ano ?: perfil.year, updatedAtMs = agora, dirty = true)
}
