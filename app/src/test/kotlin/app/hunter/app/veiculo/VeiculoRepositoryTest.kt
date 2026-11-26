package app.hunter.app.veiculo

import app.hunter.maintenance.data.VeiculoEntity
import app.hunter.model.TipoVeiculo
import app.hunter.sync.UserProfile
import app.hunter.sync.VEICULO_LEGADO_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeVeiculoStore : VeiculoStore {
    val todos = MutableStateFlow<List<VeiculoEntity>>(emptyList())
    override fun observeVeiculos(): Flow<List<VeiculoEntity>> =
        todos.map { l -> l.filter { it.removidoEm == null }.sortedWith(compareBy({ it.criadoEm }, { it.id })) }
    override suspend fun getVeiculos() = todos.value.filter { it.removidoEm == null }.sortedWith(compareBy({ it.criadoEm }, { it.id }))
    override suspend fun getVeiculo(id: String) = todos.value.firstOrNull { it.id == id }
    override suspend fun salvar(v: VeiculoEntity) { todos.value = todos.value.filter { it.id != v.id } + v }
    val tanquesDoHodometro = mutableMapOf<String, Double>()
    override suspend fun atualizarTanqueDoHodometro(id: String, litros: Double) { tanquesDoHodometro[id] = litros }
    val viagensAtribuidas = mutableListOf<String>()
    override suspend fun atribuirViagensSemVeiculo(id: String) { viagensAtribuidas += id }
}

private class FakeAtivoStore : VeiculoAtivoStore {
    val valor = MutableStateFlow<String?>(null)
    override fun ler() = valor.value
    override fun gravar(id: String?) { valor.value = id }
    override fun mudancas(): Flow<String?> = valor
}

@OptIn(ExperimentalCoroutinesApi::class)
class VeiculoRepositoryTest {
    private val store = FakeVeiculoStore()
    private val ativo = FakeAtivoStore()
    private var tempo = 1_000L
    private var seq = 0
    private var syncs = 0

    private fun TestScope.repo() = VeiculoRepository(
        store, ativo, CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        requestSync = { syncs++ }, clock = { tempo++ }, novoId = { "v${++seq}" },
    )

    @Test
    fun `o primeiro veiculo cadastrado vira o ativo`() = runTest {
        val r = repo()
        val v = r.adicionar(TipoVeiculo.CARRO, "Meu carro", tanqueLitros = 45.0)
        advanceUntilIdle()
        assertEquals("v1", v.id)
        assertEquals("v1", r.veiculoAtivo.value?.id)
        assertEquals(TipoVeiculo.CARRO, r.veiculoAtivo.value?.tipo)
        assertEquals(1, syncs)
    }

    @Test
    fun `o segundo so vira ativo quando pedido`() = runTest {
        val r = repo()
        r.adicionar(TipoVeiculo.CARRO, "Carro 1", tanqueLitros = 45.0)
        r.adicionar(TipoVeiculo.CARRO, "Carro", tanqueLitros = 45.0)
        advanceUntilIdle()
        assertEquals("v1", r.veiculoAtivo.value?.id)
        r.adicionar(TipoVeiculo.CARRO, "Outro carro", tanqueLitros = 50.0, tornarAtivo = true)
        advanceUntilIdle()
        assertEquals("v3", r.veiculoAtivo.value?.id)
    }

    @Test
    fun `definir ativo troca na hora e ignora id inexistente ou removido`() = runTest {
        val r = repo()
        r.adicionar(TipoVeiculo.CARRO, "Carro 1", tanqueLitros = 45.0)
        r.adicionar(TipoVeiculo.CARRO, "Carro", tanqueLitros = 45.0)
        r.definirAtivo("v2")
        advanceUntilIdle()
        assertEquals("v2", r.veiculoAtivo.value?.id)
        r.definirAtivo("nao-existe")
        advanceUntilIdle()
        assertEquals("v2", r.veiculoAtivo.value?.id)
        r.remover("v1")
        r.definirAtivo("v1")
        advanceUntilIdle()
        assertEquals("v2", r.veiculoAtivo.value?.id)
    }

