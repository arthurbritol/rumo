package app.hunter.nav

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Uma versão do app publicada no GitHub, mais nova que a instalada. */
data class AppUpdate(
    val tag: String,
    val versionName: String,
    /** `versionCode` do APK: maior.menor.patch vira maior*10000 + menor*100 + patch (0.3.1 → 301). */
    val versionCode: Int,
    /** Texto da release (lista das alterações), em Markdown simples. */
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

/**
 * Consulta a última release do repositório no GitHub (API pública, sem chave) e diz se há versão mais nova que a
 * instalada. As releases são publicadas pelo CI com tag `v<maior>.<menor>.<patch>` (0.3.1, 0.3.2…) e
 * `versionCode` calculado dessa tag, então a comparação usa só números. As primeiras tinham tag `v0.3.0-b<n>`.
 */
class UpdateChecker(
    private val http: Http = UrlConnectionHttp(readTimeoutMs = 10_000, attempts = 2),
    private val repo: String = "arthurbritol/rumo",
) {
    /** A release mais recente se for mais nova que [installedCode]; `null` se já está atualizado. */
    suspend fun check(installedCode: Int): AppUpdate? =
        parse(http.get("https://api.github.com/repos/$repo/releases/latest"))?.takeIf { it.versionCode > installedCode }

    companion object {
        private const val LEGACY_CODE_BASE = 100
        private val json = Json { ignoreUnknownKeys = true }
        private val tagRegex = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""")
        private val legacyTagRegex = Regex("""^v?(.+)-b(\d+)$""")

        /** Nome e `versionCode` de uma tag de release; `null` se não segue nenhum dos dois formatos. */
        internal fun parseTag(tag: String): Pair<String, Int>? {
            tagRegex.matchEntire(tag)?.let { m ->
                val (a, b, c) = m.destructured
                val code = a.toIntOrNull()?.let { x -> b.toIntOrNull()?.let { y -> c.toIntOrNull()?.let { z -> x * 10_000 + y * 100 + z } } }
                return code?.let { "$a.$b.$c" to it }
            }
            legacyTagRegex.matchEntire(tag)?.let { m ->
                val build = m.groupValues[2].toIntOrNull() ?: return null
                return m.groupValues[1] to LEGACY_CODE_BASE + build
            }
            return null
        }

        /** Interpreta a resposta de `releases/latest`; `null` se for rascunho, pré-lançamento, sem APK ou fora do padrão. */
        fun parse(text: String): AppUpdate? {
            val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            if (o["draft"]?.jsonPrimitive?.contentOrNull == "true" || o["prerelease"]?.jsonPrimitive?.contentOrNull == "true") return null
            val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
            val (name, code) = parseTag(tag) ?: return null
            val apk = (o["assets"] as? JsonArray)?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk", ignoreCase = true) == true }
                ?: return null
            val url = apk["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null
            return AppUpdate(
                tag = tag,
                versionName = name,
                versionCode = code,
                notes = o["body"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
                apkUrl = url,
                sizeBytes = apk["size"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }
    }
}
