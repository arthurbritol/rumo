package app.hunter.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CarCatalogTest {
    @Test
    fun `nao vazio e termina em Outra sem modelos`() {
        assertTrue(CarCatalog.brands.size >= 15)
        assertEquals("Outra", CarCatalog.brands.last().name)
        assertTrue(CarCatalog.models("Outra").isEmpty())
        assertTrue(CarCatalog.brands.dropLast(1).all { it.models.isNotEmpty() })
    }

    @Test
    fun `sem marcas nem modelos duplicados e nomes dentro do limite de 60`() {
        val nomes = CarCatalog.brands.map { it.name }
        assertEquals(nomes.size, nomes.toSet().size)
        CarCatalog.brands.forEach { b ->
            assertEquals(b.models.size, b.models.toSet().size, "duplicado em ${b.name}")
            assertTrue(b.name.length <= 60 && b.models.all { it.isNotBlank() && it.length <= 60 })
        }
    }

    @Test
    fun `modelos populares estao nas marcas certas`() {
        assertTrue("Onix" in CarCatalog.models("Chevrolet"))
        assertTrue("Strada" in CarCatalog.models("Fiat"))
        assertTrue("T-Cross" in CarCatalog.models("Volkswagen"))
        assertTrue("Hilux" in CarCatalog.models("Toyota"))
        assertTrue("HB20" in CarCatalog.models("Hyundai"))
        assertTrue("208" in CarCatalog.models("Peugeot"))
        assertTrue("C3" in CarCatalog.models("Citroën"))
        assertTrue(CarCatalog.models("Inexistente").isEmpty())
    }
}
