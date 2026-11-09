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
    /** Número da build (também o sufixo da tag): o `versionCode` do APK é [BUILD_CODE_BASE] + build. */
    val build: Int,
    val versionCode: Int,
    /** Texto da release (lista das alterações), em Markdown simples. */
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

/**
 * Consulta a última release do repositório no GitHub (API pública, sem chave) e diz se há versão mais nova que a
 * instalada. As releases são publicadas pelo CI com tag `v<versionName>-b<run>` e `versionCode = 100 + run`,
 * então a comparação usa só números.
 */
class UpdateChecker(
    private val http: Http = UrlConnectionHttp(readTimeoutMs = 10_000, attempts = 2),
    private val repo: String = "arthurbritol/rumo",
) {
    /** A release mais recente se for mais nova que [installedCode]; `null` se já está atualizado. */
    suspend fun check(installedCode: Int): AppUpdate? =
        parse(http.get("https://api.github.com/repos/$repo/releases/latest"))?.takeIf { it.versionCode > installedCode }

    companion object {
        const val BUILD_CODE_BASE = 100
        private val json = Json { ignoreUnknownKeys = true }
        private val tagRegex = Regex("""^v?(.+)-b(\d+)$""")

        /** Interpreta a resposta de `releases/latest`; `null` se for rascunho, pré-lançamento, sem APK ou fora do padrão. */
        fun parse(text: String): AppUpdate? {
            val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            if (o["draft"]?.jsonPrimitive?.contentOrNull == "true" || o["prerelease"]?.jsonPrimitive?.contentOrNull == "true") return null
            val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
            val m = tagRegex.matchEntire(tag) ?: return null
            val build = m.groupValues[2].toIntOrNull() ?: return null
            val apk = (o["assets"] as? JsonArray)?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk", ignoreCase = true) == true }
                ?: return null
            val url = apk["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null
            return AppUpdate(
                tag = tag,
                versionName = m.groupValues[1],
                build = build,
                versionCode = BUILD_CODE_BASE + build,
                notes = o["body"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
                apkUrl = url,
                sizeBytes = apk["size"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }
    }
}
