package app.hunter.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FuelParityTest {

    @Test
    fun `constante de paridade energetica e 70 por cento`() {
        assertEquals(0.70, FuelParityMath.PARITY_THRESHOLD, 1e-6)
    }

    @Test
    fun `calcula razao entre etanol e gasolina corretamente`() {
        val ratio70 = FuelParityMath.calculateRatio(3.50, 5.00)
        assertEquals(0.70, ratio70!!, 1e-4)

        val ratio72 = FuelParityMath.calculateRatio(4.32, 6.00)
        assertEquals(0.72, ratio72!!, 1e-4)
    }

    @Test
    fun `precos zerados ou negativos retornam nulo sem excecao`() {
        // Review Focus 1
        assertNull(FuelParityMath.calculateRatio(0.0, 5.00))
        assertNull(FuelParityMath.calculateRatio(3.50, 0.0))
        assertNull(FuelParityMath.calculateRatio(0.0, 0.0))
        assertNull(FuelParityMath.calculateRatio(-3.50, 5.00))
        assertNull(FuelParityMath.calculateRatio(3.50, -5.00))
        assertNull(FuelParityMath.calculateRatio(-3.50, -5.00))
        assertNull(FuelParityMath.calculateRatio(Double.NaN, 5.00))
        assertNull(FuelParityMath.calculateRatio(3.50, Double.NaN))
        assertNull(FuelParityMath.calculateRatio(Double.NEGATIVE_INFINITY, 5.00))
        assertNull(FuelParityMath.calculateRatio(3.50, Double.POSITIVE_INFINITY))
    }

    @Test
    fun `etanol e vantajoso quando razao menor ou igual a 70 por cento`() {
        assertTrue(FuelParityMath.isEtanolAdvantageous(0.70))
        assertTrue(FuelParityMath.isEtanolAdvantageous(0.69))
        assertTrue(FuelParityMath.isEtanolAdvantageous(0.50))
    }

    @Test
    fun `gasolina e vantajosa quando razao maior que 70 por cento`() {
        assertFalse(FuelParityMath.isEtanolAdvantageous(0.7001))
        assertFalse(FuelParityMath.isEtanolAdvantageous(0.72))
        assertFalse(FuelParityMath.isEtanolAdvantageous(0.85))
    }

    @Test
    fun `formata descricao de paridade em portugues corretamente`() {
        assertEquals(
            "Etanol a 70% da gasolina · Etanol compensa",
            FuelParityMath.formatParityDescription(0.70)
        )
        assertEquals(
            "Etanol a 72% da gasolina · Gasolina compensa",
            FuelParityMath.formatParityDescription(0.72)
        )
        assertEquals(
            "Etanol a 68% da gasolina · Etanol compensa",
            FuelParityMath.formatParityDescription(0.68)
        )
    }
}
