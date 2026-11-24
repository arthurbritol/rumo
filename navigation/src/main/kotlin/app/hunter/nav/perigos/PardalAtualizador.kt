package app.hunter.nav.perigos

import app.hunter.nav.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
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
    /** Fora do Wi-Fi sem permissão: não baixou nada. [bytes] = tamanho do último zip baixado desta UF nesta execução, se houve. */
    data class PrecisaConfirmarDadosMoveis(val bytes: Long?) : ResultadoPardal
}

/** Um arquivo de origem de uma UF no catálogo. */
data class PardalArquivo(val nome: String, val sha256: String, val count: Int)

/** Uma UF do catálogo. [bbox] = [latMin, lonMin, latMax, lonMax]. */
data class PardalUfCatalogo(val uf: String, val bbox: List<Double>, val artifactBuiltAt: String, val arquivos: List<PardalArquivo>) {
    fun contem(lat: Double, lon: Double) = lat in bbox[0]..bbox[2] && lon in bbox[1]..bbox[3]
}

class PardalCatalogo(val schema: Int, val builtAt: String, val ufs: Map<String, PardalUfCatalogo>)

/** Conta os bytes que passam (aqui: os COMPRIMIDOS lidos da rede, antes de o zip descomprimir). */
private class ContadorDeBytes(entrada: InputStream) : FilterInputStream(entrada) {
    @Volatile var lidos = 0L
        private set
    override fun read(): Int = super.read().also { if (it >= 0) lidos++ }
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) lidos += it }
}

