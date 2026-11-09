package app.hunter.app.ui

const val TRIP_LOCAL_ONLY_MESSAGE = "O trajeto desta viagem só existe no aparelho em que ela foi gravada"

/**
 * Texto no lugar do mapa do detalhe da viagem, ou null se o mapa pode ser desenhado.
 * [pointCount] null = ainda carregando. Viagens vindas da nuvem não trazem pontos (zero).
 */
fun tripMapPlaceholder(pointCount: Int?): String? = when {
    pointCount == null -> "Carregando…"
    pointCount == 0 -> TRIP_LOCAL_ONLY_MESSAGE
    pointCount < 2 -> "Sem pontos suficientes para o mapa."
    else -> null
}
