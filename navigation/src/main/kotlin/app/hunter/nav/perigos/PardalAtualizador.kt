package app.hunter.nav.perigos

import app.hunter.nav.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** Política de rede: dados móveis só com confirmação do usuário. A implementação Android fica no módulo `app`. */
interface RedePolitica {
    fun emWifi(): Boolean
}

/** Resultado de [PardalAtualizador.atualizar]. */
sealed interface ResultadoPardal {
    /** Baixou, validou e gravou. */
    data class Atualizada(val uf: String, val pontos: Int) : ResultadoPardal
    /** O que está guardado já é o do catálogo: nada baixado. */
    data class JaEmDia(val uf: String) : ResultadoPardal
    /** Nada mudou; [motivo] em texto. */
    data class Falhou(val motivo: String) : ResultadoPardal
    /** Fora do Wi-Fi sem permissão: não baixou. [bytes] = tamanho aproximado do zip, se o servidor informou. */
    data class PrecisaConfirmarDadosMoveis(val bytes: Long?) : ResultadoPardal
}

/** Um arquivo de origem de uma UF no catálogo. */
data class PardalArquivo(val nome: String, val sha256: String, val count: Int)

/** Uma UF do catálogo. [bbox] = [latMin, lonMin, latMax, lonMax]. */
data class PardalUfCatalogo(val uf: String, val bbox: List<Double>, val artifactBuiltAt: String, val arquivos: List<PardalArquivo>) {
    val area get() = (bbox[2] - bbox[0]) * (bbox[3] - bbox[1])
    fun contem(lat: Double, lon: Double) = lat in bbox[0]..bbox[2] && lon in bbox[1]..bbox[3]
}

class PardalCatalogo(val schema: Int, val builtAt: String, val ufs: Map<String, PardalUfCatalogo>)

/**
 * Catálogo + baixador do Pardal (github.com/murkiriel/pardal). Sem Android.
 *
 * - Só atualiza com `schema` == [SCHEMA]; outro valor mantém o dado antigo (resultado [ResultadoPardal.Falhou] com o motivo).
 * - [atualizar]: uma UF por vez (trava). Compara `artifact_built_at` e sha256 do catálogo com o `.meta`; igual = não baixa o zip.
 *   Baixa em fluxo, lê só radares/lombadas/pontos_atencao (o resto é lido e jogado fora, sem gravar), aborta passando de
 *   [limiteBytes] lidos (descomprimidos), confere o sha256 dos CSVs, converte e grava pelo [PardalArmazenamento].
 *   Qualquer falha deixa o dado antigo intacto e liga o recuo exponencial (1 min dobrando até 30 min, como no PerigoRepositorio).
 */
