package app.hunter.app.fuel

import app.hunter.app.data.anp.AnpStationEntity
import app.hunter.nav.Place
import java.text.Normalizer

/**
 * Casa um posto do mapa (OpenStreetMap) com o posto da ANP que tem o preço dele.
 *
 * A ANP não informa a posição do posto, só rua, número, bairro e CEP; o mapa informa a posição e, muitas vezes, a
 * rua e o número. Então a ligação é pelo endereço, dentro da mesma cidade. Sem endereço que bata, não há preço:
 * o posto fica de fora em vez de receber a média.
 */
object AnpMatcher {
    private val streetTypes = setOf(
        "avenida", "av", "rua", "r", "rodovia", "rod", "estrada", "est", "travessa", "tv", "praca", "pca", "alameda",
        "al", "via", "largo", "beco", "viela", "caminho", "marginal", "governador", "gov", "prefeito", "doutor", "dr",
        "professor", "prof", "presidente", "pres", "engenheiro", "eng", "general", "gen", "coronel", "cel",
    )

    /** Letras sem acento e sem vogais: sobrevive a acento perdido ("AM�RICO" x "Américo") e a pequenas variações. */
    internal fun streetKey(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val ascii = Normalizer.normalize(raw, Normalizer.Form.NFD).lowercase()
            .replace(Regex("[\u0300-\u036f]"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
        val words = ascii.split(' ').filter { it.isNotBlank() && it !in streetTypes }
        return words.joinToString("") { it.replace(Regex("[aeiou]"), "") }
    }

    /** Número da porta: só dígitos; "S/N" e vazio viram null. */
    internal fun number(raw: String?): String? {
        val d = raw?.filter { it.isDigit() }?.trimStart('0').orEmpty()
        return d.ifEmpty { null }
    }

    private fun streetOfAnp(endereco: String): String = endereco.substringBefore(',')
    private fun numberOfAnp(endereco: String): String? = number(endereco.substringAfter(',', ""))
    private fun digits(s: String?) = s?.filter { it.isDigit() }.orEmpty()

    private fun brandMatches(anpBrand: String, osmName: String): Boolean {
        val n = osmName.lowercase()
        return when (anpBrand.uppercase()) {
            "VIBRA", "PETROBRAS" -> "br" in n.split(' ', '-', '/') || "petrobras" in n || "vibra" in n
            "RAIZEN" -> "shell" in n || "texaco" in n || "raizen" in n || "raízen" in n
            "IPIRANGA" -> "ipiranga" in n
            "ALE" -> n.split(' ', '-').contains("ale")
            else -> false
        }
    }

    /** O posto da ANP que corresponde a [place] entre [candidates] (todos da mesma cidade), ou null. */
    fun match(place: Place, candidates: List<AnpStationEntity>): AnpStationEntity? {
        val key = streetKey(place.street ?: place.subtitle?.substringBefore(',')?.substringBefore(" · "))
        if (key.length < 3) return null
        val num = number(place.houseNumber)
        val cep = digits(place.postcode).takeIf { it.length == 8 }
        val sameStreet = candidates.filter { streetKey(streetOfAnp(it.endereco)) == key }
        if (sameStreet.isEmpty()) return null

        // 1. rua + número da porta
        if (num != null) sameStreet.singleOrNull { numberOfAnp(it.endereco) == num }?.let { return it }
        // 2. rua + CEP
        if (cep != null) sameStreet.filter { digits(it.cep) == cep }.let { if (it.size == 1) return it.first() }
        // 3. rua + bandeira, só quando é única na rua (sem número no mapa)
        sameStreet.filter { brandMatches(it.bandeira, place.name) }.let { if (it.size == 1 && num == null) return it.first() }
        // 4. a rua só tem um posto cadastrado
        if (num == null && sameStreet.size == 1) return sameStreet.first()
        return null
    }
}
