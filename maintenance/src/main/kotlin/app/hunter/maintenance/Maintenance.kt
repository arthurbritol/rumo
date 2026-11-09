package app.hunter.maintenance

/**
 * Hodômetro da moto: deslocamento informado pelo usuário + soma dos pedais gravados em `:trips`.
 * Cada pedal finalizado atualiza a quilometragem, que alimenta lembretes e consumo.
 */
object Odometer {
    fun currentKm(offsetKm: Double, tripsDistanceM: Double): Double = offsetKm + tripsDistanceM / 1000.0

    /** Deslocamento que faz o hodômetro calculado igualar o valor lido no painel. */
    fun offsetFor(panelKm: Double, tripsDistanceM: Double): Double = panelKm - tripsDistanceM / 1000.0
}
