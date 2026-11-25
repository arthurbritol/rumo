package app.hunter.app.veiculo

import app.hunter.model.MaintenanceMath
import app.hunter.model.TipoVeiculo
import app.hunter.nav.RouteProfile
import app.hunter.nav.TipoDeRota
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PadroesDoVeiculoTest {
    @Test fun `semente da moto usa os itens de moto e fica no veiculo`() {
        val itens = itensDeManutencaoPadrao("v1", TipoVeiculo.MOTO)
        assertEquals(MaintenanceMath.defaultItems.map { it.first }, itens.map { it.name })
        assertTrue(itens.all { it.veiculoId == "v1" })
    }

    @Test fun `semente do carro usa os itens de carro com os intervalos`() {
        val itens = itensDeManutencaoPadrao("v2", TipoVeiculo.CARRO)
        assertEquals(MaintenanceMath.defaultItemsCarro.map { it.first }, itens.map { it.name })
        val oleo = itens.first { it.name == "Óleo e filtro de óleo" }
        assertEquals(10000, oleo.intervalKm)
        assertEquals(365, oleo.intervalDays)
        assertTrue(itens.none { it.name.contains("corrente", ignoreCase = true) })
        assertTrue(itens.all { it.veiculoId == "v2" })
    }

    @Test fun `tipo do veiculo vira tipo de rota e sem veiculo e moto`() {
        assertEquals(TipoDeRota.CARRO, TipoVeiculo.CARRO.paraRota())
        assertEquals(TipoDeRota.MOTO, TipoVeiculo.MOTO.paraRota())
        assertEquals(TipoDeRota.MOTO, (null as TipoVeiculo?).paraRota())
    }

    @Test fun `trocar de moto para carro derruba perfil que o carro nao tem`() {
        assertEquals(RouteProfile.FASTEST, perfilAoTrocarDeTipo(RouteProfile.CURVES, TipoDeRota.CARRO))
        assertEquals(RouteProfile.FASTEST, perfilAoTrocarDeTipo(RouteProfile.NO_DIRT, TipoDeRota.CARRO))
        assertEquals(RouteProfile.NO_TOLLS, perfilAoTrocarDeTipo(RouteProfile.NO_TOLLS, TipoDeRota.CARRO))
        assertEquals(RouteProfile.FASTEST, perfilAoTrocarDeTipo(RouteProfile.NO_FERRY, TipoDeRota.MOTO))
    }
}
