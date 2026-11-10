package app.hunter.app.ui

import app.hunter.app.fuel.FuelPriceReference
import app.hunter.app.fuel.PriceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GarageFuelPriceSuggestionTest {

    @Test
    fun `buildFuelPriceSuggestionText formata referencia municipal com gasolina`() {
        val ref = FuelPriceReference(
            regiaoNome = "Recife/PE",
            precoGasolina = 5.82,
            precoGasolinaAdit = 6.05,
            precoEtanol = 4.10,
            precoDiesel = 5.90,
            fonte = PriceSource.MEDIA_MUNICIPAL,
            dataRef = "11/2026",
        )
        val text = buildFuelPriceSuggestionText(ref)
        assertEquals("Média em Recife/PE: R$ 5,82/L (Gasolina)", text)
    }

    @Test
    fun `buildFuelPriceSuggestionText formata referencia estadual com gasolina`() {
        val ref = FuelPriceReference(
            regiaoNome = "SP",
            precoGasolina = 5.85,
            precoGasolinaAdit = null,
            precoEtanol = 3.90,
            precoDiesel = 5.80,
            fonte = PriceSource.MEDIA_ESTADUAL,
            dataRef = "11/2026",
        )
        val text = buildFuelPriceSuggestionText(ref)
        assertEquals("Média estadual (SP): R$ 5,85/L", text)
    }

    @Test
    fun `buildFuelPriceSuggestionText fallback quando apenas gasolina esta disponivel`() {
        val ref = FuelPriceReference(
            regiaoNome = "Curitiba/PR",
            precoGasolina = 6.10,
            precoGasolinaAdit = null,
            precoEtanol = null,
            precoDiesel = null,
            fonte = PriceSource.MEDIA_MUNICIPAL,
            dataRef = "11/2026",
        )
        val text = buildFuelPriceSuggestionText(ref)
        assertEquals("Média em Curitiba/PR: R$ 6,10/L (Gasolina)", text)
    }

    @Test
    fun `buildFuelPriceSuggestionText fallback quando apenas etanol esta disponivel em municipio`() {
        val ref = FuelPriceReference(
            regiaoNome = "Campinas/SP",
            precoGasolina = null,
            precoGasolinaAdit = null,
            precoEtanol = 3.85,
            precoDiesel = null,
            fonte = PriceSource.MEDIA_MUNICIPAL,
            dataRef = "11/2026",
        )
        val text = buildFuelPriceSuggestionText(ref)
        assertEquals("Média em Campinas/SP: R$ 3,85/L (Etanol)", text)
    }

    @Test
    fun `buildFuelPriceSuggestionText fallback quando apenas etanol esta disponivel em estado`() {
        val ref = FuelPriceReference(
            regiaoNome = "GO",
            precoGasolina = null,
            precoGasolinaAdit = null,
            precoEtanol = 3.75,
            precoDiesel = null,
            fonte = PriceSource.MEDIA_ESTADUAL,
            dataRef = "11/2026",
        )
        val text = buildFuelPriceSuggestionText(ref)
        assertEquals("Média estadual (GO): R$ 3,75/L (Etanol)", text)
    }

    @Test
    fun `buildFuelPriceSuggestionText retorna null quando nenhum preco util esta disponivel`() {
        val ref = FuelPriceReference(
            regiaoNome = "Remoto/AM",
            precoGasolina = null,
            precoGasolinaAdit = null,
            precoEtanol = null,
            precoDiesel = 6.00,
            fonte = PriceSource.MEDIA_MUNICIPAL,
            dataRef = "11/2026",
        )
        assertNull(buildFuelPriceSuggestionText(ref))
    }

    @Test
    fun `resolveSuggestedFuelPrice prioriza gasolina e recorre ao etanol`() {
        val refBoth = FuelPriceReference(
            regiaoNome = "Recife/PE",
            precoGasolina = 5.82,
            precoGasolinaAdit = null,
            precoEtanol = 4.10,
            precoDiesel = null,
            fonte = PriceSource.MEDIA_MUNICIPAL,
            dataRef = "11/2026",
        )
        assertEquals(5.82, resolveSuggestedFuelPrice(refBoth)!!, 0.001)

        val refOnlyEthanol = FuelPriceReference(
            regiaoNome = "Recife/PE",
            precoGasolina = null,
            precoGasolinaAdit = null,
            precoEtanol = 4.10,
            precoDiesel = null,
            fonte = PriceSource.MEDIA_MUNICIPAL,
            dataRef = "11/2026",
        )
        assertEquals(4.10, resolveSuggestedFuelPrice(refOnlyEthanol)!!, 0.001)

        val refNone = FuelPriceReference(
            regiaoNome = "Recife/PE",
            precoGasolina = null,
            precoGasolinaAdit = null,
            precoEtanol = null,
            precoDiesel = null,
            fonte = PriceSource.MEDIA_MUNICIPAL,
            dataRef = "11/2026",
        )
        assertNull(resolveSuggestedFuelPrice(refNone))
    }

    @Test
    fun `calculateCostFromLiters calcula valor total corretamente`() {
        val cost = calculateCostFromLiters(liters = 10.0, pricePerLiter = 5.82)
        assertEquals(58.20, cost, 0.001)

        val cost2 = calculateCostFromLiters(liters = 12.5, pricePerLiter = 4.00)
        assertEquals(50.00, cost2, 0.001)
    }

    @Test
    fun `calculateCostFromLiters protege contra zero negativo ou invalidos`() {
        assertEquals(0.0, calculateCostFromLiters(liters = 0.0, pricePerLiter = 5.82), 0.0001)
        assertEquals(0.0, calculateCostFromLiters(liters = -5.0, pricePerLiter = 5.82), 0.0001)
        assertEquals(0.0, calculateCostFromLiters(liters = 10.0, pricePerLiter = 0.0), 0.0001)
        assertEquals(0.0, calculateCostFromLiters(liters = 10.0, pricePerLiter = -5.82), 0.0001)
        assertEquals(0.0, calculateCostFromLiters(liters = Double.NaN, pricePerLiter = 5.82), 0.0001)
        assertEquals(0.0, calculateCostFromLiters(liters = 10.0, pricePerLiter = Double.POSITIVE_INFINITY), 0.0001)
    }

    @Test
    fun `calculateLitersFromCost calcula litros a partir do valor total`() {
        val liters = calculateLitersFromCost(cost = 58.20, pricePerLiter = 5.82)
        assertEquals(10.0, liters, 0.001)

        val liters2 = calculateLitersFromCost(cost = 50.00, pricePerLiter = 4.00)
        assertEquals(12.5, liters2, 0.001)
    }

    @Test
    fun `calculateLitersFromCost protege contra zero negativo ou invalidos sem divisao por zero`() {
        assertEquals(0.0, calculateLitersFromCost(cost = 0.0, pricePerLiter = 5.82), 0.0001)
        assertEquals(0.0, calculateLitersFromCost(cost = -50.0, pricePerLiter = 5.82), 0.0001)
        assertEquals(0.0, calculateLitersFromCost(cost = 58.20, pricePerLiter = 0.0), 0.0001)
        assertEquals(0.0, calculateLitersFromCost(cost = 58.20, pricePerLiter = -5.82), 0.0001)
        assertEquals(0.0, calculateLitersFromCost(cost = Double.NaN, pricePerLiter = 5.82), 0.0001)
        assertEquals(0.0, calculateLitersFromCost(cost = 58.20, pricePerLiter = Double.NaN), 0.0001)
        assertEquals(0.0, calculateLitersFromCost(cost = 58.20, pricePerLiter = Double.POSITIVE_INFINITY), 0.0001)
    }
}
