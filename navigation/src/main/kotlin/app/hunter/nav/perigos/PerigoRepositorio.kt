package app.hunter.nav.perigos

import app.hunter.nav.HttpFailure
import app.hunter.nav.LatLon
import app.hunter.nav.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Perigos em memória, alimentados por quadrados fixos: cache em disco (válido por [VALIDADE_CACHE_MS]) e, quando falta
 * ou venceu, consulta ao [source].
 *
 * - No máximo UMA consulta de rede por vez (um único trabalhador esvazia a fila) e pelo menos [intervaloMinMs] entre elas.
 * - Falha de rede (inclusive `remark`, 429, 502, 504): recuo exponencial global ([recuoMs], ou o Retry-After do servidor se maior)
 *   e nada é gravado no cache; enquanto isso só o cache (mesmo vencido) é usado.
 * - Quadrado vazio também é cacheado. Nunca é chamado pelo movimento do mapa: só por mudança de quadrado da posição,
 *   prévia de rota (primeiros quadrados da rota escolhida) e janela de ~25 km à frente na navegação.
 * - Fila e quadrados em memória são protegidos pela mesma trava ([lock]); só o trabalhador mexe na rede.
 */
class PerigoRepositorio(
    private val source: PerigoSource,
    private val cache: PerigoCacheStore,
    private val scope: CoroutineScope,
    private val agora: () -> Long = System::currentTimeMillis,
    private val intervaloMinMs: Long = 2_000,
    private val maxQuadrados: Int = 150,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()

    // Tudo abaixo (até [ultimaRede]) só é lido/escrito dentro de synchronized(lock).
    private val fila = ArrayDeque<TileId>()
    private val naFila = HashSet<TileId>()
    private var rodando = false
    private val carregados = LinkedHashMap<TileId, QuadradoEmCache>()
    private var protegidosPosicao: Set<TileId> = emptySet()
    private var protegidosJanela: Set<TileId> = emptySet()
    private var ultimoCentro: TileId? = null

    // Só o trabalhador (uma coroutine por vez) mexe nestes.
    private var falhasSeguidas = 0
    private var bloqueadoAte = Long.MIN_VALUE / 2
    // /2: evita estouro ao somar o intervalo e subtrair o relógio (com MIN_VALUE puro o 1º `delay` seria de ~292 milhões de anos).
    private var ultimaRede = Long.MIN_VALUE / 2

    private val _perigos = MutableStateFlow<List<PerigoPonto>>(emptyList())
    /** Todos os perigos dos quadrados carregados (as categorias desligadas são filtradas por quem usa). */
    val perigos: StateFlow<List<PerigoPonto>> = _perigos.asStateFlow()

    /** Fila vazia e trabalhador parado (útil em testes). */
    fun ocioso(): Boolean = synchronized(lock) { !rodando && fila.isEmpty() }

    internal fun estado(): String = synchronized(lock) { "rodando=$rodando fila=${fila.size} naFila=${naFila.size} mem=${carregados.size}" }

    /** Quantos quadrados estão em memória. */
    fun quadradosEmMemoria(): Int = synchronized(lock) { carregados.size }

    /** Posição mudou: se o quadrado é outro, carrega os 3x3 ao redor (na frente da fila). */
    fun aoMudarPosicao(lat: Double, lon: Double) {
        val c = TileId.de(lat, lon)
        val ao = TileId.aoRedor(lat, lon)
        synchronized(lock) {
            if (c == ultimoCentro) return
            ultimoCentro = c
            protegidosPosicao = ao.toSet()
        }
        pedir(ao, urgente = true)
    }

    /** Prévia da rota escolhida: só os primeiros [limite] quadrados dela (as alternativas não carregam nada). */
    fun aoPreviaDeRota(shape: List<LatLon>, limite: Int = QUADRADOS_PREVIA) {
        val tiles = TileId.aoLongoDe(shape, limite)
        synchronized(lock) { protegidosJanela = tiles.toSet() }
        pedir(tiles, urgente = false)
    }

    /** Navegando: carrega os quadrados dos próximos [JANELA_M] m à frente de [alongM] (chamar de novo ao avançar). */
    fun aoNavegar(rota: Route, alongM: Double) {
        val tiles = TileId.daJanela(rota, alongM)
        synchronized(lock) { protegidosJanela = tiles.toSet() }
        pedir(tiles, urgente = false)
    }

    /** Tenta de novo os quadrados ao redor da última posição (o recuo de falha ainda vale). */
    fun tentarDeNovo() {
        val c = synchronized(lock) { ultimoCentro } ?: return
        pedir(TileId.aoRedor(c.sul + TileId.TAMANHO_GRAUS / 2, c.oeste + TileId.TAMANHO_GRAUS / 2), urgente = true)
    }

    /** Insere na fila ([urgente] vai para a frente, mantendo a ordem). Quadrados já carregados e válidos são ignorados. */
    fun pedir(tiles: List<TileId>, urgente: Boolean) {
        synchronized(lock) {
            val t = agora()
            val precisam = tiles.filter { !(carregados[it]?.valido(t) ?: false) }
            if (urgente) {
                // Os que já esperavam atrás (rota) passam para a frente.
                val set = precisam.toSet()
                fila.removeAll { it in set }
                precisam.asReversed().forEach { fila.addFirst(it) }
            } else {
                fila.addAll(precisam.filter { it !in naFila })
            }
            naFila.addAll(precisam)
            if (fila.isEmpty() || rodando) return
            rodando = true
        }
        // Só uma chamada chega aqui por vez (quem virou `rodando`); o trabalhador solta a marca ao esvaziar a fila.
        scope.launch { processar() }
    }

    /** Próximo da fila; ao esvaziar, solta o trabalhador na mesma seção crítica (não perde um pedido que chega agora). */
    private fun proximo(): TileId? = synchronized(lock) {
        val t = fila.removeFirstOrNull()
        if (t == null) rodando = false else naFila.remove(t)
        t
    }

    private suspend fun processar() {
        try {
            while (true) {
                val tile = proximo() ?: return
                var q = synchronized(lock) { carregados[tile] }
                if (q == null) {
                    q = withContext(io) { cache.ler(tile) }
                    if (q != null) guardar(tile, q)
                }
                if (q != null && q.valido(agora())) continue
                // Falta ou venceu: só vai à rede fora do recuo de falha.
                if (agora() < bloqueadoAte) continue
                val espera = ultimaRede + intervaloMinMs - agora()
                if (espera > 0) delay(espera)
                ultimaRede = agora()
                try {
                    val lista = source.buscar(tile)
                    val novo = QuadradoEmCache(agora(), lista)
                    withContext(io) { cache.gravar(tile, novo) }
                    guardar(tile, novo)
                    falhasSeguidas = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Nada vai para o cache; o recuo vale o maior entre o exponencial e o Retry-After do servidor.
                    falhasSeguidas++
                    bloqueadoAte = agora() + maxOf(recuoMs(falhasSeguidas), (e as? HttpFailure)?.retryAfterMs ?: 0L)
                }
            }
        } catch (e: Throwable) {
            // Saída anormal (cancelamento, erro inesperado): solta o trabalhador para um próximo pedido poder reiniciar.
            synchronized(lock) { rodando = false }
            throw e
        }
    }

    private fun guardar(tile: TileId, q: QuadradoEmCache) {
        val lista = synchronized(lock) {
            carregados.remove(tile)
            carregados[tile] = q
            if (carregados.size > maxQuadrados) {
                val it = carregados.keys.iterator()
                while (carregados.size > maxQuadrados && it.hasNext()) {
                    val k = it.next()
                    if (k !in protegidosPosicao && k !in protegidosJanela) it.remove()
                }
            }
            carregados.values.flatMap { it.perigos }
        }
        _perigos.value = lista
    }

    companion object {
        /** Na prévia: no máximo os primeiros quadrados da rota escolhida. */
        const val QUADRADOS_PREVIA = 10
        /** Navegando: quanto da rota à frente fica carregado (m). */
        const val JANELA_M = 25_000.0
    }
}
