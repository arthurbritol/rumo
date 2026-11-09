package app.hunter.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BikeCatalogTest {
    @Test
    fun `contém as marcas do spec`() {
        val nomes = BikeCatalog.brands.map { it.name }
        listOf(
            "Royal Enfield", "Yamaha", "Honda", "Kawasaki", "Triumph", "BMW",
            "KTM", "TVS", "Bajaj", "Harley-Davidson", "Outra",
        ).forEach { assertTrue(it in nomes, "faltou $it") }
    }

    @Test
    fun `Royal Enfield lista Hunter 350, Meteor 350, Classic 350 e Himalayan`() {
        val modelos = BikeCatalog.models("Royal Enfield")
        listOf("Hunter 350", "Meteor 350", "Classic 350", "Himalayan").forEach {
            assertTrue(it in modelos, "faltou $it")
        }
    }

    @Test
    fun `Outra não tem modelos fixos`() {
        assertTrue(BikeCatalog.models("Outra").isEmpty())
    }

    @Test
    fun `models de marca desconhecida é vazio`() {
        assertTrue(BikeCatalog.models("Marca Inexistente").isEmpty())
    }

    @Test
    fun `nomes de modelos são únicos por marca`() {
        BikeCatalog.brands.forEach { b ->
            assertEquals(b.models.size, b.models.toSet().size, "duplicado em ${b.name}")
        }
    }
}
