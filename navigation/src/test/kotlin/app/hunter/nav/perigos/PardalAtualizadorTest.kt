package app.hunter.nav.perigos

import app.hunter.nav.LatLon
import app.hunter.nav.Route
import app.hunter.nav.RouteProfile
import kotlinx.coroutines.runBlocking
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
    private val pedidos = ArrayList<String>()

    private fun recurso(nome: String) = javaClass.getResource("/pardal/$nome")!!.readBytes()
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun montarZip(extras: Map<String, ByteArray> = emptyMap(), omitir: Set<String> = emptySet()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            val todos = linkedMapOf(
                "PR/limites.csv" to ByteArray(2 * 1024 * 1024) { (it % 7).toByte() }, // grande, deve ser descartado
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
    ) = """{"schema": $schema, "built_at": "2026-11-23T09:46:24Z", "campo_novo": true, "ufs": {
        "PR": {"name": "Parana", "bbox": $bbox, "artifact_built_at": "$artefato", "coverage": {"level": "x"},
          "cameras": {"file": "a", "bytes": 1, "sha256": "$shaRadares", "count": 2},
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
                    request.path == "/pardal-PR.zip" -> MockResponse().setBody(Buffer().write(zipBytes))
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

    private fun atualizador(limite: Long = PardalAtualizador.LIMITE_BYTES) = PardalAtualizador(
        armazenamento = PardalArmazenamento(dir),
        rede = object : RedePolitica { override fun emWifi() = wifi },
        baseUrl = server.url("/").toString().trimEnd('/'),
        limiteBytes = limite,
        agora = { hora },
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
        assertEquals(setOf("PR.csv", "PR.meta"), dir.list()!!.toSet())
    }

    @Test
    fun `limite de tamanho aborta e nada muda`() = runBlocking<Unit> {
        val f = assertIs<ResultadoPardal.Falhou>(atualizador(limite = 1024 * 1024).atualizar("PR", false))
        assertTrue("limite" in f.motivo, f.motivo)
        assertNull(armazenamento.ler("PR"))
    }

    @Test
    fun `fora do wifi sem permissao pede confirmacao com o tamanho e nao baixa`() = runBlocking<Unit> {
        wifi = false
        val r = assertIs<ResultadoPardal.PrecisaConfirmarDadosMoveis>(atualizador().atualizar("PR", false))
        assertEquals(zipBytes.size.toLong(), r.bytes)
        assertEquals(0, zipsPedidos())
        assertNull(armazenamento.ler("PR"))
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
        assertEquals(hora + 60_000, a.bloqueadoAte())
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
    fun `ufsPara escolhe pelo bbox, a menor area na sobreposicao, e nenhuma fora`() = runBlocking<Unit> {
        val a = atualizador()
        a.atualizar("PR", false) // carrega o catálogo (4 UFs) na instância
        // Curitiba: só o bbox do PR a contém.
        assertEquals("PR", a.ufsPara(-25.43, -49.27))
        // Sobreposição PR x SP: o PR tem a menor área.
        assertEquals("PR", a.ufsPara(-25.0, -49.0))
        // Sobreposição PR x SC: o SC tem a menor área.
        assertEquals("SC", a.ufsPara(-26.3, -49.0))
        // Fronteira exata do bbox conta como dentro.
        assertEquals("PR", a.ufsPara(-22.5, -48.02))
        // Oceano / fora.
        assertNull(a.ufsPara(-10.0, -20.0))
    }

    @Test
    fun `ufsParaRota amostra a cada 2 km e lista UFs na ordem sem repetir`() = runBlocking<Unit> {
        val a = atualizador()
        a.atualizar("PR", false)
        // ~220 km ao longo de um meridiano (lon -49,5), de lat -27,0 (SC) a -25,0 (PR/SP).
        val shape = (0..200).map { LatLon(-27.0 + it * 0.01, -49.5) }
        val rota = Route(shape, emptyList(), 220_000.0, 10_000.0, false, false, RouteProfile.FASTEST)
        // lat -27..-26,72: SC; -26,72..-25,95: SC (menor área); depois PR/SP (PR menor). Sem repetição.
        assertEquals(listOf("SC", "PR"), a.ufsParaRota(rota))
        // Rota toda fora de qualquer bbox: nenhuma UF.
        val fora = Route(listOf(LatLon(-10.0, -20.0), LatLon(-10.1, -20.0)), emptyList(), 11_000.0, 600.0, false, false, RouteProfile.FASTEST)
        assertTrue(a.ufsParaRota(fora).isEmpty())
    }
}
