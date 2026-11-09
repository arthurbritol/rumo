package app.hunter.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TripPointsMessageTest {
    @Test fun `sem pontos explica que o trajeto so existe no aparelho de origem`() {
        assertEquals("O trajeto desta viagem só existe no aparelho em que ela foi gravada", tripMapPlaceholder(0))
    }

    @Test fun `carregando enquanto os pontos nao chegam`() {
        assertEquals("Carregando…", tripMapPlaceholder(null))
    }

    @Test fun `um ponto so nao desenha mapa`() {
        assertEquals("Sem pontos suficientes para o mapa.", tripMapPlaceholder(1))
    }

    @Test fun `com dois ou mais pontos mostra o mapa`() {
        assertNull(tripMapPlaceholder(2))
        assertNull(tripMapPlaceholder(500))
    }
}
