package app.hunter.nav.perigos

import app.hunter.nav.LatLon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TileIdTest {
    @Test fun `grade de 0,1 grau`() {
        val t = TileId.de(-8.0431, -34.9265)
        assertEquals(TileId(1450, 819), t)
        assertEquals(-8.1, t.sul, 1e-9)
        assertEquals(-8.0, t.norte, 1e-9)
        assertEquals(-35.0, t.oeste, 1e-9)
        assertEquals(-34.9, t.leste, 1e-9)
        assertEquals(t, TileId.de(-8.0999, -34.9999))
    }

    @Test fun `3x3 comeca pelo do meio`() {
        val ao = TileId.aoRedor(-8.05, -34.95)
        assertEquals(9, ao.size)
        assertEquals(TileId.de(-8.05, -34.95), ao[0])
        assertEquals(9, ao.toSet().size)
        assertTrue(TileId(ao[0].ix + 1, ao[0].iy - 1) in ao)
    }

    @Test fun `quadrados tocados por um traco longo, na ordem`() {
        // 30 km a leste na latitude 0: ~3 quadrados de largura.
        val shape = listOf(LatLon(0.05, 0.05), LatLon(0.05, 0.05 + 0.27))
        val ts = TileId.aoLongoDe(shape)
        assertEquals(TileId.de(0.05, 0.05), ts.first())
        assertTrue(ts.map { it.ix }.toSet().containsAll(listOf(1800, 1801, 1802, 1803)))
        assertTrue(ts.all { it.iy in 899..900 }) // a linha está a meio do quadrado: nada de vizinhos de cima/baixo
        assertEquals(emptyList(), TileId.aoLongoDe(emptyList()))
    }

    @Test fun `traco colado na divisa inclui o vizinho`() {
        val ts = TileId.aoLongoDe(listOf(LatLon(0.0501, 0.1000001), LatLon(0.0601, 0.1000001)))
        assertTrue(TileId.de(0.05, 0.0999) in ts && TileId.de(0.05, 0.1001) in ts)
    }

    @Test fun `rota enorme e limitada`() {
        val shape = listOf(LatLon(-10.0, -40.0), LatLon(-10.0, -30.0))
        assertEquals(80, TileId.aoLongoDe(shape).size)
        assertEquals(10, TileId.aoLongoDe(shape, limite = 10).size)
    }

    @Test fun `recuo exponencial com teto de 30 min`() {
        assertEquals(0, recuoMs(0))
        assertEquals(60_000, recuoMs(1))
        assertEquals(120_000, recuoMs(2))
        assertEquals(240_000, recuoMs(3))
        assertEquals(30 * 60_000L, recuoMs(10))
    }
}

class ArquivoPerigoCacheTest {
    private fun tmp(): File = java.nio.file.Files.createTempDirectory("perigos").toFile()

    @Test fun `grava e le de volta`() {
        val dir = tmp()
        val c = ArquivoPerigoCache(dir)
        val t = TileId(1450, 819)
        val ps = listOf(
            PerigoPonto("n1", Perigo.RADAR, -8.04, -34.92, 60, 90.0),
            PerigoPonto("w2", Perigo.LOMBADA, -8.05, -34.93),
        )
        c.gravar(t, QuadradoEmCache(1_000, ps))
        val lido = c.ler(t)!!
        assertEquals(1_000, lido.buscadoEm)
        assertEquals(ps, lido.perigos)
        // outra instância (reabrir o app) lê o mesmo
        assertEquals(ps, ArquivoPerigoCache(dir).ler(t)!!.perigos)
    }

    @Test fun `quadrado vazio tambem e cacheado`() {
        val c = ArquivoPerigoCache(tmp())
        c.gravar(TileId(1, 2), QuadradoEmCache(5, emptyList()))
        assertEquals(emptyList(), c.ler(TileId(1, 2))!!.perigos)
        assertNull(c.ler(TileId(1, 3)))
    }

    @Test fun `arquivo corrompido ou de outra versao conta como ausente`() {
        val dir = tmp()
        val c = ArquivoPerigoCache(dir)
        val sub = File(dir, "perigos-v${ArquivoPerigoCache.VERSAO}").also { it.mkdirs() }
        File(sub, "1_1.json").writeText("{ não é json")
        File(sub, "2_2.json").writeText("""{"v":99,"em":1,"p":[]}""")
        assertNull(c.ler(TileId(1, 1)))
        assertNull(c.ler(TileId(2, 2)))
    }

