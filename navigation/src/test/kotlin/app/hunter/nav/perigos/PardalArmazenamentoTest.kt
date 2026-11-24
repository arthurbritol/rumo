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

    private fun PardalArmazenamento.gravarCom(m: PardalMeta, p: List<PerigoPonto>) {
        gravarBboxes(lerBboxes() + (m.uf to m.bbox))
        gravar(m, p)
    }

    // ---------- PardalSource ----------

    private fun tileDe(p: PerigoPonto) = TileId.de(p.lat, p.lon)

    @Test
    fun `source responde pelo quadrado do indice`() = runBlocking<Unit> {
        val a = PardalArmazenamento(dir)
        a.gravarCom(meta(), pontos)
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
        a.gravarCom(meta(), pontos)
        assertFailsWith<SemDadosPardal> { s.buscar(TileId.de(-23.5, -46.6)) } // fora do bbox do PR
        assertFalse(s.cobre(TileId.de(-23.5, -46.6)))
    }

    @Test
    fun `UF com dado corrompido tambem lanca SemDadosPardal`() = runBlocking<Unit> {
        val a = PardalArmazenamento(dir)
        a.gravarCom(meta(), pontos)
        File(dir, "PR.csv").writeText("lixo")
        val s = PardalSource(a, agora = { 2_000L })
        assertFailsWith<SemDadosPardal> { s.buscar(tileDe(pontos[0])) }
    }

    @Test
    fun `cobre exige dado recente de ate 14 dias`() {
        val a = PardalArmazenamento(dir)
        a.gravarCom(meta(em = 0L), pontos)
        val t = tileDe(pontos[0])
        assertTrue(PardalSource(a, agora = { PardalSource.RECENTE_MS }).cobre(t))
        assertFalse(PardalSource(a, agora = { PardalSource.RECENTE_MS + 1 }).cobre(t))
    }

    @Test
    fun `dado vencido ainda responde no buscar`() = runBlocking<Unit> {
        val a = PardalArmazenamento(dir)
        a.gravarCom(meta(em = 0L), pontos)
        val s = PardalSource(a, agora = { PardalSource.RECENTE_MS * 10 })
        assertEquals(listOf(pontos[0]), s.buscar(tileDe(pontos[0])))
    }

    @Test
    fun `LRU mantem so 3 UFs em memoria e invalidar descarta`() = runBlocking<Unit> {
        val a = PardalArmazenamento(dir)
        val ufs = listOf("AA", "BB", "CC", "DD")
        ufs.forEachIndexed { i, uf ->
            val lat0 = -20.0 - i * 2
            a.gravarCom(meta(uf, bbox = listOf(lat0 - 1, -49.0, lat0 + 1, -48.0)), listOf(PerigoPonto("p$uf", Perigo.LOMBADA, lat0, -48.5)))
        }
        val s = PardalSource(a, agora = { 1_000L })
        ufs.forEachIndexed { i, _ -> s.buscar(TileId.de(-20.0 - i * 2, -48.5)) }
        assertEquals(setOf("BB", "CC", "DD"), s.ufsEmMemoria())
        s.invalidar("CC")
        assertEquals(setOf("BB", "DD"), s.ufsEmMemoria())
    }

    // ---------- bbox do catalogo e cobertura por fronteira ----------

    private val bboxSP = listOf(-25.31, -53.11, -19.78, -44.16)
    private val bboxMG = listOf(-22.92, -51.05, -14.23, -39.86)
    private val bboxGO = listOf(-19.5, -53.25, -12.4, -45.9)
    private val bboxDF = listOf(-16.05, -48.29, -15.5, -47.31)
    private val pouso = TileId.de(-22.23, -45.94) // Pouso Alegre (MG), dentro do bbox de SP

    private fun uf(a: PardalArmazenamento, sigla: String, bbox: List<Double>) =
        a.gravar(meta(sigla, em = 1_000L, bbox = bbox), listOf(PerigoPonto("p$sigla", Perigo.LOMBADA, bbox[0] + 0.01, bbox[1] + 0.01)))

    @Test
    fun `bboxes do catalogo vao e voltam, sem reescrever o que nao mudou`() {
        val a = PardalArmazenamento(dir)
        assertTrue(a.lerBboxes().isEmpty())
        a.gravarBboxes(mapOf("SP" to bboxSP, "MG" to bboxMG))
        assertEquals(mapOf("SP" to bboxSP, "MG" to bboxMG), PardalArmazenamento(dir).lerBboxes())
        assertFalse(File(dir, "catalogo-bbox.json.tmp").exists())
        File(dir, PardalArmazenamento.ARQ_BBOX).writeText("lixo")
        assertTrue(a.lerBboxes().isEmpty())
    }

    @Test
    fun `fronteira SP e MG so cobre com as duas baixadas`() {
        val a = PardalArmazenamento(dir)
        a.gravarBboxes(mapOf("SP" to bboxSP, "MG" to bboxMG))
        val s = PardalSource(a, agora = { 2_000L })
        uf(a, "SP", bboxSP)
        // Só SP: o bbox de SP cobre o sul de MG, mas MG toca o quadrado e falta: Overpass.
        assertFalse(s.cobre(pouso))
        uf(a, "MG", bboxMG)
        assertTrue(s.cobre(pouso))
        // Quadrado dentro de SP e longe de MG: só SP basta.
        assertTrue(s.cobre(TileId.de(-23.5, -49.5)))
    }

    @Test
    fun `fronteira tambem exige UF recente`() {
        val a = PardalArmazenamento(dir)
        a.gravarBboxes(mapOf("SP" to bboxSP, "MG" to bboxMG))
        uf(a, "SP", bboxSP)
        a.gravar(meta("MG", em = 0L, bbox = bboxMG), listOf(PerigoPonto("pMG", Perigo.LOMBADA, -22.0, -45.0)))
        a.renovar("SP", 20L * 24 * 3600 * 1000)
        val s = PardalSource(a, agora = { 20L * 24 * 3600 * 1000 })
        assertFalse(s.cobre(pouso)) // MG tem 20 dias
    }

    @Test
    fun `DF dentro de GO exige as duas`() {
        val a = PardalArmazenamento(dir)
        a.gravarBboxes(mapOf("GO" to bboxGO, "DF" to bboxDF))
        val s = PardalSource(a, agora = { 2_000L })
        val brasilia = TileId.de(-15.79, -47.88)
        uf(a, "DF", bboxDF)
        assertFalse(s.cobre(brasilia))
        uf(a, "GO", bboxGO)
        assertTrue(s.cobre(brasilia))
    }

    @Test
    fun `sem bbox conhecido o Pardal nao cobre nada, mesmo com UF baixada`() = runBlocking<Unit> {
        val a = PardalArmazenamento(dir)
        uf(a, "SP", bboxSP)
        val s = PardalSource(a, agora = { 2_000L })
        assertFalse(s.cobre(TileId.de(-23.5, -46.6)))
        // buscar (reserva) ainda funciona com o bbox do próprio meta.
        assertEquals(1, s.buscar(TileId.de(-25.30, -53.10)).size)
    }

    @Test
    fun `quadrado fora de todos os bbox nao e coberto`() {
        val a = PardalArmazenamento(dir)
        a.gravarBboxes(mapOf("SP" to bboxSP))
        uf(a, "SP", bboxSP)
        assertFalse(PardalSource(a, agora = { 2_000L }).cobre(TileId.de(-8.05, -34.95)))
    }

    @Test
    fun `indice velho de um buscar lento nao sobrevive a uma atualizacao`() = runBlocking<Unit> {
        val a = PardalArmazenamento(dir)
        a.gravar(meta(), pontos.take(1))
        val s = PardalSource(a, agora = { 2_000L })
        assertEquals(1, s.buscar(tileDe(pontos[0])).size)
        // Nova gravação SEM invalidar (como se um buscar lento tivesse reposto o índice antigo depois do invalidar).
        a.gravar(meta(), listOf(pontos[0].copy(id = "novo")))
        assertEquals(listOf("novo"), s.buscar(tileDe(pontos[0])).map { it.id })
    }
}
