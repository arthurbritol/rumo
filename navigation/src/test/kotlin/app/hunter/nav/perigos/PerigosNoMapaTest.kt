package app.hunter.nav.perigos

import kotlin.test.Test
import kotlin.test.assertEquals

class PerigosNoMapaTest {
    private val todos = listOf(
        perigo("perto-radar", 1_000.0),
        perigo("perto-lombada", 2_000.0, tipo = Perigo.LOMBADA),
        perigo("longe", 40_000.0),
        perigo("trem", 3_000.0, tipo = Perigo.TRAVESSIA_FERROVIARIA),
    )

    @Test fun `so dentro do raio de 25 km, do mais perto ao mais longe`() {
        val r = PerigosNoMapa.selecionar(todos, CategoriaPerigo.values().toSet(), 0.0, 0.0)
        assertEquals(listOf("perto-radar", "perto-lombada", "trem"), r.map { it.id })
    }

    @Test fun `respeita as categorias ligadas`() {
        assertEquals(listOf("perto-radar"), PerigosNoMapa.selecionar(todos, setOf(CategoriaPerigo.RADARES), 0.0, 0.0).map { it.id })
        assertEquals(emptyList(), PerigosNoMapa.selecionar(todos, emptySet(), 0.0, 0.0))
    }

    @Test fun `teto de pontos fica com os mais perto`() {
        assertEquals(listOf("perto-radar", "perto-lombada"), PerigosNoMapa.selecionar(todos, CategoriaPerigo.values().toSet(), 0.0, 0.0, max = 2).map { it.id })
    }

    @Test fun `sem posicao so aplica categoria e teto`() {
        assertEquals(4, PerigosNoMapa.selecionar(todos, CategoriaPerigo.values().toSet(), null, null).size)
    }
}