class PardalAtualizador(
    private val armazenamento: PardalArmazenamento,
    private val rede: RedePolitica,
    private val client: OkHttpClient = clientePadrao(),
    private val userAgent: String = USER_AGENT,
    private val baseUrl: String = BASE_URL,
    private val limiteBytes: Long = LIMITE_BYTES,
    private val agora: () -> Long = System::currentTimeMillis,
    /** Chamado depois de gravar (ou renovar) uma UF; o app usa para invalidar o índice do [PardalSource]. */
    private val aoAtualizar: (String) -> Unit = {},
) {
    private val trava = Mutex()
    @Volatile private var catalogo: PardalCatalogo? = null
    @Volatile private var falhasSeguidas = 0
    @Volatile private var bloqueadoAte = 0L

    /** Instante (ms) até o qual novas tentativas ficam em recuo (0 = livre). */
    fun bloqueadoAte(): Long = bloqueadoAte

    // ---------- Escolha de UF ----------

    private fun bboxes(): List<PardalUfCatalogo> =
        catalogo?.ufs?.values?.toList() ?: armazenamento.ufsBaixadas().mapNotNull { uf ->
            armazenamento.lerMeta(uf)?.takeIf { it.bbox.size == 4 }?.let { PardalUfCatalogo(uf, it.bbox, it.artifactBuiltAt, emptyList()) }
        }

    /** UF cujo bbox contém o ponto (a de menor área se houver mais de uma); null se fora de todos. Usa o último catálogo baixado, ou os `.meta` guardados. */
    fun ufsPara(lat: Double, lon: Double): String? =
        bboxes().filter { it.contem(lat, lon) }.minByOrNull { it.area }?.uf

    /** UFs dos pontos da rota amostrados a cada ~[passoM] m (e o último), na ordem em que aparecem, sem repetir. */
    fun ufsParaRota(rota: Route, passoM: Double = 2_000.0): List<String> {
        val cum = rota.cumulativeM
        if (rota.shape.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        var proximo = 0.0
        for (i in rota.shape.indices) {
            if (cum[i] >= proximo) {
                ufsPara(rota.shape[i].lat, rota.shape[i].lon)?.let { out += it }
                proximo = cum[i] + passoM
            }
        }
        rota.shape.last().let { p -> ufsPara(p.lat, p.lon)?.let { out += it } }
        return out.toList()
    }

    /** O catálogo já foi baixado nesta execução (só então [ufsPara] conhece UFs que ainda não têm `.meta`). */
    fun catalogoCarregado(): Boolean = catalogo != null

    /** Siglas das UFs do catálogo carregado, em ordem alfabética (vazio se o catálogo ainda não foi baixado). */
    fun ufsDoCatalogo(): List<String> = catalogo?.ufs?.keys?.sorted().orEmpty()

    /** Baixa só o catálogo (pequeno), sem zip nenhum: permite descobrir a UF antes do primeiro download. false = indisponível ou schema não suportado. */
    suspend fun carregarCatalogo(): Boolean = trava.withLock {
        try {
            val cat = withContext(Dispatchers.IO) { baixarCatalogo() }
            if (cat.schema != SCHEMA) return@withLock false
            catalogo = cat
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    // ---------- Atualização ----------

    suspend fun atualizar(uf: String, permitirDadosMoveis: Boolean): ResultadoPardal = trava.withLock {
        val t = agora()
        if (t < bloqueadoAte) return@withLock ResultadoPardal.Falhou("em recuo por falha anterior, nova tentativa em ${(bloqueadoAte - t) / 1000}s")
        try {
            val r = withContext(Dispatchers.IO) { atualizarBloqueando(uf, permitirDadosMoveis) }
            if (r is ResultadoPardal.Falhou) falhar() else falhasSeguidas = 0
            r
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            falhar()
            ResultadoPardal.Falhou(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun falhar() {
        falhasSeguidas++
        bloqueadoAte = agora() + recuoMs(falhasSeguidas)
    }

    private class FalhaPardal(message: String) : Exception(message)

    private fun atualizarBloqueando(uf: String, permitirDadosMoveis: Boolean): ResultadoPardal {
        val cat = try { baixarCatalogo() } catch (e: IOException) { return ResultadoPardal.Falhou("catálogo indisponível: ${e.message}") }
        catalogo = cat
        if (cat.schema != SCHEMA) return ResultadoPardal.Falhou("schema ${cat.schema} do catálogo não é suportado (esperado $SCHEMA): dado antigo mantido")
        val info = cat.ufs[uf] ?: return ResultadoPardal.Falhou("UF $uf não está no catálogo")
        if (info.bbox.size != 4) return ResultadoPardal.Falhou("UF $uf sem bbox no catálogo")

        val meta = armazenamento.lerMeta(uf)
        val shaCat = info.arquivos.associate { it.nome to it.sha256 }
        if (meta != null && meta.artifactBuiltAt == info.artifactBuiltAt && meta.sha256 == shaCat && armazenamento.ler(uf) != null) {
            armazenamento.renovar(uf, agora())
            aoAtualizar(uf)
            return ResultadoPardal.JaEmDia(uf)
        }

        if (!permitirDadosMoveis && !rede.emWifi()) return ResultadoPardal.PrecisaConfirmarDadosMoveis(tamanhoDoZip(uf))

        val csvs = baixarZip(uf, info)
        val pontos = LinkedHashMap<String, PerigoPonto>()
        val contagens = HashMap<String, Int>()
        for (a in info.arquivos) {
            val texto = csvs[a.nome]!!.toString(Charsets.UTF_8)
            val lista = when (a.nome) {
                ARQ_RADARES -> PardalCsv.radares(texto)
                ARQ_LOMBADAS -> PardalCsv.lombadas(texto)
                else -> PardalCsv.pontosAtencao(texto)
            }
            contagens[a.nome] = lista.size
            for (p in lista) pontos.putIfAbsent(p.id, p)
        }
        armazenamento.gravar(PardalMeta(uf, info.artifactBuiltAt, shaCat, agora(), contagens, info.bbox), pontos.values.toList())
        aoAtualizar(uf)
        return ResultadoPardal.Atualizada(uf, pontos.size)
    }

    private fun requisicao(url: String) = Request.Builder().url(url).header("User-Agent", userAgent)

    private fun baixarCatalogo(): PardalCatalogo {
        client.newCall(requisicao("$baseUrl/catalogo.json").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val texto = r.body.string()
            try {
                return parseCatalogo(texto)
            } catch (e: Exception) {
                throw IOException("catálogo ilegível", e)
            }
        }
    }

    private fun tamanhoDoZip(uf: String): Long? = try {
        client.newCall(requisicao("$baseUrl/pardal-$uf.zip").head().build()).execute().use { r ->
            if (r.isSuccessful) r.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 } else null
        }
    } catch (e: IOException) { null }

    /** Lê o zip em fluxo. Devolve nome -> bytes só dos arquivos esperados pelo catálogo, já com o sha256 conferido. */
    private fun baixarZip(uf: String, info: PardalUfCatalogo): Map<String, ByteArray> {
        val esperados = info.arquivos.associateBy { it.nome }
        val achados = HashMap<String, ByteArray>()
        client.newCall(requisicao("$baseUrl/pardal-$uf.zip").build()).execute().use { r ->
            if (!r.isSuccessful) throw FalhaPardal("zip de $uf: HTTP ${r.code}")
            var lidos = 0L
            ZipInputStream(r.body.byteStream()).use { zip ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val nome = e.name.substringAfterLast('/')
                    val guardar = nome in esperados && nome !in achados
                    val saida = if (guardar) ByteArrayOutputStream() else null
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        lidos += n
                        if (lidos > limiteBytes) throw FalhaPardal("zip de $uf passou do limite de $limiteBytes bytes: abortado")
                        saida?.write(buf, 0, n)
                    }
                    if (saida != null) achados[nome] = saida.toByteArray()
                }
            }
        }
        for ((nome, a) in esperados) {
            val bytes = achados[nome] ?: throw FalhaPardal("zip de $uf sem $nome")
            if (sha256(bytes) != a.sha256.lowercase()) throw FalhaPardal("sha256 de $nome não confere com o catálogo")
        }
        return achados
    }

    companion object {
        const val SCHEMA = 2
        const val BASE_URL = "https://github.com/murkiriel/pardal/releases/latest/download"
        const val LIMITE_BYTES = 150L * 1024 * 1024
        const val USER_AGENT = "Rumo/1.0 (app de navegacao para motos; https://github.com/arthurbritol/rumo)"
        const val ARQ_RADARES = "radares.csv"
        const val ARQ_LOMBADAS = "lombadas.csv"
        const val ARQ_ATENCAO = "pontos_atencao.csv"

        /** Redirecionamentos são seguidos (padrão do OkHttp); GitHub redireciona os downloads de release. */
        fun clientePadrao(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        private val json = Json { ignoreUnknownKeys = true }

        private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        /** Bloco do catálogo -> arquivo no zip: cameras = radares, bumps = lombadas, hazards = pontos de atenção. */
        private val BLOCOS = listOf("cameras" to ARQ_RADARES, "bumps" to ARQ_LOMBADAS, "hazards" to ARQ_ATENCAO)

        fun parseCatalogo(texto: String): PardalCatalogo {
            val raiz = json.parseToJsonElement(texto).jsonObject
            val schema = raiz["schema"]?.jsonPrimitive?.intOrNull ?: error("sem schema")
            val builtAt = raiz["built_at"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val ufs = LinkedHashMap<String, PardalUfCatalogo>()
            // Com outro schema o formato das UFs pode ser diferente: não tenta interpretar.
            if (schema == SCHEMA) for ((sigla, v) in raiz["ufs"]?.jsonObject.orEmpty()) {
                val o = v as? JsonObject ?: continue
                val bbox = (o["bbox"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.doubleOrNull }?.takeIf { it.size == 4 } ?: continue
                val artefato = o["artifact_built_at"]?.jsonPrimitive?.contentOrNull ?: continue
                val arquivos = BLOCOS.mapNotNull { (bloco, nome) ->
                    val b = o[bloco] as? JsonObject ?: return@mapNotNull null
                    val sha = b["sha256"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    PardalArquivo(nome, sha, b["count"]?.jsonPrimitive?.intOrNull ?: 0)
                }
                ufs[sigla] = PardalUfCatalogo(sigla, bbox, artefato, arquivos)
            }
            return PardalCatalogo(schema, builtAt, ufs)
        }
    }
}
