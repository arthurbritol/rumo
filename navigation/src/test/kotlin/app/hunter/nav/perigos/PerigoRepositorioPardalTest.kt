package app.hunter.nav.perigos

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PerigoRepositorioPardalTest {
    private class MemCache : PerigoCacheStore {
        val dados = HashMap<TileId, QuadradoEmCache>()
        var leituras = 0
        override fun ler(tile: TileId): QuadradoEmCache? { leituras++; return dados[tile] }
        override fun gravar(tile: TileId, q: QuadradoEmCache) { dados[tile] = q }
    }

    private class FonteOverpass : PerigoSource {
        val buscados = mutableListOf<TileId>()
        override suspend fun buscar(tile: TileId): List<PerigoPonto> {
            buscados += tile
            delay(100)
            return listOf(PerigoPonto("o${tile.ix}_${tile.iy}", Perigo.LOMBADA, tile.sul + 0.05, tile.oeste + 0.05))
        }
    }

    private val base = 1_700_000_000_000L
    private val dir: File = java.nio.file.Files.createTempDirectory("pardal-repo").toFile()

    @AfterTest fun limpar() { dir.deleteRecursively() }

    // Bbox padrão cobre o Recife (-8.05, -34.95) e arredores.
    private fun gravarUf(arm: PardalArmazenamento, uf: String, em: Long, pontos: List<PerigoPonto>, bbox: List<Double> = listOf(-9.0, -36.0, -7.0, -34.0)) =
        arm.gravar(PardalMeta(uf, "2026-11-23T09:21:49Z", mapOf("radares.csv" to "x"), em, mapOf("radares.csv" to pontos.size), bbox), pontos)

    private fun ponto(id: String, lat: Double, lon: Double) = PerigoPonto(id, Perigo.RADAR, lat, lon, 60, null, true)

    private fun TestScope.montar(src: PerigoSource, cache: PerigoCacheStore, local: PardalSource?, ativo: () -> Boolean = { true }, max: Int = 150) =
        PerigoRepositorio(
            src, cache, CoroutineScope(StandardTestDispatcher(testScheduler)), agora = { base + currentTime },
            intervaloMinMs = 2_000, maxQuadrados = max, io = StandardTestDispatcher(testScheduler), local = local, pardalAtivo = ativo,
        )

    private fun TestScope.pardal(arm: PardalArmazenamento) =
        PardalSource(arm, agora = { base + currentTime }, io = StandardTestDispatcher(testScheduler))

    @Test fun `Pardal cobre, nao chama o Overpass nem le o cache e nao grava em disco`() = runTest {
        val arm = PardalArmazenamento(dir)
        gravarUf(arm, "PE", base, listOf(ponto("pA", -8.05, -34.95)))
        val src = FonteOverpass(); val cache = MemCache()
        val r = montar(src, cache, pardal(arm))
        r.aoMudarPosicao(-8.05, -34.95, amplo = false)
        advanceUntilIdle()
        assertEquals(listOf("pA"), r.perigos.value.map { it.id })
        assertTrue(src.buscados.isEmpty())
        assertEquals(0, cache.leituras)
        assertTrue(cache.dados.isEmpty())
        assertTrue(r.ocioso())
    }

    @Test fun `Pardal tem prioridade sobre o cache do Overpass`() = runTest {
        val arm = PardalArmazenamento(dir)
        gravarUf(arm, "PE", base, listOf(ponto("pA", -8.05, -34.95)))
        val cache = MemCache()
        cache.dados[TileId.de(-8.05, -34.95)] = QuadradoEmCache(base, listOf(PerigoPonto("velho", Perigo.LOMBADA, -8.05, -34.95)))
        val r = montar(FonteOverpass(), cache, pardal(arm))
        r.aoMudarPosicao(-8.05, -34.95, amplo = false)
        advanceUntilIdle()
        assertEquals(listOf("pA"), r.perigos.value.map { it.id })
    }

    @Test fun `Pardal nao cobre e segue o fluxo antigo`() = runTest {
        val arm = PardalArmazenamento(dir)
        gravarUf(arm, "SP", base, listOf(ponto("pS", -23.5, -46.6)), bbox = listOf(-25.0, -48.0, -22.0, -44.0))
        val src = FonteOverpass(); val cache = MemCache()
        val r = montar(src, cache, pardal(arm))
        r.aoMudarPosicao(-8.05, -34.95, amplo = false)
        advanceUntilIdle()
        assertEquals(1, src.buscados.size)
        assertEquals(listOf("o${src.buscados[0].ix}_${src.buscados[0].iy}"), r.perigos.value.map { it.id })
        assertEquals(1, cache.dados.size)
    }

    @Test fun `UF vencida nao cobre e cai no Overpass`() = runTest {
        val arm = PardalArmazenamento(dir)
        gravarUf(arm, "PE", base - 15L * 24 * 3600 * 1000, listOf(ponto("pA", -8.05, -34.95)))
        val src = FonteOverpass()
        val r = montar(src, MemCache(), pardal(arm))
        r.aoMudarPosicao(-8.05, -34.95, amplo = false)
        advanceUntilIdle()
        assertEquals(1, src.buscados.size)
    }

    @Test fun `pardal desligado usa so o Overpass`() = runTest {
        val arm = PardalArmazenamento(dir)
        gravarUf(arm, "PE", base, listOf(ponto("pA", -8.05, -34.95)))
        val src = FonteOverpass()
        val r = montar(src, MemCache(), pardal(arm), ativo = { false })
        r.aoMudarPosicao(-8.05, -34.95, amplo = false)
        advanceUntilIdle()
        assertEquals(1, src.buscados.size)
        assertTrue(r.perigos.value.none { it.id == "pA" })
    }

    @Test fun `Pardal nao depende da rede mesmo com o Overpass falhando`() = runTest {
        val arm = PardalArmazenamento(dir)
        gravarUf(arm, "PE", base, listOf(ponto("pA", -8.05, -34.95)))
        val falha = object : PerigoSource { override suspend fun buscar(tile: TileId): List<PerigoPonto> = throw IOException("sem rede") }
        val r = montar(falha, MemCache(), pardal(arm))
        r.aoMudarPosicao(-8.05, -34.95, amplo = true)
        advanceUntilIdle()
        assertEquals(9, r.quadradosEmMemoria())
        assertTrue(r.perigos.value.any { it.id == "pA" })
    }

    @Test fun `atualizacao troca o conteudo de um quadrado ja carregado do Overpass`() = runTest {
        val arm = PardalArmazenamento(dir)
        val local = pardal(arm)
        val r = montar(FonteOverpass(), MemCache(), local)
        r.aoMudarPosicao(-8.05, -34.95, amplo = false)
        advanceUntilIdle()
        assertTrue(r.perigos.value.single().id.startsWith("o"))
        gravarUf(arm, "PE", base + currentTime, listOf(ponto("pA", -8.05, -34.95)))
        local.invalidar("PE")
        r.aoPardalAtualizado()
        advanceUntilIdle()
        assertEquals(listOf("pA"), r.perigos.value.map { it.id })
    }

    @Test fun `atualizacao de UF ja do Pardal troca o conteudo do quadrado`() = runTest {
        val arm = PardalArmazenamento(dir)
        gravarUf(arm, "PE", base, listOf(ponto("pA", -8.05, -34.95)))
        val local = pardal(arm)
        val r = montar(FonteOverpass(), MemCache(), local)
        r.aoMudarPosicao(-8.05, -34.95, amplo = false)
        advanceUntilIdle()
        assertEquals(listOf("pA"), r.perigos.value.map { it.id })
        gravarUf(arm, "PE", base + currentTime, listOf(ponto("pB", -8.06, -34.96)))
        local.invalidar("PE")
        r.aoPardalAtualizado()
        advanceUntilIdle()
        assertEquals(listOf("pB"), r.perigos.value.map { it.id })
    }

    @Test fun `atualizacao tira da fila de rede os quadrados que o Pardal passou a cobrir`() = runTest {
        val arm = PardalArmazenamento(dir)
        val local = pardal(arm)
        val src = FonteOverpass()
        val r = montar(src, MemCache(), local)
        r.aoMudarPosicao(-8.05, -34.95, amplo = true) // 9 quadrados na fila de rede
        advanceTimeBy(50)
        gravarUf(arm, "PE", base + currentTime, listOf(ponto("pA", -8.05, -34.95)))
        local.invalidar("PE")
        r.aoPardalAtualizado()
        advanceUntilIdle()
        assertTrue(src.buscados.size < 9, "buscou ${src.buscados.size}")
        assertTrue(r.perigos.value.any { it.id == "pA" })
        assertTrue(r.ocioso())
    }

    @Test fun `teto de memoria vale tambem para quadrados do Pardal`() = runTest {
        val arm = PardalArmazenamento(dir)
        gravarUf(arm, "PE", base, listOf(ponto("pA", -8.05, -34.95)))
        val r = montar(FonteOverpass(), MemCache(), pardal(arm), max = 5)
        r.aoMudarPosicao(-8.05, -34.95, amplo = true)
        advanceUntilIdle()
        assertEquals(9, r.quadradosEmMemoria()) // os 9 da posição são protegidos
        r.aoMudarPosicao(-8.45, -35.45, amplo = true)
        advanceUntilIdle()
        assertEquals(9, r.quadradosEmMemoria())
    }
}
