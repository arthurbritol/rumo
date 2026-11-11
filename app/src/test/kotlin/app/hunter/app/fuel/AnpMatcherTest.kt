package app.hunter.app.fuel

import app.hunter.app.data.anp.AnpStationEntity
import app.hunter.nav.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnpMatcherTest {
    private fun anp(id: String, end: String, brand: String = "RAIZEN", cep: String = "") = AnpStationEntity(
        id = id, nome = "POSTO", bandeira = brand, cityKey = "es_serra", uf = "ES", endereco = end, bairro = "", cep = cep,
        lat = 0.0, lon = 0.0, precoGasolina = 6.0,
    )

    @Test
    fun `casa por rua e numero ignorando tipo de via, caixa e acento perdido`() {
        val list = listOf(anp("a", "AVENIDA AM�RICO BUAIZ, 1285"), anp("b", "AVENIDA AMERICO BUAIZ, 999"))
        val p = Place("Posto Escola", null, 0.0, 0.0, street = "Av. Américo Buaiz", houseNumber = "1285")
        assertEquals("a", AnpMatcher.match(p, list)?.id)
    }

    @Test
    fun `casa por rua e CEP quando o mapa nao tem numero`() {
        val list = listOf(anp("a", "RUA DAS FLORES, 10", cep = "29000100"), anp("b", "RUA DAS FLORES, 20", cep = "29000200"))
        val p = Place("Posto", null, 0.0, 0.0, street = "Rua das Flores", postcode = "29000-200")
        assertEquals("b", AnpMatcher.match(p, list)?.id)
    }

    @Test
    fun `casa por rua e bandeira quando e unica e o mapa nao tem numero`() {
        val list = listOf(anp("a", "RUA DAS FLORES, 10", brand = "VIBRA"), anp("b", "RUA DAS FLORES, 20", brand = "RAIZEN"))
        val p = Place("Shell Centro", null, 0.0, 0.0, street = "Rua das Flores")
        assertEquals("b", AnpMatcher.match(p, list)?.id)
    }

    @Test
    fun `nao casa quando a rua ou o numero nao batem e nunca chuta`() {
        val list = listOf(anp("a", "RUA DAS FLORES, 10"), anp("b", "RUA DAS FLORES, 20"))
        assertNull(AnpMatcher.match(Place("Posto", null, 0.0, 0.0, street = "Rua das Flores", houseNumber = "30"), list))
        assertNull(AnpMatcher.match(Place("Posto", null, 0.0, 0.0, street = "Rua das Flores"), list)) // duas na rua, sem como escolher
        assertNull(AnpMatcher.match(Place("Posto", null, 0.0, 0.0, street = "Rua Zeferino Vaz", houseNumber = "10"), list))
        assertNull(AnpMatcher.match(Place("Posto", null, 0.0, 0.0), list))
    }

    private fun named(id: String, nome: String, end: String, brand: String = "VIBRA") =
        anp(id, end, brand).copy(nome = nome)

    @Test
    fun `casa pelo nome do posto mesmo com a rua diferente no mapa`() {
        val list = listOf(
            named("coliseu", "POSTO COLISEU LTDA", "AVENIDA DESEMBARGADOR MARIO DA SILVA NUNES, 1637"),
            named("solemar", "POSTO SOLEMAR", "AVENIDA ABIDO SAAD, 2100", "RAIZEN"),
        )
        val p = Place("Posto Coliseu", null, 0.0, 0.0, street = "Avenida Brigadeiro Eduardo Gomes", houseNumber = "1637")
        assertEquals("coliseu", AnpMatcher.match(p, list)?.id)
        assertEquals("solemar", AnpMatcher.match(Place("Posto solemar", null, 0.0, 0.0, street = "Avenida Abido Saade"), list)?.id)
    }

    @Test
    fun `varios postos do mapa na mesma rua nao herdam o preco do unico posto da ANP dela`() {
        val list = listOf(named("coliseu", "POSTO COLISEU LTDA", "AVENIDA DESEMBARGADOR MARIO DA SILVA NUNES, 1637"))
        for (nome in listOf("Posto Rodeio", "Posto Castelo", "Posto Pioneiro do Gás", "Shell")) {
            val p = Place(nome, null, 0.0, 0.0, street = "Avenida Desembargador Mário da Silva Nunes")
            assertNull(nome, AnpMatcher.match(p, list))
        }
    }

    @Test
    fun `nome so com bandeira ou palavra generica nao casa`() {
        val list = listOf(named("a", "POSTO SHELL LTDA", "RUA X, 1", "RAIZEN"), named("b", "AUTO POSTO Y", "RUA Z, 2"))
        assertNull(AnpMatcher.match(Place("Posto Shell", null, 0.0, 0.0), list))
    }
}
