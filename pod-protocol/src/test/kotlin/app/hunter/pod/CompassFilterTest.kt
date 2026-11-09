package app.hunter.pod

import kotlin.test.Test
import kotlin.test.assertEquals

class CompassFilterTest {
    @Test
    fun `primeira leitura usa o setor direto`() {
        assertEquals(CompassDirection.E, CompassFilter().update(95f))
    }

    @Test
    fun `oscilar na borda nao troca de setor`() {
        val f = CompassFilter(hysteresisDeg = 12f)
        assertEquals(CompassDirection.N, f.update(10f))
        // 22.5 é a borda N/NE; até 34.5 continua N
        for (b in listOf(23f, 30f, 34f, 20f, 25f)) assertEquals(CompassDirection.N, f.update(b), "rumo $b")
        assertEquals(CompassDirection.NE, f.update(40f))
    }

    @Test
    fun `histerese funciona atravessando o norte (359 e 1 grau)`() {
        val f = CompassFilter()
        assertEquals(CompassDirection.N, f.update(359f))
        assertEquals(CompassDirection.N, f.update(350f))
        assertEquals(CompassDirection.N, f.update(5f))
        assertEquals(CompassDirection.NW, f.update(310f))
    }

    @Test
    fun `mudanca grande troca na hora`() {
        val f = CompassFilter()
        f.update(0f)
        assertEquals(CompassDirection.S, f.update(180f))
    }
}
