package app.hunter.nav.perigos

import app.hunter.nav.LatLon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    @Test fun `janela de 25 km so inclui os quadrados da frente`() {
        val rota = rotaLeste(100)
        val ts = TileId.daJanela(rota, 40_000.0)
        val ix = ts.map { it.ix }.toSet()
        assertEquals(TileId.de(0.0, 40_000.0 / 111_194.9), ts.first())
        assertTrue(ix.containsAll(listOf(1803, 1804, 1805)), ix.toString())
        assertTrue(ix.none { it < 1802 || it > 1806 }, ix.toString())
        // no começo e no fim da rota a janela é cortada
        assertTrue(TileId.daJanela(rota, 0.0).map { it.ix }.max() <= 1803)
        assertTrue(TileId.daJanela(rota, 99_000.0).map { it.ix }.min() >= 1807)
        assertTrue(TileId.daJanela(rota, 40_000.0, limite = 2).size == 2)
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
        assertTrue(q.valido(500)) // relógio um pouco atrasado não derruba o cache
        assertFalse(q.valido(1_000 - VALIDADE_CACHE_MS - 1)) // muito atrasado (data absurda): não confia
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
        var erro: Exception? = null
        var porTile: (TileId) -> List<PerigoPonto> = { listOf(PerigoPonto("n${it.ix}_${it.iy}", Perigo.LOMBADA, it.sul + 0.05, it.oeste + 0.05)) }
        override suspend fun buscar(tile: TileId): List<PerigoPonto> {
            buscados += tile
            instantes += scope().currentTime
            simultaneas++
            maxSimultaneas = maxOf(maxSimultaneas, simultaneas)
            try {
                delay(300)
                erro?.let { throw it }
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

    @Test fun `falha entra em recuo, nao martela o servidor e a fila retoma sozinha ao fim do recuo`() = runTest {
        val src = FakeSource { this }.also { it.falha = true }
        val r = repo(src)
        r.aoMudarPosicao(-8.05, -34.95)
        advanceTimeBy(10_000)
        assertEquals(1, src.buscados.size) // a 1ª falha trava a rede (não são 9 tentativas)...
        r.pedir(TileId.aoRedor(-8.05, -34.95), urgente = true)
        advanceTimeBy(10_000)
        assertEquals(1, src.buscados.size) // ...e continua travada no recuo de 1 min
        src.falha = false
        advanceUntilIdle() // o recuo acaba: os 8 quadrados guardados são buscados sem novo pedido
        // o pedido feito durante o recuo (inclui o que falhou) também ficou guardado
        assertEquals(1 + 9, src.buscados.size)
        assertEquals(9, r.perigos.value.size)
    }

    @Test fun `no recuo a posicao atual continua antes da rota na retomada`() = runTest {
        val src = FakeSource { this }.also { it.falha = true }
        val r = repo(src)
        val rota = listOf(LatLon(-8.05, -34.95), LatLon(-8.05, -34.55))
        r.aoPreviaDeRota(rota) // 1º da rota falha e abre o recuo
        advanceTimeBy(10_000)
        assertEquals(1, src.buscados.size)
        r.aoPreviaDeRota(rota) // rota inteira em fila durante o recuo
        r.aoMudarPosicao(-8.05, -34.45) // posição atual, num quadrado mais à frente (urgente)
        src.falha = false
        src.buscados.clear()
        advanceUntilIdle()
        // os 9 primeiros são exatamente o 3x3 da posição atual (urgente), antes de qualquer quadrado só da rota
        assertEquals(TileId.aoRedor(-8.05, -34.45).toSet(), src.buscados.take(9).toSet(), src.buscados.toString())
        assertTrue(src.buscados.size > 9) // e a rota também foi carregada, nada foi descartado
        assertEquals(src.buscados.size, src.buscados.toSet().size)
    }

    @Test fun `fora de viagem so o quadrado atual e depois amplia para 3x3`() = runTest {
        val src = FakeSource { this }
        val r = repo(src)
        r.aoMudarPosicao(-8.05, -34.95, amplo = false)
        advanceUntilIdle()
        assertEquals(1, src.buscados.size)
        r.aoMudarPosicao(-8.051, -34.951, amplo = false)
        advanceUntilIdle()
        assertEquals(1, src.buscados.size)
        r.aoMudarPosicao(-8.051, -34.951, amplo = true) // começou a gravar no mesmo quadrado
        advanceUntilIdle()
        assertEquals(9, src.buscados.size)
    }

    @Test fun `perigo repetido nos dois quadrados da divisa conta uma vez`() = runTest {
        val src = FakeSource { this }.also { it.porTile = { listOf(PerigoPonto("n1", Perigo.RADAR, -8.0, -34.9)) } }
        val r = repo(src)
        r.aoMudarPosicao(-8.05, -34.95)
        advanceUntilIdle()
        assertEquals(1, r.perigos.value.size)
    }

    @Test fun `uma consulta por vez e intervalo minimo`() = runTest {
        val src = FakeSource { this }
        val r = repo(src, intervalo = 2_000)
        r.aoMudarPosicao(-8.05, -34.95)
        r.aoPreviaDeRota(listOf(LatLon(-8.05, -34.95), LatLon(-8.05, -34.2)))
        advanceUntilIdle()
        assertEquals(1, src.maxSimultaneas)
        assertTrue(src.buscados.size > 9)
        assertTrue(src.instantes.zipWithNext().all { (a, b) -> b - a >= 2_000 }, src.instantes.toString())
        assertEquals(src.buscados.size, src.buscados.toSet().size) // sem repetir quadrado
    }

    @Test fun `quadrados da posicao furam a fila da rota`() = runTest {
        val src = FakeSource { this }
        val r = repo(src)
        r.aoPreviaDeRota(listOf(LatLon(-8.05, -34.95), LatLon(-8.05, -34.2)))
        r.aoMudarPosicao(-8.05, -34.95)
        advanceUntilIdle()
        assertEquals(TileId.de(-8.05, -34.95), src.buscados.first())
    }

    @Test fun `teto de memoria nao descarta os quadrados da posicao nem da janela ativa`() = runTest {
        val src = FakeSource { this }
        val r = PerigoRepositorio(src, MemCache(), CoroutineScope(StandardTestDispatcher(testScheduler)), agora = { base + currentTime }, intervaloMinMs = 0, maxQuadrados = 14, io = StandardTestDispatcher(testScheduler))
        r.aoMudarPosicao(-8.05, -34.95)
        r.aoPreviaDeRota(listOf(LatLon(-8.05, -34.95), LatLon(-8.05, -34.60)), limite = 3)
        advanceUntilIdle()
        val protegidos = TileId.aoRedor(-8.05, -34.95) + TileId.aoLongoDe(listOf(LatLon(-8.05, -34.95), LatLon(-8.05, -34.60)), 3)
        // muitos outros quadrados longe (ex.: o usuário olhou outra região): entram, mas o excesso sai dos NÃO protegidos
        r.pedir((0 until 10).map { TileId(100 + it, 100) }, urgente = false)
        advanceUntilIdle()
        assertTrue(r.quadradosEmMemoria() <= 14)
        val ids = r.perigos.value.map { it.id }.toSet()
        for (t in protegidos.toSet()) assertTrue("n${t.ix}_${t.iy}" in ids, "protegido $t foi descartado")
    }

    @Test fun `remark do Overpass e falha, nada vai para o cache e entra em recuo`() = runTest {
        val src = FakeSource { this }
        src.erro = OverpassRemark("runtime error: Query timed out")
        val cache = MemCache()
        val r = repo(src, cache)
        r.aoMudarPosicao(-8.05, -34.95)
        advanceTimeBy(10_000)
        assertTrue(cache.dados.isEmpty())
        assertTrue(r.perigos.value.isEmpty())
        assertEquals(1, src.buscados.size)
        r.pedir(TileId.aoRedor(-8.05, -34.95), urgente = true)
        advanceTimeBy(10_000)
        assertEquals(1, src.buscados.size) // em recuo
    }

    @Test fun `Retry-After maior que o recuo prevalece`() = runTest {
        val src = FakeSource { this }
        src.erro = app.hunter.nav.HttpFailure(429, "HTTP 429", 300_000)
        val r = repo(src)
        r.aoMudarPosicao(-8.05, -34.95)
        advanceTimeBy(10_000)
        assertEquals(1, src.buscados.size)
        src.erro = null
        advanceTimeBy(120_000) // já passou o recuo de 1 min, mas não os 5 min pedidos
        assertEquals(1, src.buscados.size)
        advanceTimeBy(200_000) // passou dos 5 min: os guardados retomam sozinhos
        advanceUntilIdle()
        assertEquals(9, src.buscados.size)
    }

    @Test fun `cenario A B C com o trabalhador terminando entre os pedidos mantem uma consulta por vez`() = runTest {
        val src = FakeSource { this }
        val r = repo(src, intervalo = 0)
        val a = TileId(10, 10); val b = TileId(11, 10); val c = TileId(12, 10)
        r.pedir(listOf(a), urgente = false)
        advanceTimeBy(100) // trabalhador no meio da consulta de A
        r.pedir(listOf(b), urgente = false) // chega com o trabalhador ocupado
        advanceTimeBy(250) // A termina e B começa (o trabalhador não esvaziou a fila ao sair)
        r.pedir(listOf(c), urgente = false)
        advanceUntilIdle()
        assertEquals(listOf(a, b, c), src.buscados)
        assertEquals(1, src.maxSimultaneas)
        assertTrue(r.ocioso())
        // depois de ocioso, um novo pedido reinicia o trabalhador
        r.pedir(listOf(TileId(13, 10)), urgente = false)
        advanceUntilIdle()
        assertEquals(4, src.buscados.size)
        assertEquals(1, src.maxSimultaneas)
    }

    @Test fun `janela da navegacao carrega so os proximos 25 km e e reposta ao avancar`() = runTest {
        val src = FakeSource { this }
        val r = repo(src, intervalo = 0)
        val rota = rotaLeste(100)
        r.aoNavegar(rota, 40_000.0)
        advanceUntilIdle()
        val ix = src.buscados.map { it.ix }.toSet()
        assertTrue(ix.containsAll(listOf(1803, 1804, 1805)), ix.toString())
        assertTrue(ix.none { it < 1802 || it > 1806 }, ix.toString())
        val n = src.buscados.size
        r.aoNavegar(rota, 52_000.0) // avançou: repõe só o que falta
        advanceUntilIdle()
        assertTrue(src.buscados.size > n && src.buscados.size == src.buscados.toSet().size)
        assertTrue(src.buscados.map { it.ix }.max() >= 1806)
    }

    @Test fun `previa carrega no maximo 10 quadrados`() = runTest {
        val src = FakeSource { this }
        val r = repo(src, intervalo = 0)
        r.aoPreviaDeRota(rotaLeste(100).shape)
        advanceUntilIdle()
        assertEquals(10, src.buscados.size)
    }
}

/** Corrida de verdade: várias threads pedindo, uma thread lendo, trabalhador em dispatcher multi-thread. */
class PerigoRepositorioConcorrenciaTest {
    private class MemCache : PerigoCacheStore {
        val dados = java.util.concurrent.ConcurrentHashMap<TileId, QuadradoEmCache>()
        override fun ler(tile: TileId) = dados[tile]
        override fun gravar(tile: TileId, q: QuadradoEmCache) { dados[tile] = q }
    }

    private class Fonte : PerigoSource {
        val simultaneas = java.util.concurrent.atomic.AtomicInteger()
        val maxSimultaneas = java.util.concurrent.atomic.AtomicInteger()
        val buscados = java.util.concurrent.ConcurrentLinkedQueue<TileId>()
        override suspend fun buscar(tile: TileId): List<PerigoPonto> {
            val n = simultaneas.incrementAndGet()
            maxSimultaneas.accumulateAndGet(n) { x, y -> maxOf(x, y) }
            buscados += tile
            try {
                delay(1)
                return listOf(PerigoPonto("n${tile.ix}_${tile.iy}", Perigo.LOMBADA, tile.sul + 0.05, tile.oeste + 0.05))
            } finally { simultaneas.decrementAndGet() }
        }
    }

    @Test fun `pedidos simultaneos de varias threads, uma consulta por vez, sem repetir e sem perder quadrado`() {
        val fonte = Fonte()
        val repo = PerigoRepositorio(fonte, MemCache(), CoroutineScope(Dispatchers.Default), intervaloMinMs = 0, maxQuadrados = 500)
        val pedidos = java.util.concurrent.ConcurrentHashMap.newKeySet<TileId>()
        val erro = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val parar = java.util.concurrent.atomic.AtomicBoolean(false)
        val leitor = Thread {
            try { while (!parar.get()) { repo.quadradosEmMemoria(); repo.perigos.value.size; repo.ocioso() } } catch (e: Throwable) { erro.set(e) }
        }
        leitor.start()
        val threads = (0 until 8).map { k ->
            Thread {
                try {
                    val rnd = java.util.Random(k.toLong())
                    repeat(60) {
                        val ts = (0 until 3).map { TileId(rnd.nextInt(25), rnd.nextInt(2)) }
                        pedidos += ts
                        if (rnd.nextBoolean()) repo.pedir(ts, urgente = rnd.nextBoolean())
                        else repo.aoMudarPosicao(ts[0].sul + 0.05, ts[0].oeste + 0.05).also { pedidos += TileId.aoRedor(ts[0].sul + 0.05, ts[0].oeste + 0.05) }
                        if (it % 10 == 0) Thread.sleep(2) // deixa o trabalhador esvaziar a fila de vez em quando
                    }
                } catch (e: Throwable) { erro.set(e) }
            }.also { it.start() }
        }
        threads.forEach { it.join() }
        val limite = System.currentTimeMillis() + 20_000
        while (!repo.ocioso() && System.currentTimeMillis() < limite) Thread.sleep(5)
        parar.set(true); leitor.join()
        assertNull(erro.get())
        assertTrue(repo.ocioso(), "trabalhador travado: ${repo.estado()}")
        assertEquals(1, fonte.maxSimultaneas.get(), "mais de uma consulta ao mesmo tempo")
        val buscados = fonte.buscados.toList()
        assertEquals(buscados.size, buscados.toSet().size, "quadrado buscado mais de uma vez")
        assertEquals(pedidos.toSet(), buscados.toSet(), "quadrado pedido e nunca carregado")
        assertEquals(pedidos.size, repo.perigos.value.size)
    }

    @Test fun `primeira consulta com relogio real e intervalo padrao nao espera (regressao do estouro de Long)`() {
        val fonte = Fonte()
        val repo = PerigoRepositorio(fonte, MemCache(), CoroutineScope(Dispatchers.Default)) // intervalo de 2 s, relógio do sistema
        repo.pedir(listOf(TileId(5, 5)), urgente = true)
        val limite = System.currentTimeMillis() + 1_500
        while (fonte.buscados.isEmpty() && System.currentTimeMillis() < limite) Thread.sleep(5)
        assertEquals(1, fonte.buscados.size, "a 1ª consulta deveria sair na hora")
    }
}
