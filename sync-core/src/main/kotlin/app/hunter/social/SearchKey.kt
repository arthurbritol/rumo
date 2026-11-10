package app.hunter.social

import java.text.Normalizer
import java.util.Locale

/**
 * Chave de busca por nome: a mesma função monta o campo `displayNameLower` gravado e o prefixo consultado.
 * Minúsculas (`Locale.ROOT`), sem acentos (NFD sem marcas combinantes), aparada e com espaços múltiplos
 * reduzidos a um; no máximo [MAX_LENGTH] caracteres (limite das regras). "João  Silva" vira "joao silva".
 */
object SearchKey {
    const val MAX_LENGTH = 60

    private val marks = Regex("""\p{Mn}+""")
    private val spaces = Regex("""\s+""")

    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        val key = marks.replace(decomposed, "").trim().replace(spaces, " ")
        if (key.length <= MAX_LENGTH) return key
        val cut = if (Character.isHighSurrogate(key[MAX_LENGTH - 1])) MAX_LENGTH - 1 else MAX_LENGTH
        return key.substring(0, cut).trim()
    }
}