    @Test
    fun `remover o ativo cai no primeiro nao removido`() = runTest {
        val r = repo()
        r.adicionar(TipoVeiculo.CARRO, "Carro 1", tanqueLitros = 45.0)
        r.adicionar(TipoVeiculo.CARRO, "Carro", tanqueLitros = 45.0, tornarAtivo = true)
        advanceUntilIdle()
        assertEquals("v2", r.veiculoAtivo.value?.id)
        r.remover("v2")
        advanceUntilIdle()
        assertEquals("v1", r.veiculoAtivo.value?.id)
        assertEquals("v1", ativo.ler(), "a preferencia acompanha o veiculo que sobrou")
        assertNotNull(store.getVeiculo("v2")?.removidoEm, "remocao e logica: a linha continua")
        assertTrue(store.getVeiculo("v2")!!.dirty)
    }

    @Test
    fun `remover um veiculo que nao e o ativo mantem o ativo e some da lista`() = runTest {
        val r = repo()
        r.adicionar(TipoVeiculo.CARRO, "Carro 1", tanqueLitros = 45.0)
        r.adicionar(TipoVeiculo.CARRO, "Carro", tanqueLitros = 45.0)
        advanceUntilIdle()
        r.remover("v2")
        advanceUntilIdle()
        assertEquals("v1", r.veiculoAtivo.value?.id)
        assertEquals(listOf("v1"), r.veiculos.first().map { it.id })
    }

    @Test
    fun `nao remove o ultimo veiculo`() = runTest {
        val r = repo()
        r.adicionar(TipoVeiculo.CARRO, "Carro 1", tanqueLitros = 45.0)
        assertFalse(r.remover("v1"))
        advanceUntilIdle()
        assertEquals("v1", r.veiculoAtivo.value?.id)
        assertNull(store.getVeiculo("v1")!!.removidoEm)
        r.adicionar(TipoVeiculo.MOTO, "Moto", tanqueLitros = 13.0)
        assertTrue(r.remover("v1"))
    }

    // ---------- primeira moto do aparelho usa o id legado (I2) ----------

    @Test
    fun `a primeira moto de um aparelho sem veiculos usa o id legado`() = runTest {
        val r = repo()
        val m = r.adicionar(TipoVeiculo.MOTO, "Moto", tanqueLitros = 13.0)
        assertEquals(VEICULO_LEGADO_ID, m.id)
        assertEquals(VEICULO_LEGADO_ID, r.veiculoAtivo.value?.id ?: ativo.ler())
        val segunda = r.adicionar(TipoVeiculo.MOTO, "Outra", tanqueLitros = 13.0)
        assertEquals("v1", segunda.id, "a segunda usa id novo")
    }

    @Test
    fun `carro primeiro usa id novo e a moto seguinte tambem`() = runTest {
        val r = repo()
        assertEquals("v1", r.adicionar(TipoVeiculo.CARRO, "Carro", tanqueLitros = 45.0).id)
        assertEquals("v2", r.adicionar(TipoVeiculo.MOTO, "Moto", tanqueLitros = 13.0).id)
    }

    @Test
    fun `viagens sem veiculo vao para o primeiro veiculo criado`() = runTest {
        val r = repo()
        r.adicionar(TipoVeiculo.CARRO, "Carro", tanqueLitros = 45.0)
        r.adicionar(TipoVeiculo.MOTO, "Moto", tanqueLitros = 13.0)
        assertEquals(listOf("v1"), store.viagensAtribuidas)
    }

    @Test
    fun `veiculos faltantes das linhas tem tanque do hodometro e marca do perfil no migrado`() {
        val r = veiculosFaltantes(listOf(VEICULO_LEGADO_ID, "x"), UserProfile("Ana", "Honda", "Hunter", 2022), mapOf("x" to 50.0))
        assertEquals(listOf(VEICULO_LEGADO_ID, "x"), r.map { it.id })
        assertEquals("Honda", r[0].marca)
        assertEquals(13.0, r[0].tanqueLitros)
        assertEquals("", r[1].marca)
        assertEquals(50.0, r[1].tanqueLitros)
        assertTrue(r.all { it.tipo == "MOTO" && it.apelido == "Minha moto" })
        assertTrue(veiculosFaltantes(emptyList(), null, emptyMap()).isEmpty())
    }

