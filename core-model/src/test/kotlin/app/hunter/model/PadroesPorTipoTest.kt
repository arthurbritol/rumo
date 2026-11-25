package app.hunter.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PadroesPorTipoTest {
    private fun f(id: Long, odo: Double, l: Double, full: Boolean = true) = Fuelup(id, id, odo, l, l * 6, full)

    @Test fun `itens padrao da moto continuam os mesmos`() {
        val moto = MaintenanceMath.itensPadrao(TipoVeiculo.MOTO)
        assertEquals(MaintenanceMath.defaultItems, moto)
        assertEquals(7, moto.size)
        assertTrue(moto.any { it.first == "Lubrificar corrente" })
    }

    @Test fun `itens padrao do carro sao de carro e tem intervalo`() {
        val nomes = MaintenanceMath.itensPadrao(TipoVeiculo.CARRO).map { it.first }
        assertFalse("Lubrificar corrente" in nomes)
        for (parte in listOf("Óleo e filtro de óleo", "Filtro de ar", "Filtro de combustível", "Pneus (rodízio", "Pastilhas de freio", "Discos de freio", "Fluido de freio", "Correia dentada", "Velas", "Bateria", "Revisão geral")) {
            assertTrue(nomes.any { it.startsWith(parte) }, "falta $parte")
        }
        assertEquals(nomes.size, nomes.toSet().size)
        assertTrue(MaintenanceMath.itensPadrao(TipoVeiculo.CARRO).all { (_, km, dias) -> km != null || dias != null })
    }

    @Test fun `consumo generico por tipo`() {
        assertEquals(28.0, FuelPlanner.consumoGenerico(TipoVeiculo.MOTO), 0.0)
        assertEquals(12.0, FuelPlanner.consumoGenerico(TipoVeiculo.CARRO), 0.0)
    }

    @Test fun `consumo padrao do veiculo tem precedencia sobre o generico`() {
        assertEquals(15.0, FuelPlanner.consumoPadrao(TipoVeiculo.CARRO, 15.0), 0.0)
        assertEquals(12.0, FuelPlanner.consumoPadrao(TipoVeiculo.CARRO, null), 0.0)
        assertEquals(12.0, FuelPlanner.consumoPadrao(TipoVeiculo.CARRO, 0.0), 0.0)
        assertEquals(28.0, FuelPlanner.consumoPadrao(null, null), 0.0)
    }

    @Test fun `autonomia sem historico usa o consumo padrao informado`() {
        val ups = listOf(f(1, 1000.0, 10.0))
        val generico = FuelPlanner.state(45.0, null, 1000.0, ups, FuelPlanner.consumoPadrao(TipoVeiculo.CARRO, null))!!
        assertEquals(45.0 * 12.0, generico.rangeKm, 1e-6)
        assertTrue(generico.usedDefaultConsumption)
        val informado = FuelPlanner.state(45.0, null, 1000.0, ups, FuelPlanner.consumoPadrao(TipoVeiculo.CARRO, 14.0))!!
        assertEquals(45.0 * 14.0, informado.rangeKm, 1e-6)
    }

    @Test fun `com historico a media real vence o consumo padrao`() {
        val ups = listOf(f(1, 1000.0, 10.0))
        val s = FuelPlanner.state(45.0, 10.0, 1000.0, ups, defaultKmPerL = 14.0)!!
        assertEquals(450.0, s.rangeKm, 1e-6)
        assertFalse(s.usedDefaultConsumption)
    }

    @Test fun `abastecimento de um veiculo nao altera o consumo do outro`() {
        // Cada veículo resume só os seus abastecimentos (o ViewModel filtra por veiculoId ativo).
        val carro = listOf(f(1, 1000.0, 40.0), f(2, 1480.0, 40.0)) // 12 km/L
        val moto = listOf(f(3, 500.0, 10.0), f(4, 780.0, 10.0)) // 28 km/L
        val soCarro = FuelMath.summarize(carro).avgKmPerL
        val soMoto = FuelMath.summarize(moto).avgKmPerL
        assertEquals(12.0, soCarro!!, 1e-9)
        assertEquals(28.0, soMoto!!, 1e-9)
        // Misturar os dois (o erro que o filtro evita) daria outro número.
        val misturado = FuelMath.summarize(carro + moto).avgKmPerL
        assertNotNull(misturado)
        assertTrue(kotlin.math.abs(misturado!! - 12.0) > 0.01 || kotlin.math.abs(misturado - 28.0) > 0.01)
        assertNull(FuelMath.summarize(emptyList()).avgKmPerL)
    }
}
