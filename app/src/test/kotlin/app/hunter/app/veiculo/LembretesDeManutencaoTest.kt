package app.hunter.app.veiculo

import app.hunter.model.DueState
import app.hunter.model.MaintenanceItem
import app.hunter.model.TipoVeiculo
import app.hunter.model.Veiculo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LembretesDeManutencaoTest {
    private val agora = 1_000_000_000_000L

    private fun veiculo(id: String, apelido: String, tipo: TipoVeiculo = TipoVeiculo.MOTO) = Veiculo(
        id, tipo, apelido, "", "", null, 13.0, null, 1, null,
    )

    // vencido por km: feito aos 0 km, intervalo 1000 km, hodometro em 1500
    private fun item(id: Long, nome: String) = MaintenanceItem(id, nome, 1000, null, 0.0, agora)

    @Test
    fun `avisa a manutencao vencida de todos os veiculos, cada um com o km dele`() {
        val moto = ManutencaoDoVeiculo(veiculo("m", "Hunter"), listOf(item(1, "Oleo")), km = 1500.0)
        val carro = ManutencaoDoVeiculo(veiculo("c", "Argo", TipoVeiculo.CARRO), listOf(item(2, "Pastilhas")), km = 100.0) // em dia
        val outroCarro = ManutencaoDoVeiculo(veiculo("c2", "Onix", TipoVeiculo.CARRO), listOf(item(3, "Filtro")), km = 2000.0)
        val d = LembretesDeManutencao.devidos(listOf(moto, carro, outroCarro), agora, emptyMap())
        assertEquals(listOf(1L, 3L), d.map { it.due.item.id }.sorted())
        assertTrue(d.none { it.due.item.id == 2L })
    }

    @Test
    fun `nao repete o que ja foi avisado e o registro soma os veiculos`() {
        val moto = ManutencaoDoVeiculo(veiculo("m", "Hunter"), listOf(item(1, "Oleo")), km = 1500.0)
        val carro = ManutencaoDoVeiculo(veiculo("c", "Argo", TipoVeiculo.CARRO), listOf(item(2, "Pastilhas")), km = 1500.0)
        val reg = LembretesDeManutencao.registro(listOf(moto, carro), agora)
        assertEquals(mapOf(1L to DueState.OVERDUE, 2L to DueState.OVERDUE), reg)
        assertTrue(LembretesDeManutencao.devidos(listOf(moto, carro), agora, reg).isEmpty())
    }

    @Test
    fun `com mais de um veiculo a linha leva o nome dele, com um so nao`() {
        val moto = ManutencaoDoVeiculo(veiculo("m", "Hunter"), listOf(item(1, "Oleo")), km = 1500.0)
        val d = LembretesDeManutencao.devidos(listOf(moto), agora, emptyMap()).single()
        assertTrue(LembretesDeManutencao.linha(d, 2).startsWith("Hunter: Oleo"))
        assertTrue(LembretesDeManutencao.linha(d, 1).startsWith("Oleo"))
        assertEquals("Manutenção de Hunter", LembretesDeManutencao.titulo(listOf(d), 2))
        assertEquals("Manutenção da moto", LembretesDeManutencao.titulo(listOf(d), 1))
        assertEquals("Manutenção dos seus veículos (2 itens)", LembretesDeManutencao.titulo(listOf(d, d), 2))
    }
}