    @Test
    fun `preferido inexistente cai no primeiro e ativoAgora concorda com o fluxo`() = runTest {
        val r = repo()
        r.adicionar(TipoVeiculo.CARRO, "Carro 1", tanqueLitros = 45.0)
        r.adicionar(TipoVeiculo.CARRO, "Carro", tanqueLitros = 45.0)
        ativo.gravar("fantasma") // p.ex. o perfil sincronizado trouxe um id que este aparelho nao tem
        advanceUntilIdle()
        assertEquals("v1", r.veiculoAtivo.value?.id)
        assertEquals("v1", r.ativoAgora()?.id)
    }

    @Test
    fun `editar muda os dados, mantem id e criacao e marca sujo`() = runTest {
        val r = repo()
        val v = r.adicionar(TipoVeiculo.CARRO, "Carro 1", tanqueLitros = 45.0)
        store.salvar(store.getVeiculo("v1")!!.copy(dirty = false))
        r.editar(v.copy(apelido = "Hunter", marca = "Honda", modelo = "Hunter 350", ano = 2023, tanqueLitros = 13.0))
        val e = store.getVeiculo("v1")!!
        assertEquals("Hunter", e.apelido)
        assertEquals("Honda", e.marca)
        assertEquals(2023, e.ano)
        assertEquals(v.criadoEm, e.criadoEm)
        assertTrue(e.dirty)
        assertTrue(e.updatedAtMs > v.criadoEm)
    }

    @Test
    fun `editar o tanque tambem atualiza o tanque do hodometro do veiculo`() = runTest {
        val r = repo()
        val v = r.adicionar(TipoVeiculo.CARRO, "Carro", tanqueLitros = 45.0)
        r.editar(v.copy(tanqueLitros = 55.0))
        assertEquals(55.0, store.tanquesDoHodometro["v1"])
        assertEquals(55.0, store.getVeiculo("v1")!!.tanqueLitros)
    }

    // ---------- veículo migrado: preenchimento pelo perfil ----------

    private fun legado(marca: String = "", modelo: String = "", ano: Int? = null) =
        VeiculoEntity(id = VEICULO_LEGADO_ID, tipo = "MOTO", apelido = "Minha moto", marca = marca, modelo = modelo, ano = ano, tanqueLitros = 13.0, dirty = false)

    @Test
    fun `primeira abertura preenche marca modelo e ano do veiculo migrado pelo perfil`() = runTest {
        store.salvar(legado())
        repo().completarLegadoDoPerfil(UserProfile("Ana", " Honda ", "Hunter 350", 2022))
        val e = store.getVeiculo(VEICULO_LEGADO_ID)!!
        assertEquals("Honda", e.marca)
        assertEquals("Hunter 350", e.modelo)
        assertEquals(2022, e.ano)
        assertTrue(e.dirty)
    }

    @Test
    fun `nao sobrescreve o que ja esta preenchido nem usa perfil vazio`() = runTest {
        store.salvar(legado(marca = "Yamaha"))
        val r = repo()
        r.completarLegadoDoPerfil(UserProfile("Ana", "Honda", "Hunter", 2022))
        assertEquals("Yamaha", store.getVeiculo(VEICULO_LEGADO_ID)!!.marca)
        assertNull(completarVeiculoLegado(legado(), UserProfile("Ana", null, " ", null)))
        assertNull(completarVeiculoLegado(legado(), null))
    }

    @Test
    fun `so o veiculo migrado e completado pelo perfil`() {
        val outro = legado().copy(id = "outro")
        assertNull(completarVeiculoLegado(outro, UserProfile("Ana", "Honda", "Hunter", 2022)))
    }

    @Test
    fun `sem veiculo migrado a rotina nao faz nada`() = runTest {
        repo().completarLegadoDoPerfil(UserProfile("Ana", "Honda", "Hunter", 2022))
        assertTrue(store.todos.value.isEmpty())
    }
}
