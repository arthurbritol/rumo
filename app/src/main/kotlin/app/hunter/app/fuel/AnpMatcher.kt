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

    /** Palavras que não identificam um posto (tipo de negócio, empresa, bandeira): ficam fora do casamento por nome. */
    private val genericWords = setOf(
        "posto", "postos", "auto", "combustivel", "combustiveis", "revenda", "rede", "ltda", "eireli", "epp", "sa",
        "comercio", "derivados", "petroleo", "abastecimento", "servico", "servicos", "shell", "ipiranga", "ale",
        "petrobras", "vibra", "raizen", "mania", "atlantic", "distribuidora", "gas", "gasolina",
    )

    /** Palavras que identificam o posto pelo nome, já no formato de [streetKey]-like (sem vogais). */
    internal fun nameKeys(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val ascii = Normalizer.normalize(raw, Normalizer.Form.NFD).lowercase()
            .replace(Regex("[̀-ͯ]"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
        return ascii.split(' ')
            .filter { it.length >= 3 && it !in genericWords }
            .map { it.replace(Regex("[aeiou]"), "") }
            .filter { it.isNotEmpty() }
    }

    /** Posto da ANP ligado a um posto do mapa, com a força da ligação (para desempatar dois postos do mapa no mesmo). */
    data class Match(val station: AnpStationEntity, val score: Int)

    fun match(place: Place, candidates: List<AnpStationEntity>): AnpStationEntity? = matchScored(place, candidates)?.station

    /**
     * O posto da ANP que corresponde a [place] entre [candidates] (todos da mesma cidade), ou null. Cada regra exige
     * UM único candidato: na dúvida, não casa (melhor sem marcador do que com o preço de outro posto).
     */
    fun matchScored(place: Place, candidates: List<AnpStationEntity>): Match? {
        val key = streetKey(place.street ?: place.subtitle?.substringBefore(',')?.substringBefore(" · "))
        val num = number(place.houseNumber)
        val cep = digits(place.postcode).takeIf { it.length == 8 }
        val sameStreet = if (key.length >= 3) candidates.filter { streetKey(streetOfAnp(it.endereco)) == key } else emptyList()

        // 1. rua + número da porta
        if (num != null) sameStreet.singleOrNull { numberOfAnp(it.endereco) == num }?.let { return Match(it, 4) }

        // 2. nome do posto: todas as palavras que o identificam ("Coliseu", "Arara Azul") estão no nome da ANP
        val wanted = nameKeys(place.name)
        if (wanted.isNotEmpty()) {
            candidates.filter { c -> nameKeys(c.nome).containsAll(wanted) }.singleOrNull()?.let { return Match(it, 3) }
        }

        // 3. rua + CEP
        if (cep != null) sameStreet.filter { digits(it.cep) == cep }.singleOrNull()?.let { return Match(it, 2) }

        val osmBrand = FuelPriceRepository.detectBrand(place.name)
        // 4. rua + bandeira, só quando o nome do posto traz a bandeira e é única na rua (sem número no mapa)
        if (num == null && osmBrand != "Branca") {
            sameStreet.filter { brandMatches(it.bandeira, place.name) }.singleOrNull()?.let { return Match(it, 1) }
        }
        // 5. CEP único na cidade + bandeira compatível (a rua pode ter mudado de nome no mapa)
        if (cep != null && osmBrand != "Branca") {
            candidates.filter { digits(it.cep) == cep && brandMatches(it.bandeira, place.name) }.singleOrNull()
                ?.let { return Match(it, 1) }
        }
        return null
    }
}