    @Test fun `tipo desconhecido no arquivo e ignorado`() {
        val dir = tmp()
        val c = ArquivoPerigoCache(dir)
        val sub = File(dir, "perigos-v${ArquivoPerigoCache.VERSAO}").also { it.mkdirs() }
        File(sub, "3_3.json").writeText("""{"v":1,"em":7,"p":[{"id":"n1","t":"OVNI","lat":1.0,"lon":2.0},{"id":"n2","t":"RADAR","lat":1.0,"lon":2.0,"lim":50}]}""")
        assertEquals(listOf("n2"), c.ler(TileId(3, 3))!!.perigos.map { it.id })
    }

    @Test fun `validade de 30 dias`() {
        val q = QuadradoEmCache(1_000, emptyList())
        assertTrue(q.valido(1_000 + VALIDADE_CACHE_MS))
        assertFalse(q.valido(1_001 + VALIDADE_CACHE_MS))
        assertFalse(q.valido(500)) // relógio voltou: não confia
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PerigoRepositorioTest {
    private class MemCache : PerigoCacheStore {
        val dados = HashMap<TileId, QuadradoEmCache>()
        override fun ler(tile: TileId) = dados[tile]
        override fun gravar(tile: TileId, q: QuadradoEmCache) { dados[tile] = q }
    }

    private class FakeSource(val scope: () -> TestScope) : PerigoSource {
        val buscados = mutableListOf<TileId>()
        val instantes = mutableListOf<Long>()
        var falha = false
        var simultaneas = 0
        var maxSimultaneas = 0
        var porTile: (TileId) -> List<PerigoPonto> = { listOf(PerigoPonto("n${it.ix}_${it.iy}", Perigo.LOMBADA, it.sul + 0.05, it.oeste + 0.05)) }
        override suspend fun buscar(tile: TileId): List<PerigoPonto> {
            buscados += tile
            instantes += scope().currentTime
            simultaneas++
            maxSimultaneas = maxOf(maxSimultaneas, simultaneas)
            try {
                delay(300)
                if (falha) throw IOException("sem rede")
                return porTile(tile)
            } finally { simultaneas-- }
        }
    }

    private val base = 1_700_000_000_000L

    private fun TestScope.repo(src: PerigoSource, cache: PerigoCacheStore = MemCache(), intervalo: Long = 2_000) =
        PerigoRepositorio(
            src, cache, CoroutineScope(StandardTestDispatcher(testScheduler)), agora = { base + currentTime },
            intervaloMinMs = intervalo, io = StandardTestDispatcher(testScheduler),
        )

    @Test fun `mudar de quadrado carrega o 3x3 e cacheia`() = runTest {
        val src = FakeSource { this }
        val cache = MemCache()
        val r = repo(src, cache)
        r.aoMudarPosicao(-8.05, -34.95)
        advanceUntilIdle()
        assertEquals(9, src.buscados.size)
        assertEquals(TileId.de(-8.05, -34.95), src.buscados[0]) // o do meio primeiro
        assertEquals(9, cache.dados.size)
        assertEquals(9, r.perigos.value.size)
    }

    @Test fun `mesmo quadrado nao consulta de novo`() = runTest {
        val src = FakeSource { this }
        val r = repo(src)
        r.aoMudarPosicao(-8.05, -34.95)
        advanceUntilIdle()
        val n = src.buscados.size
        r.aoMudarPosicao(-8.051, -34.951) // mesmo quadrado
        r.aoMudarPosicao(-8.07, -34.97)
        advanceUntilIdle()
        assertEquals(n, src.buscados.size)
    }

    @Test fun `andar para o quadrado vizinho so busca os 3 novos`() = runTest {
        val src = FakeSource { this }
        val r = repo(src)
        r.aoMudarPosicao(-8.05, -34.95)
        advanceUntilIdle()
        r.aoMudarPosicao(-8.05, -34.85) // um quadrado a leste
        advanceUntilIdle()
        assertEquals(9 + 3, src.buscados.size)
    }

    @Test fun `cache valido nao vai a rede e quadrado vazio tambem vale`() = runTest {
        val src = FakeSource { this }
        val cache = MemCache()
        val t = TileId.de(-8.05, -34.95)
        for (x in TileId.aoRedor(-8.05, -34.95)) cache.dados[x] = QuadradoEmCache(base - 1_000, if (x == t) listOf(PerigoPonto("n1", Perigo.RADAR, -8.05, -34.95)) else emptyList())
        val r = repo(src, cache)
        r.aoMudarPosicao(-8.05, -34.95)
        advanceUntilIdle()
        assertEquals(0, src.buscados.size)
        assertEquals(listOf("n1"), r.perigos.value.map { it.id })
    }

    @Test fun `cache vencido busca de novo e substitui`() = runTest {
        val src = FakeSource { this }
        val cache = MemCache()
        val t = TileId.de(-8.05, -34.95)
        cache.dados[t] = QuadradoEmCache(base - VALIDADE_CACHE_MS - 1, listOf(PerigoPonto("velho", Perigo.RADAR, -8.05, -34.95)))
        val r = repo(src, cache)
        r.pedir(listOf(t), urgente = true)
        advanceUntilIdle()
        assertEquals(listOf(t), src.buscados)
        assertEquals(listOf("n${t.ix}_${t.iy}"), r.perigos.value.map { it.id })
        assertTrue(cache.dados[t]!!.valido(base + currentTime))
    }

    @Test fun `sem internet usa o cache vencido`() = runTest {
        val src = FakeSource { this }.also { it.falha = true }
        val cache = MemCache()
        val t = TileId.de(-8.05, -34.95)
        cache.dados[t] = QuadradoEmCache(base - VALIDADE_CACHE_MS - 1, listOf(PerigoPonto("velho", Perigo.RADAR, -8.05, -34.95)))
        val r = repo(src, cache)
        r.pedir(listOf(t), urgente = true)
        advanceUntilIdle()
        assertEquals(listOf("velho"), r.perigos.value.map { it.id })
        assertEquals(1, src.buscados.size) // tentou uma vez, falhou, ficou com o que tinha
        assertEquals(base - VALIDADE_CACHE_MS - 1, cache.dados[t]!!.buscadoEm)
    }

    @Test fun `falha entra em recuo e nao martela o servidor`() = runTest {
        val src = FakeSource { this }.also { it.falha = true }
        val r = repo(src)
        r.aoMudarPosicao(-8.05, -34.95)
        advanceUntilIdle()
        assertEquals(1, src.buscados.size) // a 1ª falha trava o resto (9 quadrados) em vez de 9 tentativas
        r.pedir(TileId.aoRedor(-8.05, -34.95), urgente = true)
        advanceUntilIdle()
        assertEquals(1, src.buscados.size) // ainda no recuo de 1 min
        advanceTimeBy(61_000)
        src.falha = false
        r.pedir(TileId.aoRedor(-8.05, -34.95), urgente = true)
        advanceUntilIdle()
        assertEquals(1 + 9, src.buscados.size)
        assertEquals(9, r.perigos.value.size)
    }

    @Test fun `uma consulta por vez e intervalo minimo`() = runTest {
        val src = FakeSource { this }
        val r = repo(src, intervalo = 2_000)
        r.aoMudarPosicao(-8.05, -34.95)
        r.aoCalcularRota(listOf(LatLon(-8.05, -34.95), LatLon(-8.05, -34.2)))
        advanceUntilIdle()
        assertEquals(1, src.maxSimultaneas)
        assertTrue(src.buscados.size > 9)
        assertTrue(src.instantes.zipWithNext().all { (a, b) -> b - a >= 2_000 }, src.instantes.toString())
        assertEquals(src.buscados.size, src.buscados.toSet().size) // sem repetir quadrado
    }

    @Test fun `quadrados da posicao furam a fila da rota`() = runTest {
        val src = FakeSource { this }
        val r = repo(src)
        r.aoCalcularRota(listOf(LatLon(-8.05, -34.95), LatLon(-8.05, -34.2)))
        r.aoMudarPosicao(-8.05, -34.95)
        advanceUntilIdle()
        assertEquals(TileId.de(-8.05, -34.95), src.buscados.first())
    }

    @Test fun `nao deixa o numero de quadrados em memoria passar do limite`() = runTest {
        val src = FakeSource { this }
        val r = PerigoRepositorio(src, MemCache(), CoroutineScope(StandardTestDispatcher(testScheduler)), agora = { base + currentTime }, intervaloMinMs = 0, maxQuadrados = 12, io = StandardTestDispatcher(testScheduler))
        r.aoMudarPosicao(-8.05, -34.95)
        r.aoCalcularRota(listOf(LatLon(-8.05, -34.95), LatLon(-8.05, -34.4)))
        advanceUntilIdle()
        assertTrue(r.perigos.value.size <= 12)
        assertNotNull(r.perigos.value.firstOrNull { it.id == "n${TileId.de(-8.05, -34.95).ix}_${TileId.de(-8.05, -34.95).iy}" })
    }
}
