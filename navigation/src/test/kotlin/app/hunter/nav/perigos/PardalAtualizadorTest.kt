package app.hunter.nav.perigos

import app.hunter.nav.LatLon
import app.hunter.nav.Route
import app.hunter.nav.RouteProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PardalAtualizadorTest {
    private val dir: File = Files.createTempDirectory("pardal-atu").toFile()
    private val server = MockWebServer()
    private var wifi = true
    private var hora = 1_000_000L

    // Estado "do servidor", mutável pelos testes.
    private var catalogoJson = ""
    private var zipBytes = ByteArray(0)
    private var catalogoCodigo = 200
    private var zipCodigo = 200
    private val pedidos = ArrayList<String>()

    private fun recurso(nome: String) = javaClass.getResource("/pardal/$nome")!!.readBytes()
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun montarZip(extras: Map<String, ByteArray> = emptyMap(), omitir: Set<String> = emptySet()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            val todos = linkedMapOf(
                "PR/limites.csv" to java.util.Random(1).let { r -> ByteArray(2 * 1024 * 1024).also { r.nextBytes(it) } }, // grande e incompressivel, deve ser descartado
                "PR/radares.csv" to recurso("radares.csv"),
                "PR/lombadas.csv" to recurso("lombadas.csv"),
                "PR/pontos_atencao.csv" to recurso("pontos_atencao.csv"),
                "PR/LICENCA-DADOS.txt" to "licenca".toByteArray(),
            )
            todos.putAll(extras)
            for ((n, b) in todos) {
                if (n.substringAfterLast('/') in omitir) continue
                z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun bloco(nome: String, b: ByteArray, count: Int) =
        """"$nome": {"file": "estados/PR/x.csv", "bytes": ${b.size}, "sha256": "${sha(b)}", "count": $count, "extra": 1}"""

    private fun catalogo(
        schema: Int = 2,
        artefato: String = "2026-11-23T09:21:49Z",
        shaRadares: String = sha(recurso("radares.csv")),
        bbox: String = "[-26.72, -54.62, -22.5, -48.02]",
        semRadares: Boolean = false,
    ) = """{"schema": $schema, "built_at": "2026-11-23T09:46:24Z", "campo_novo": true, "ufs": {
        "PR": {"name": "Parana", "bbox": $bbox, "artifact_built_at": "$artefato", "coverage": {"level": "x"},
          ${if (semRadares) "" else "\"cameras\": {\"file\": \"a\", \"bytes\": 1, \"sha256\": \"$shaRadares\", \"count\": 2},"}
          ${bloco("bumps", recurso("lombadas.csv"), 1)},
          ${bloco("hazards", recurso("pontos_atencao.csv"), 1)}},
        "SP": {"bbox": [-25.31, -53.11, -19.78, -44.16], "artifact_built_at": "x"},
        "SC": {"bbox": [-29.35, -53.84, -25.95, -48.55], "artifact_built_at": "x"},
        "RS": {"bbox": [-33.75, -57.65, -27.08, -49.69], "artifact_built_at": "x"}
    }}"""

    @BeforeTest
    fun subir() {
        catalogoJson = catalogo()
        zipBytes = montarZip()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                pedidos += "${request.method} ${request.path}"
                return when {
                    request.path == "/catalogo.json" ->
                        if (catalogoCodigo != 200) MockResponse().setResponseCode(catalogoCodigo) else MockResponse().setBody(catalogoJson)
                    request.path == "/pardal-PR.zip" && request.method == "HEAD" ->
                        MockResponse().setHeader("Content-Length", zipBytes.size.toString())
                    request.path == "/pardal-PR.zip" ->
                        if (zipCodigo != 200) MockResponse().setResponseCode(zipCodigo) else MockResponse().setBody(Buffer().write(zipBytes))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @AfterTest
    fun descer() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun atualizador(
        limite: Long = PardalAtualizador.LIMITE_BYTES,
        limiteEntrada: Long = PardalAtualizador.LIMITE_ENTRADA_BYTES,
        redirecionamento: ((okhttp3.HttpUrl) -> Boolean)? = null,
        timeoutCatalogoS: Long = PardalAtualizador.CALL_TIMEOUT_CATALOGO_S,
    ) = PardalAtualizador(
        armazenamento = PardalArmazenamento(dir),
        rede = object : RedePolitica { override fun emWifi() = wifi },
        baseUrl = server.url("/").toString().trimEnd('/'),
        limiteBytes = limite,
        agora = { hora },
        limiteEntradaBytes = limiteEntrada,
        callTimeoutCatalogoS = timeoutCatalogoS,
        redirecionamentoPermitido = redirecionamento ?: PardalAtualizador.Companion::redirecionamentoPermitido,
    )

    private val armazenamento get() = PardalArmazenamento(dir)
    private fun zipsPedidos() = pedidos.count { it == "GET /pardal-PR.zip" }

    @Test
    fun `baixa, valida, converte e grava so os 3 csvs`() = runBlocking<Unit> {
        val r = atualizador().atualizar("PR", false)
        val ok = assertIs<ResultadoPardal.Atualizada>(r)
        val (meta, pontos) = assertNotNull(armazenamento.ler("PR"))
        assertEquals(ok.pontos, pontos.size)
        assertEquals(PardalCsv.radares(String(recurso("radares.csv"))).size + 2, pontos.size)
        assertEquals("2026-11-23T09:21:49Z", meta.artifactBuiltAt)
        assertEquals(listOf(-26.72, -54.62, -22.5, -48.02), meta.bbox)
        assertEquals(hora, meta.baixadoEm)
        assertEquals(setOf("radares.csv", "lombadas.csv", "pontos_atencao.csv"), meta.sha256.keys)
        // Tipos vindos dos 3 arquivos.
        assertTrue(pontos.any { it.tipo == Perigo.RADAR })
        assertTrue(pontos.any { it.tipo == Perigo.LOMBADA })
        assertTrue(pontos.any { it.tipo == Perigo.TRAVESSIA_FERROVIARIA })
    }

    @Test
    fun `manda User-Agent do Rumo`() = runBlocking<Unit> {
        val ua = ArrayList<String?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                ua += request.getHeader("User-Agent")
                return MockResponse().setResponseCode(500)
            }
        }
        atualizador().atualizar("PR", false)
        assertTrue(ua.isNotEmpty() && ua.all { it!!.startsWith("Rumo/") && "github.com/arthurbritol/rumo" in it })
    }

    @Test
    fun `ja em dia nao baixa o zip e renova o instante`() = runBlocking<Unit> {
        atualizador().atualizar("PR", false)
        pedidos.clear()
        hora += 1000
        val r = atualizador().atualizar("PR", false)
        assertIs<ResultadoPardal.JaEmDia>(r)
        assertEquals(0, zipsPedidos())
        assertEquals(hora, armazenamento.lerMeta("PR")!!.baixadoEm)
    }

    @Test
    fun `artefato novo no catalogo baixa de novo`() = runBlocking<Unit> {
        atualizador().atualizar("PR", false)
        catalogoJson = catalogo(artefato = "2026-11-30T00:00:00Z")
        assertIs<ResultadoPardal.Atualizada>(atualizador().atualizar("PR", false))
        assertEquals("2026-11-30T00:00:00Z", armazenamento.lerMeta("PR")!!.artifactBuiltAt)
    }

    @Test
    fun `schema diferente nao atualiza e mantem o dado antigo`() = runBlocking<Unit> {
        atualizador().atualizar("PR", false)
        val antes = assertNotNull(armazenamento.ler("PR"))
        catalogoJson = catalogo(schema = 3, artefato = "novo")
        pedidos.clear()
        hora += 10_000_000
        val r = atualizador().atualizar("PR", false)
        val f = assertIs<ResultadoPardal.Falhou>(r)
        assertTrue("schema 3" in f.motivo, f.motivo)
        assertEquals(0, zipsPedidos())
        assertEquals(antes, armazenamento.ler("PR"))
    }

    @Test
    fun `sha256 errado nao muda nada`() = runBlocking<Unit> {
        atualizador().atualizar("PR", false)
        val antes = assertNotNull(armazenamento.ler("PR"))
        catalogoJson = catalogo(artefato = "novo", shaRadares = "0".repeat(64))
        hora += 10_000_000
        val f = assertIs<ResultadoPardal.Falhou>(atualizador().atualizar("PR", false))
        assertTrue("sha256" in f.motivo, f.motivo)
        assertEquals(antes, armazenamento.ler("PR"))
    }

    @Test
    fun `zip sem um dos arquivos falha sem gravar`() = runBlocking<Unit> {
        zipBytes = montarZip(omitir = setOf("lombadas.csv"))
        assertIs<ResultadoPardal.Falhou>(atualizador().atualizar("PR", false))
        assertNull(armazenamento.ler("PR"))
    }

    @Test
    fun `entradas grandes sao descartadas e entradas extras ignoradas`() = runBlocking<Unit> {
        zipBytes = montarZip(extras = mapOf("PR/buracos.csv" to ByteArray(3 * 1024 * 1024)))
        assertIs<ResultadoPardal.Atualizada>(atualizador().atualizar("PR", false))
        // Só nossos arquivos em disco.
        assertEquals(setOf("PR.csv", "PR.meta", "catalogo-bbox.json"), dir.list()!!.toSet())
    }

    @Test
    fun `limite de tamanho aborta e nada muda`() = runBlocking<Unit> {
        val f = assertIs<ResultadoPardal.Falhou>(atualizador(limite = 1024 * 1024).atualizar("PR", false))
        assertTrue("limite" in f.motivo, f.motivo)
        assertNull(armazenamento.ler("PR"))
    }

    @Test
    fun `fora do wifi sem permissao pede confirmacao e nao toca a rede (nem catalogo nem HEAD)`() = runBlocking<Unit> {
        wifi = false
        val r = assertIs<ResultadoPardal.PrecisaConfirmarDadosMoveis>(atualizador().atualizar("PR", false))
        assertNull(r.bytes)
        assertTrue(pedidos.isEmpty(), pedidos.toString())
        assertNull(armazenamento.ler("PR"))
    }

    @Test
    fun `depois de baixar, o pedido seguinte ja sabe o tamanho do zip`() = runBlocking<Unit> {
        val a = atualizador()
        a.atualizar("PR", false)
        wifi = false
        catalogoJson = catalogo(artefato = "novo")
        val r = assertIs<ResultadoPardal.PrecisaConfirmarDadosMoveis>(a.atualizar("PR", false))
        assertTrue((r.bytes ?: 0L) > 0L)
    }

    @Test
    fun `dados moveis permitidos baixam fora do wifi`() = runBlocking<Unit> {
        wifi = false
        assertIs<ResultadoPardal.Atualizada>(atualizador().atualizar("PR", true))
    }

    @Test
    fun `falha de rede liga o recuo exponencial e nada muda`() = runBlocking<Unit> {
        catalogoCodigo = 503
        val a = atualizador()
        assertIs<ResultadoPardal.Falhou>(a.atualizar("PR", false))
        assertEquals(hora + 60_000, a.bloqueadoAte("PR"))
        pedidos.clear()
        val f = assertIs<ResultadoPardal.Falhou>(a.atualizar("PR", false))
        assertTrue("recuo" in f.motivo)
        assertTrue(pedidos.isEmpty())
        // Depois do recuo, e com o servidor bom, volta a funcionar.
        hora += 60_001
        catalogoCodigo = 200
        assertIs<ResultadoPardal.Atualizada>(a.atualizar("PR", false))
    }

    @Test
    fun `UF fora do catalogo falha`() = runBlocking<Unit> {
        assertIs<ResultadoPardal.Falhou>(atualizador().atualizar("ZZ", false))
    }

    // ---------- escolha de UF ----------

    @Test
    fun `ufsPara devolve todas as UFs cujo bbox contem o ponto, e nenhuma fora`() = runBlocking<Unit> {
        val a = atualizador()
        a.atualizar("PR", false) // carrega o catálogo (4 UFs) na instância
        // Curitiba: só o bbox do PR a contém.
        assertEquals(listOf("PR"), a.ufsPara(-25.43, -49.27))
        // Sobreposição PR x SP e PR x SC: todas, em ordem alfabética.
        assertEquals(listOf("PR", "SP"), a.ufsPara(-25.0, -49.0))
        assertEquals(listOf("PR", "SC"), a.ufsPara(-26.3, -49.0))
        // Fronteira exata do bbox conta como dentro.
        assertEquals(listOf("PR", "SP"), a.ufsPara(-22.5, -48.02))
        // Oceano / fora.
        assertTrue(a.ufsPara(-10.0, -20.0).isEmpty())
    }

    @Test
    fun `bbox do catalogo ficam em disco e ufsPara funciona offline numa instancia nova`() = runBlocking<Unit> {
        assertTrue(atualizador().ufsPara(-25.43, -49.27).isEmpty()) // nunca carregado
        assertTrue(atualizador().carregarCatalogo())
        assertEquals(setOf("PR", "SP", "SC", "RS"), armazenamento.lerBboxes().keys)
        server.shutdown()
        val offline = atualizador()
        assertEquals(listOf("PR", "SP"), offline.ufsPara(-25.0, -49.0))
        assertTrue(offline.bboxesConhecidos())
    }

    @Test
    fun `ufsParaRota amostra a cada 2 km e lista UFs na ordem sem repetir`() = runBlocking<Unit> {
        val a = atualizador()
        a.atualizar("PR", false)
        // ~220 km ao longo de um meridiano (lon -49,5), de lat -27,0 (SC) a -25,0 (PR/SP).
        val shape = (0..200).map { LatLon(-27.0 + it * 0.01, -49.5) }
        val rota = Route(shape, emptyList(), 220_000.0, 10_000.0, false, false, RouteProfile.FASTEST)
        // SC primeiro; depois PR (e SC); por fim SP (lat > -25,31). Sem repetição.
        assertEquals(listOf("SC", "PR", "SP"), a.ufsParaRota(rota))
        // Rota toda fora de qualquer bbox: nenhuma UF.
        val fora = Route(listOf(LatLon(-10.0, -20.0), LatLon(-10.1, -20.0)), emptyList(), 11_000.0, 600.0, false, false, RouteProfile.FASTEST)
        assertTrue(a.ufsParaRota(fora).isEmpty())
    }

    // ---------- revisao: catalogo, limites, rede ----------

    @Test
    fun `catalogo sem bloco de radares falha e nao grava UF vazia`() = runBlocking<Unit> {
        catalogoJson = catalogo(semRadares = true)
        val f = assertIs<ResultadoPardal.Falhou>(atualizador().atualizar("PR", false))
        assertTrue("radares" in f.motivo, f.motivo)
        assertNull(armazenamento.lerMeta("PR"))
        assertEquals(0, zipsPedidos())
    }

    @Test
    fun `entrada guardada acima do limite por entrada falha, mas entrada descartada grande passa`() = runBlocking<Unit> {
        val f = assertIs<ResultadoPardal.Falhou>(atualizador(limiteEntrada = 100).atualizar("PR", false))
        assertTrue("descomprimidos" in f.motivo, f.motivo)
        assertNull(armazenamento.ler("PR"))
        // limites.csv (2 MB, descartado) é maior que o limite de 1 MB por entrada e não conta.
        hora += 10_000_000
        assertIs<ResultadoPardal.Atualizada>(atualizador(limiteEntrada = 1024 * 1024).atualizar("PR", false))
    }

    @Test
    fun `o teto total conta bytes comprimidos lidos da rede, inclusive das entradas descartadas`() = runBlocking<Unit> {
        // O zip tem ~2 MB incompressiveis (limites.csv, descartado): passa de 1 MB lido da rede.
        val f = assertIs<ResultadoPardal.Falhou>(atualizador(limite = 1024 * 1024).atualizar("PR", false))
        assertTrue("rede" in f.motivo, f.motivo)
        hora += 10_000_000
        // Teto folgado (o zip real tem ~2 MB comprimidos).
        assertIs<ResultadoPardal.Atualizada>(atualizador(limite = 4L * 1024 * 1024).atualizar("PR", false))
    }

    @Test
    fun `falha de uma UF nao poe as outras em recuo`() = runBlocking<Unit> {
        zipCodigo = 500
        val a = atualizador()
        assertIs<ResultadoPardal.Falhou>(a.atualizar("PR", false))
        assertEquals(hora + 60_000, a.bloqueadoAte("PR"))
        assertEquals(0L, a.bloqueadoAte("SC"))
        val f = assertIs<ResultadoPardal.Falhou>(a.atualizar("SC", false))
        assertTrue("recuo" !in f.motivo, f.motivo)
    }

    @Test
    fun `redirecionamento para http ou host fora do GitHub e recusado`() = runBlocking<Unit> {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/catalogo.json" -> MockResponse().setResponseCode(302).setHeader("Location", "http://evil.example/catalogo.json")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val f = assertIs<ResultadoPardal.Falhou>(atualizador().atualizar("PR", false))
        assertTrue("não permitido" in f.motivo, f.motivo)
        assertTrue(!PardalAtualizador.redirecionamentoPermitido("http://github.com/x".toHttpUrl()))
        assertTrue(!PardalAtualizador.redirecionamentoPermitido("https://evil.example/x".toHttpUrl()))
        assertTrue(!PardalAtualizador.redirecionamentoPermitido("https://githubusercontent.com.evil.example/x".toHttpUrl()))
        assertTrue(PardalAtualizador.redirecionamentoPermitido("https://github.com/x".toHttpUrl()))
        assertTrue(PardalAtualizador.redirecionamentoPermitido("https://objects.githubusercontent.com/x".toHttpUrl()))
        assertTrue(PardalAtualizador.redirecionamentoPermitido("https://release-assets.githubusercontent.com/x".toHttpUrl()))
    }

    @Test
    fun `redirecionamento permitido e seguido`() = runBlocking<Unit> {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/catalogo.json" -> MockResponse().setResponseCode(302).setHeader("Location", "/real/catalogo.json")
                "/real/catalogo.json" -> MockResponse().setBody(catalogoJson)
                else -> MockResponse().setResponseCode(404)
            }
        }
        assertTrue(atualizador(redirecionamento = { true }).carregarCatalogo())
    }

    @Test
    fun `catalogo maior que 2 MB e recusado`() = runBlocking<Unit> {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(Buffer().write(ByteArray(3 * 1024 * 1024) { 'x'.code.toByte() }))
        }
        assertTrue(!atualizador().carregarCatalogo())
        val f = assertIs<ResultadoPardal.Falhou>(atualizador().atualizar("PR", false))
        assertTrue("grande demais" in f.motivo, f.motivo)
    }

    @Test
    fun `cancelar a coroutine cancela a chamada de rede em andamento`() = runBlocking<Unit> {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(catalogoJson).setBodyDelay(20, TimeUnit.SECONDS)
        }
        val a = atualizador()
        val job = launch(Dispatchers.Default) { a.atualizar("PR", false) }
        delay(500)
        withTimeout(5_000) { job.cancelAndJoin() }
    }

    @Test
    fun `callTimeout derruba a chamada lenta e vira falha`() = runBlocking<Unit> {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(catalogoJson).setBodyDelay(20, TimeUnit.SECONDS)
        }
        val f = assertIs<ResultadoPardal.Falhou>(withTimeout(10_000) { atualizador(timeoutCatalogoS = 1).atualizar("PR", false) })
        assertTrue(f.motivo.isNotBlank())
    }

    @Test
    fun `carregar o catalogo nao espera um download de zip em andamento`() = runBlocking<Unit> {
        var zipIniciado = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/catalogo.json" -> MockResponse().setBody(catalogoJson)
                "/pardal-PR.zip" -> { zipIniciado = true; MockResponse().setBody(Buffer().write(zipBytes)).setBodyDelay(4, TimeUnit.SECONDS) }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val a = atualizador()
        val job = launch(Dispatchers.Default) { a.atualizar("PR", false) }
        withTimeout(5_000) { while (!zipIniciado) delay(20) }
        val t0 = System.nanoTime()
        assertTrue(withTimeout(3_000) { a.carregarCatalogo() })
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 3_000)
        job.cancelAndJoin()
    }
}