/**
 * Catálogo + baixador do Pardal (github.com/murkiriel/pardal). Sem Android.
 *
 * - Só atualiza com `schema` == [SCHEMA]; outro valor mantém o dado antigo (resultado [ResultadoPardal.Falhou] com o motivo).
 * - Os bbox de TODAS as UFs do catálogo são gravados em disco a cada catálogo carregado ([PardalArmazenamento.gravarBboxes]),
 *   para o app saber offline quais UFs tocam um ponto/quadrado ([ufsPara] devolve todas, não só uma).
 * - [atualizar]: uma UF por vez (trava). Fora do Wi-Fi sem permissão NÃO toca a rede (nem catálogo nem HEAD): devolve
 *   [ResultadoPardal.PrecisaConfirmarDadosMoveis] (com o tamanho do último download desta UF nesta execução, se houve). Compara
 *   `artifact_built_at` e sha256 do catálogo com o `.meta`; igual = não baixa o zip. Exige o bloco de radares no catálogo.
 *   Baixa em fluxo, lê só radares/lombadas/pontos_atencao (o resto é lido e jogado fora, sem gravar), aborta passando de
 *   [limiteBytes] COMPRIMIDOS lidos da rede ou de [limiteEntradaBytes] descomprimidos numa entrada guardada, confere o sha256
 *   dos CSVs (calculado durante a leitura), converte e grava pelo [PardalArmazenamento].
 * - Rede: redirecionamentos seguidos à mão, só https e só hosts do GitHub ([redirecionamentoPermitido]); tempo total por chamada
 *   ([callTimeoutZipS], [callTimeoutCatalogoS]); catálogo limitado a [LIMITE_CATALOGO_BYTES]; cancelar a coroutine cancela a chamada.
 * - Falha de UM download liga o recuo exponencial só daquela UF (1 min dobrando até 30 min, como no PerigoRepositorio).
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
    private val limiteEntradaBytes: Long = LIMITE_ENTRADA_BYTES,
    private val callTimeoutZipS: Long = CALL_TIMEOUT_ZIP_S,
    private val callTimeoutCatalogoS: Long = CALL_TIMEOUT_CATALOGO_S,
    /** Destino de um redirecionamento é aceito? (a primeira URL é sempre a [baseUrl]). */
    private val redirecionamentoPermitido: (HttpUrl) -> Boolean = Companion::redirecionamentoPermitido,
) {
    private val trava = Mutex()
    private val travaCatalogo = Mutex()
    @Volatile private var catalogo: PardalCatalogo? = null
    private val falhasSeguidas = ConcurrentHashMap<String, Int>()
    private val bloqueios = ConcurrentHashMap<String, Long>()
    private val tamanhosZip = ConcurrentHashMap<String, Long>()
    private val chamadasAtivas: MutableSet<Call> = ConcurrentHashMap.newKeySet()

    /** Instante (ms) até o qual novas tentativas da UF [uf] ficam em recuo (0 = livre). */
    fun bloqueadoAte(uf: String): Long = bloqueios[uf] ?: 0L

    // ---------- Escolha de UF ----------

    /** bbox de todas as UFs: do último catálogo carregado, ou o que ficou em disco dele. */
    private fun bboxes(): List<PardalUfCatalogo> =
        catalogo?.ufs?.values?.toList()
            ?: armazenamento.lerBboxes().map { (uf, b) -> PardalUfCatalogo(uf, b, "", emptyList()) }

    /**
     * TODAS as UFs cujo bbox contém o ponto, em ordem alfabética (o bbox é grosseiro: o de SP cobre o sul de MG, o de GO contém o DF).
     * Vazia se fora de todos ou se o catálogo nunca foi carregado.
     */
    fun ufsPara(lat: Double, lon: Double): List<String> =
        bboxes().filter { it.contem(lat, lon) }.map { it.uf }.sorted()

    /** UFs dos pontos da rota amostrados a cada ~[passoM] m (e o último), na ordem em que aparecem, sem repetir. */
    fun ufsParaRota(rota: Route, passoM: Double = 2_000.0): List<String> {
        val cum = rota.cumulativeM
        if (rota.shape.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        var proximo = 0.0
        for (i in rota.shape.indices) {
            if (cum[i] >= proximo) {
                out += ufsPara(rota.shape[i].lat, rota.shape[i].lon)
                proximo = cum[i] + passoM
            }
        }
        rota.shape.last().let { p -> out += ufsPara(p.lat, p.lon) }
        return out.toList()
    }

    /** O catálogo já foi baixado nesta execução. */
    fun catalogoCarregado(): Boolean = catalogo != null

    /** Há bbox conhecidos (catálogo carregado agora ou em execução anterior). */
    fun bboxesConhecidos(): Boolean = catalogo != null || armazenamento.lerBboxes().isNotEmpty()

    /** Siglas das UFs do catálogo carregado, em ordem alfabética (vazio se o catálogo ainda não foi baixado). */
    fun ufsDoCatalogo(): List<String> = catalogo?.ufs?.keys?.sorted().orEmpty()

    /**
     * Baixa só o catálogo (pequeno), sem zip nenhum, e grava os bbox em disco. Usa trava própria: não espera um download de zip em
     * andamento. false = indisponível ou schema não suportado.
     */
    suspend fun carregarCatalogo(): Boolean = travaCatalogo.withLock {
        try {
            val cat = cancelavel { baixarCatalogo() }
            if (cat.schema != SCHEMA) return@withLock false
            aceitarCatalogo(cat)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private fun aceitarCatalogo(cat: PardalCatalogo) {
        catalogo = cat
        try { armazenamento.gravarBboxes(cat.ufs.mapValues { it.value.bbox }) } catch (e: Exception) { /* sem disco: segue só em memória */ }
    }

    // ---------- Atualização ----------

    suspend fun atualizar(uf: String, permitirDadosMoveis: Boolean): ResultadoPardal = trava.withLock {
        val t = agora()
        val bloqueio = bloqueadoAte(uf)
        if (t < bloqueio) return@withLock ResultadoPardal.Falhou("em recuo por falha anterior, nova tentativa em ${(bloqueio - t) / 1000}s")
        try {
            val r = cancelavel { atualizarBloqueando(uf, permitirDadosMoveis) }
            if (r is ResultadoPardal.Falhou) falhar(uf) else { falhasSeguidas.remove(uf); bloqueios.remove(uf) }
            r
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            falhar(uf)
            ResultadoPardal.Falhou(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Roda [bloco] (bloqueante) em IO; se a coroutine for cancelada, cancela também as chamadas OkHttp em andamento. */
    private suspend fun <T> cancelavel(bloco: () -> T): T = withContext(Dispatchers.IO) {
        val trabalho = async { runCatching(bloco) }
        try {
            trabalho.await().getOrThrow()
        } catch (e: CancellationException) {
            chamadasAtivas.forEach { it.cancel() }
            throw e
        }
    }

    private fun falhar(uf: String) {
        val n = (falhasSeguidas[uf] ?: 0) + 1
        falhasSeguidas[uf] = n
        bloqueios[uf] = agora() + recuoMs(n)
    }

    private class FalhaPardal(message: String) : Exception(message)

    private fun atualizarBloqueando(uf: String, permitirDadosMoveis: Boolean): ResultadoPardal {
        // Sem Wi-Fi e sem permissão: nem catálogo nem HEAD. Quem chamou pergunta ao usuário.
        if (!permitirDadosMoveis && !rede.emWifi()) return ResultadoPardal.PrecisaConfirmarDadosMoveis(tamanhosZip[uf])
        val cat = try { baixarCatalogo() } catch (e: IOException) { return ResultadoPardal.Falhou("catálogo indisponível: ${e.message}") }
        if (cat.schema != SCHEMA) {
            return ResultadoPardal.Falhou("schema ${cat.schema} do catálogo não é suportado (esperado $SCHEMA): dado antigo mantido")
        }
        aceitarCatalogo(cat)
        val info = cat.ufs[uf] ?: return ResultadoPardal.Falhou("UF $uf não está no catálogo")
        if (info.bbox.size != 4) return ResultadoPardal.Falhou("UF $uf sem bbox no catálogo")
        if (info.arquivos.none { it.nome == ARQ_RADARES }) return ResultadoPardal.Falhou("catálogo de $uf sem o bloco de radares: nada gravado")

        val meta = armazenamento.lerMeta(uf)
        val shaCat = info.arquivos.associate { it.nome to it.sha256 }
        if (meta != null && meta.artifactBuiltAt == info.artifactBuiltAt && meta.sha256 == shaCat && armazenamento.ler(uf) != null) {
            armazenamento.renovar(uf, agora())
            aoAtualizar(uf)
            return ResultadoPardal.JaEmDia(uf)
        }

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

    /**
     * Executa [req] seguindo os redirecionamentos à mão (só destinos que [redirecionamentoPermitido] aceita), com tempo total de
     * [timeoutS] s por chamada. A chamada fica em [chamadasAtivas] para poder ser cancelada junto com a coroutine.
     */
    private fun <T> executar(req: Request, timeoutS: Long, bloco: (Response) -> T): T {
        val cli = client.newBuilder().followRedirects(false).followSslRedirects(false).callTimeout(timeoutS, TimeUnit.SECONDS).build()
        var atual = req
        repeat(MAX_REDIRECIONAMENTOS + 1) {
            val call = cli.newCall(atual)
            chamadasAtivas += call
            try {
                val r = call.execute()
                if (!r.isRedirect) return r.use(bloco)
                val destino = r.header("Location")?.let { atual.url.resolve(it) }
                r.close()
                if (destino == null) throw IOException("redirecionamento sem destino")
                if (!redirecionamentoPermitido(destino)) throw IOException("redirecionamento para ${destino.host} não permitido")
                atual = atual.newBuilder().url(destino).build()
            } finally {
                chamadasAtivas -= call
            }
        }
        throw IOException("redirecionamentos demais")
    }

    private fun baixarCatalogo(): PardalCatalogo =
        executar(requisicao("$baseUrl/catalogo.json").build(), callTimeoutCatalogoS) { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val tamanho = r.body.contentLength()
            if (tamanho > LIMITE_CATALOGO_BYTES) throw IOException("catálogo grande demais ($tamanho bytes)")
            val out = ByteArrayOutputStream()
            r.body.byteStream().use { entrada ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = entrada.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > LIMITE_CATALOGO_BYTES) throw IOException("catálogo grande demais (passou de $LIMITE_CATALOGO_BYTES bytes)")
                }
            }
            try {
                parseCatalogo(out.toString(Charsets.UTF_8))
            } catch (e: Exception) {
                throw IOException("catálogo ilegível", e)
            }
        }

    /** Lê o zip em fluxo. Devolve nome -> bytes só dos arquivos esperados pelo catálogo, já com o sha256 conferido. */
    private fun baixarZip(uf: String, info: PardalUfCatalogo): Map<String, ByteArray> {
        val esperados = info.arquivos.associateBy { it.nome }
        val achados = HashMap<String, ByteArray>()
        val shas = HashMap<String, String>()
        executar(requisicao("$baseUrl/pardal-$uf.zip").build(), callTimeoutZipS) { r ->
            if (!r.isSuccessful) throw FalhaPardal("zip de $uf: HTTP ${r.code}")
            val contador = ContadorDeBytes(r.body.byteStream())
            fun checarRede() {
                if (contador.lidos > limiteBytes) throw FalhaPardal("zip de $uf passou do limite de $limiteBytes bytes lidos da rede: abortado")
            }
            ZipInputStream(contador).use { zip ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val e = zip.nextEntry ?: break
                    checarRede()
                    if (e.isDirectory) continue
                    val nome = e.name.substringAfterLast('/')
                    val guardar = nome in esperados && nome !in achados
                    // Entradas descartadas só contam como bytes lidos da rede; só as guardadas têm teto descomprimido.
                    val saida = if (guardar) ByteArrayOutputStream() else null
                    val md = if (guardar) MessageDigest.getInstance("SHA-256") else null
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        checarRede()
                        if (saida != null) {
                            if (saida.size() + n > limiteEntradaBytes) throw FalhaPardal("$nome de $uf passou do limite de $limiteEntradaBytes bytes descomprimidos: abortado")
                            saida.write(buf, 0, n)
                            md!!.update(buf, 0, n)
                        }
                    }
                    if (saida != null) {
                        achados[nome] = saida.toByteArray()
                        shas[nome] = md!!.digest().joinToString("") { "%02x".format(it) }
                    }
                }
            }
            tamanhosZip[uf] = contador.lidos
        }
        for ((nome, a) in esperados) {
            if (nome !in achados) throw FalhaPardal("zip de $uf sem $nome")
            if (shas[nome] != a.sha256.lowercase()) throw FalhaPardal("sha256 de $nome não confere com o catálogo")
        }
        return achados
    }

    companion object {
        const val SCHEMA = 2
        const val BASE_URL = "https://github.com/murkiriel/pardal/releases/latest/download"
        /**
         * Teto de bytes COMPRIMIDOS lidos da rede por zip. Medido em 24/11/2026 (Content-Length): SP 10,5 MB, MG 11,2 MB, PE 1,6 MB,
         * AC 0,16 MB; 60 MB dá folga de ~5x sobre o maior.
         */
        const val LIMITE_BYTES = 60L * 1024 * 1024
        /** Teto descomprimido de cada entrada guardada (os 3 CSVs; as demais são só lidas e jogadas fora). */
        const val LIMITE_ENTRADA_BYTES = 20L * 1024 * 1024
        const val LIMITE_CATALOGO_BYTES = 2L * 1024 * 1024
        const val CALL_TIMEOUT_ZIP_S = 300L
        const val CALL_TIMEOUT_CATALOGO_S = 30L
        private const val MAX_REDIRECIONAMENTOS = 5
        const val USER_AGENT = "Rumo/1.0 (app de navegacao para motos; https://github.com/arthurbritol/rumo)"
        const val ARQ_RADARES = "radares.csv"
        const val ARQ_LOMBADAS = "lombadas.csv"
        const val ARQ_ATENCAO = "pontos_atencao.csv"

        /** O GitHub redireciona os downloads de release (github.com -> *.githubusercontent.com): só https e só esses hosts. */
        fun redirecionamentoPermitido(url: HttpUrl): Boolean =
            url.isHttps && (url.host == "github.com" || url.host.endsWith(".githubusercontent.com"))

        /** Os redirecionamentos são seguidos pelo próprio atualizador (com [redirecionamentoPermitido]), não pelo OkHttp. */
        fun clientePadrao(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        private val json = Json { ignoreUnknownKeys = true }

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
