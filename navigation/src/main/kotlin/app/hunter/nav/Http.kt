package app.hunter.nav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Cliente HTTP mínimo, trocável nos testes. */
interface Http {
    suspend fun get(url: String): String
    suspend fun postJson(url: String, body: String): String
}

class HttpFailure(val code: Int, message: String) : IOException(message)

/**
 * [HttpURLConnection] com User-Agent identificado (exigido pelo Nominatim/Photon). Conexão falha rápido (8 s) e a
 * leitura espera mais ([readTimeoutMs]: o Valhalla público às vezes demora). Até [attempts] tentativas com pausa
 * crescente em timeout, queda de rede e 5xx/429; 4xx (exceto 429) falha na hora. O atraso é cooperativo
 * (cancelar a busca cancela a espera).
 */
class UrlConnectionHttp(
    private val userAgent: String = "HunterCompanion/0.3 (app pessoal para moto)",
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 25_000,
    private val attempts: Int = 3,
) : Http {

    override suspend fun get(url: String) = request(url, null)
    override suspend fun postJson(url: String, body: String) = request(url, body)

    private suspend fun request(url: String, body: String?): String {
        var last: IOException? = null
        repeat(attempts) { attempt ->
            try {
                return withContext(Dispatchers.IO) { once(url, body) }
            } catch (e: HttpFailure) {
                last = e
                if (e.code < 500 && e.code != 429) throw e
            } catch (e: IOException) {
                last = e
            }
            if (attempt < attempts - 1) delay(500L * (attempt + 1))
        }
        throw last ?: IOException("falha de rede")
    }

    private fun once(url: String, body: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        var ok = false
        try {
            c.connectTimeout = connectTimeoutMs
            c.readTimeout = readTimeoutMs
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw HttpFailure(code, "HTTP $code: ${text.take(200)}")
            ok = true
            return text
        } finally {
            // Em sucesso a conexão (TLS já negociado) volta ao pool e a próxima busca a reaproveita: bem mais rápido
            // ao digitar. Só derruba a conexão se deu erro.
            if (!ok) c.disconnect()
        }
    }
}
