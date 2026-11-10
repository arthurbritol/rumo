package app.hunter.model

import kotlin.math.roundToInt

/**
 * Cálculos matemáticos e semântica de paridade entre Etanol e Gasolina.
 *
 * Baseado na equivalência energética média de 70% (regra dos 70%).
 */
object FuelParityMath {
    /** Limiar de equivalência energética: 70% (0.70). */
    const val PARITY_THRESHOLD: Double = 0.70

    /**
     * Calcula a razão entre o preço do Etanol e o da Gasolina.
     * Retorna `null` se qualquer preço for menor ou igual a zero, ou não for número finito,
     * prevenindo divisão por zero e resultados inconsistentes (Review Focus 1).
     */
    fun calculateRatio(etanolPrice: Double, gasolinaPrice: Double): Double? {
        if (!etanolPrice.isFinite() || !gasolinaPrice.isFinite()) return null
        if (etanolPrice <= 0.0 || gasolinaPrice <= 0.0) return null
        return etanolPrice / gasolinaPrice
    }

    /**
     * Retorna `true` se o Etanol for financeiramente vantajoso em relação à Gasolina (razão <= 70%).
     */
    fun isEtanolAdvantageous(ratio: Double): Boolean = ratio <= PARITY_THRESHOLD

    /**
     * Formata a descrição da paridade em pt-BR com indicação de qual combustível compensa mais.
     * Exemplos:
     * - "Etanol a 70% da gasolina · Etanol compensa"
     * - "Etanol a 72% da gasolina · Gasolina compensa"
     */
    fun formatParityDescription(ratio: Double): String {
        val pct = (ratio * 100).roundToInt()
        return if (isEtanolAdvantageous(ratio)) {
            "Etanol a $pct% da gasolina · Etanol compensa"
        } else {
            "Etanol a $pct% da gasolina · Gasolina compensa"
        }
    }
}
