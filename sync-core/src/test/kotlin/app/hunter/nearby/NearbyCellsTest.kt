package app.hunter.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NearbyCellsTest {
    @Test
    fun `cellId usa o piso de latitude e longitude`() {
        assertEquals("-21:-41", cellId(-20.31, -40.31))
        assertEquals("0:0", cellId(0.5, 0.5))
    }

    @Test
    fun `a 20 km do centro de um quadrado devolve 1 id`() {
        assertEquals(setOf("-21:-41"), cellsInRadius(-20.5, -40.5, 20.0))
    }

    @Test
    fun `a 5 km da quina entre quatro quadrados devolve 4 ids distintos`() {
        val ids = cellsInRadius(-21.0, -41.0, 5.0)
        assertEquals(setOf("-22:-42", "-22:-41", "-21:-42", "-21:-41"), ids)
    }

    @Test
    fun `perto de uma divisa so toca 2 quadrados`() {
        // 2 km ao norte da divisa de latitude -21, longe da de longitude.
        val ids = cellsInRadius(-20.99, -40.5, 5.0)
        assertEquals(setOf("-21:-41", "-22:-41"), ids)
    }

    @Test
    fun `exemplos do Brasil, Vitoria e Sao Paulo, ficam em 1 quadrado com 5 km`() {
        assertEquals(setOf("-21:-41"), cellsInRadius(-20.3155, -40.3128, 5.0))
        assertEquals(setOf("-24:-47"), cellsInRadius(-23.5505, -46.6333, 5.0))
    }

    @Test
    fun `raio maior que o quadrado ainda devolve poucos ids`() {
        val ids = cellsInRadius(-20.5, -40.95, 20.0)
        assertTrue(ids.size in 2..4, "$ids")
        assertTrue("-21:-41" in ids)
    }
}
