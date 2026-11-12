package app.hunter.app.nearby

import app.hunter.model.Geo
import app.hunter.nearby.Distancia
import app.hunter.nearby.Parada
import app.hunter.nearby.Ritmo
import app.hunter.nearby.Vizinho
import java.util.Locale

// Funções puras (sem Android) do Rolê por perto: propriedades do mapa e textos em pt-BR.

/** Nomes das imagens de moto no estilo do mapa: uma por variante, não por pessoa. */
const val RIDER_ICON = "rider"
const val RIDER_ICON_COMBINA = "rider-combina"

/** Propriedades de uma moto anônima no mapa. `id` é a sessão aleatória; `rumo` nulo vira 0 (aponta para o norte). */
data class RiderFeature(
    val id: String,
    val lat: Double,
    val lon: Double,
    val rumo: Double,
    val destaque: Boolean,
    val icon: String,
)

fun Vizinho.toRiderFeature(): RiderFeature = RiderFeature(
    id = sessao,
    lat = lat,
    lon = lon,
    rumo = rumo ?: 0.0,
    destaque = combina,
    icon = if (combina) RIDER_ICON_COMBINA else RIDER_ICON,
)

/** Imagem do marcador do próprio usuário: a moto laranja enquanto procura rolê, o marcador de sempre fora disso. */
const val OWN_ICON_SEARCHING = "rider-self"
const val OWN_ICON_DEFAULT = "me"

fun ownMarkerIcon(procurando: Boolean): String = if (procurando) OWN_ICON_SEARCHING else OWN_ICON_DEFAULT

private val PT_BR = Locale.forLanguageTag("pt-BR")

/** "ninguém por perto ainda", "1 pessoa por perto", "N pessoas por perto". */
fun pessoasPorPerto(n: Int): String = when {
    n <= 0 -> "ninguém por perto ainda"
    n == 1 -> "1 pessoa por perto"
    else -> "$n pessoas por perto"
}

/** Texto da faixa no mapa. */
fun faixaProcurando(n: Int): String = "Procurando rolê · ${pessoasPorPerto(n)}"

/**
 * Distância arredondada até a posição aproximada de quem está por perto: "a 1,2 km", "a 12 km". A posição já
 * vem com desvio do servidor, então abaixo de 1 km não finge precisão.
 */
fun distanciaAproximada(meLat: Double, meLon: Double, v: Vizinho): String {
    val m = Geo.distanceM(meLat, meLon, v.lat, v.lon)
    return when {
        m < 1000 -> "a menos de 1 km"
        m < 10_000 -> "a " + String.format(PT_BR, "%.1f", m / 1000) + " km"
        else -> "a " + String.format(PT_BR, "%.0f", m / 1000) + " km"
    }
}

val Ritmo.rotulo: String
    get() = when (this) {
        Ritmo.TRANQUILO -> "Tranquilo"
        Ritmo.MODERADO -> "Moderado"
        Ritmo.ANIMADO -> "Animado"
    }

val Distancia.rotulo: String
    get() = when (this) {
        Distancia.ATE50 -> "Até 50 km"
        Distancia.DE50A150 -> "50 a 150 km"
        Distancia.MAIS150 -> "Mais de 150 km"
    }

val Parada.rotulo: String
    get() = when (this) {
        Parada.CAFE -> "Café ou lanche"
        Parada.MIRANTE -> "Mirante"
        Parada.SO_ESTRADA -> "Só estrada"
        Parada.SEM_PARADA -> "Sem parada"
    }
