package app.hunter.app.ui

import app.hunter.app.R
import app.hunter.model.TipoVeiculo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AbasVisiveisTest {
    @Test fun `moto e sem veiculo mostram todas as abas`() {
        assertEquals(Tab.entries.toList(), abasVisiveis(TipoVeiculo.MOTO))
        assertEquals(Tab.entries.toList(), abasVisiveis(null))
    }

    @Test fun `carro esconde so o Pod e mantem a ordem`() {
        val abas = abasVisiveis(TipoVeiculo.CARRO)
        assertFalse(Tab.Pod in abas)
        assertEquals(listOf(Tab.Map, Tab.Trips, Tab.Garage, Tab.Friends), abas)
        assertTrue(Tab.Map in abas)
    }

    @Test fun `icone da Garagem segue o veiculo`() {
        assertEquals(R.drawable.fa_car, Tab.Garage.iconeParaVeiculo(TipoVeiculo.CARRO))
        assertEquals(R.drawable.fa_motorcycle, Tab.Garage.iconeParaVeiculo(TipoVeiculo.MOTO))
        assertEquals(R.drawable.fa_motorcycle, Tab.Garage.iconeParaVeiculo(null))
        assertEquals(R.drawable.fa_route, Tab.Trips.iconeParaVeiculo(TipoVeiculo.CARRO))
    }
}
