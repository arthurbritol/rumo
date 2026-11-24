package app.hunter.nav.perigos

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PardalArmazenamentoTest {
    private val dir: File = Files.createTempDirectory("pardal-test").toFile()

    @AfterTest
    fun limpar() { dir.deleteRecursively() }

    private val pontos = listOf(
        PerigoPonto("pFIXED:-25.056361,-48.555666", Perigo.RADAR, -25.056361, -48.555666, 60, 184.0, true),
        PerigoPonto("pSECTION:-25.1,-48.5", Perigo.RADAR_DE_TRECHO, -25.1, -48.5, 80, 90.0, true, -25.2, -48.6),
        PerigoPonto("pTABLE:-25.3,-48.7", Perigo.LOMBADA, -25.3, -48.7),
    )

    private fun meta(uf: String = "PR", em: Long = 1_000L, bbox: List<Double> = listOf(-26.0, -49.0, -24.0, -48.0)) =
        PardalMeta(uf, "2026-11-23T09:21:49Z", mapOf("radares.csv" to "abc"), em, mapOf("radares.csv" to 2), bbox)

    @Test
    fun `ida e volta preserva todos os campos`() {
        val a = PardalArmazenamento(dir)
        a.gravar(meta(), pontos)
        val (m, lidos) = assertNotNull(a.ler("PR"))
        assertEquals(pontos, lidos)
        assertEquals("2026-11-23T09:21:49Z", m.artifactBuiltAt)
        assertEquals(1_000L, m.baixadoEm)
        assertEquals(listOf(-26.0, -49.0, -24.0, -48.0), m.bbox)
        assertEquals(listOf("PR"), a.ufsBaixadas())
    }

    @Test
    fun `remover apaga so a UF pedida e e idempotente`() {
        val a = PardalArmazenamento(dir)
        a.gravar(meta("PR"), pontos)
        a.gravar(meta("SP"), pontos)
        a.remover("PR")
        assertNull(a.lerMeta("PR"))
        assertNull(a.ler("PR"))
        assertEquals(listOf("SP"), a.ufsBaixadas())
        assertNotNull(a.ler("SP"))
        a.remover("PR")
        a.remover("XX")
        assertEquals(listOf("SP"), a.ufsBaixadas())
    }

    @Test
    fun `UF inexistente ou vazia nao lanca e conta como nao baixada`() {
        val a = PardalArmazenamento(dir)
        assertNull(a.ler("SP"))
        assertNull(a.lerMeta("SP"))
        assertTrue(a.ufsBaixadas().isEmpty())
    }

    @Test
    fun `csv corrompido vira nao baixada`() {
        val a = PardalArmazenamento(dir)
        a.gravar(meta(), pontos)
        File(dir, "PR.csv").appendText("lixo sem tabs\n")
        assertNull(a.ler("PR"))
    }

    @Test
    fun `meta corrompido vira nao baixada`() {
        val a = PardalArmazenamento(dir)
        a.gravar(meta(), pontos)
        File(dir, "PR.meta").writeText("{ nao e json")
        assertNull(a.ler("PR"))
        assertNull(a.lerMeta("PR"))
        assertTrue(a.ufsBaixadas().isEmpty())
    }

    @Test
    fun `csv truncado ou de outra gravacao e detectado`() {
        val a = PardalArmazenamento(dir)
        a.gravar(meta(), pontos)
        val linhas = File(dir, "PR.csv").readLines()
        File(dir, "PR.csv").writeText(linhas.take(2).joinToString("\n", postfix = "\n"))
        assertNull(a.ler("PR"))
    }

    @Test
    fun `falha na gravacao nao perde o dado antigo nem deixa temporarios`() {
        PardalArmazenamento(dir).gravar(meta(em = 1L), pontos)
        var chamadas = 0
        val quebrado = PardalArmazenamento(dir) { f, b ->
            if (++chamadas == 2) throw java.io.IOException("disco cheio")
            f.writeBytes(b)
        }
        assertFailsWith<java.io.IOException> { quebrado.gravar(meta(em = 2L), pontos.take(1)) }
        val (m, lidos) = assertNotNull(PardalArmazenamento(dir).ler("PR"))
        assertEquals(1L, m.baixadoEm)
        assertEquals(pontos, lidos)
        assertFalse(File(dir, "PR.csv.tmp").exists())
        assertFalse(File(dir, "PR.meta.tmp").exists())
    }

    @Test
    fun `renovar so muda o instante`() {
        val a = PardalArmazenamento(dir)
        a.gravar(meta(em = 1L), pontos)
        a.renovar("PR", 99L)
        val (m, lidos) = assertNotNull(a.ler("PR"))
        assertEquals(99L, m.baixadoEm)
        assertEquals(pontos, lidos)
    }

    // ---------- PardalSource ----------

    private fun tileDe(p: PerigoPonto) = TileId.de(p.lat, p.lon)

    @Test
    fun `source responde pelo quadrado do indice`() = runBlocking<Unit> {
        val a = PardalArmazenamento(dir)
        a.gravar(meta(), pontos)
        val s = PardalSource(a, agora = { 2_000L })
        val r = s.buscar(tileDe(pontos[0]))
        assertEquals(listOf(pontos[0]), r)
        assertEquals(listOf(pontos[2]), s.buscar(tileDe(pontos[2])))
        // Quadrado dentro do bbox mas sem pontos: vazio, não exceção.
        assertTrue(s.buscar(TileId.de(-24.5, -48.5)).isEmpty())
    }

    @Test
    fun `sem UF baixada cobrindo o quadrado lanca SemDadosPardal`() = runBlocking<Unit> {
        val a = PardalArmazenamento(dir)
        val s = PardalSource(a, agora = { 2_000L })
        assertFailsWith<SemDadosPardal> { s.buscar(TileId.de(-23.5, -46.6)) }
        a.gravar(meta(), pontos)
        assertFailsWith<SemDadosPardal> { s.buscar(TileId.de(-23.5, -46.6)) } // fora do bbox do PR
        assertFalse(s.cobre(TileId.de(-23.5, -46.6)))
    }

    @Test
    fun `UF com dado corrompido tambem lanca SemDadosPardal`() = runBlocking<Unit> {
        val a = PardalArmazenamento(dir)
        a.gravar(meta(), pontos)
        File(dir, "PR.csv").writeText("lixo")
        val s = PardalSource(a, agora = { 2_000L })
        assertFailsWith<SemDadosPardal> { s.buscar(tileDe(pontos[0])) }
    }

    @Test
    fun `cobre exige dado recente de ate 14 dias`() {
        val a = PardalArmazenamento(dir)
        a.gravar(meta(em = 0L), pontos)
        val t = tileDe(pontos[0])
        assertTrue(PardalSource(a, agora = { PardalSource.RECENTE_MS }).cobre(t))
        assertFalse(PardalSource(a, agora = { PardalSource.RECENTE_MS + 1 }).cobre(t))
    }

    @Test
    fun `dado vencido ainda responde no buscar`() = runBlocking<Unit> {
        val a = PardalArmazenamento(dir)
        a.gravar(meta(em = 0L), pontos)
        val s = PardalSource(a, agora = { PardalSource.RECENTE_MS * 10 })
        assertEquals(listOf(pontos[0]), s.buscar(tileDe(pontos[0])))
    }

    @Test
    fun `LRU mantem so 3 UFs em memoria e invalidar descarta`() = runBlocking<Unit> {
        val a = PardalArmazenamento(dir)
        val ufs = listOf("AA", "BB", "CC", "DD")
        ufs.forEachIndexed { i, uf ->
            val lat0 = -20.0 - i * 2
            a.gravar(meta(uf, bbox = listOf(lat0 - 1, -49.0, lat0 + 1, -48.0)), listOf(PerigoPonto("p$uf", Perigo.LOMBADA, lat0, -48.5)))
        }
        val s = PardalSource(a, agora = { 1_000L })
        ufs.forEachIndexed { i, _ -> s.buscar(TileId.de(-20.0 - i * 2, -48.5)) }
        assertEquals(setOf("BB", "CC", "DD"), s.ufsEmMemoria())
        s.invalidar("CC")
        assertEquals(setOf("BB", "DD"), s.ufsEmMemoria())
    }
}
