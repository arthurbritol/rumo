package app.hunter.nav.perigos

import app.hunter.nav.LatLon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * - Falha de rede: recuo exponencial global ([recuoMs]); enquanto isso só o cache (mesmo vencido) é usado.
 * - Quadrado vazio também é cacheado. Nunca é chamado pelo movimento do mapa: só por mudança de quadrado da posição
 *   e pelo cálculo de rota.
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
    private val fila = ArrayDeque<TileId>()
    private val naFila = HashSet<TileId>()
    private var trabalhador: Job? = null

    // Só o trabalhador escreve; ordem de inserção = ordem de saída quando passa de [maxQuadrados].
    private val carregados = LinkedHashMap<TileId, QuadradoEmCache>()
    @Volatile private var protegidos: Set<TileId> = emptySet()
    private var falhasSeguidas = 0
    private var bloqueadoAte = Long.MIN_VALUE
    private var ultimaRede = Long.MIN_VALUE

    private val _perigos = MutableStateFlow<List<PerigoPonto>>(emptyList())
    /** Todos os perigos dos quadrados carregados (as categorias desligadas são filtradas por quem usa). */
    val perigos: StateFlow<List<PerigoPonto>> = _perigos.asStateFlow()

    @Volatile private var ultimoCentro: TileId? = null

    /** Posição mudou: se o quadrado é outro, carrega os 3x3 ao redor (na frente da fila). */
    fun aoMudarPosicao(lat: Double, lon: Double) {
        val c = TileId.de(lat, lon)
        if (c == ultimoCentro) return
        ultimoCentro = c
        val ao = TileId.aoRedor(lat, lon)
        protegidos = ao.toSet()
        pedir(ao, urgente = true)
    }

    /** Rota calculada: carrega os quadrados tocados pelo traçado (atrás dos da posição atual). */
    fun aoCalcularRota(shape: List<LatLon>) = pedir(TileId.aoLongoDe(shape), urgente = false)

    /** Tenta de novo os quadrados ao redor da última posição (o recuo de falha ainda vale). */
    fun tentarDeNovo() {
        val c = ultimoCentro ?: return
        pedir(TileId.aoRedor(c.sul + TileId.TAMANHO_GRAUS / 2, c.oeste + TileId.TAMANHO_GRAUS / 2), urgente = true)
    }

    /** Insere na fila ([urgente] vai para a frente, mantendo a ordem). Quadrados já carregados e válidos são ignorados. */
    fun pedir(tiles: List<TileId>, urgente: Boolean) {
        synchronized(fila) {
            val precisam = tiles.filter { !(carregados[it]?.valido(agora()) ?: false) }
            if (urgente) {
                // Os que já esperavam atrás (rota) passam para a frente.
                val set = precisam.toSet()
                fila.removeAll { it in set }
                precisam.asReversed().forEach { fila.addFirst(it) }
            } else {
                fila.addAll(precisam.filter { it !in naFila })
            }
            naFila.addAll(precisam)
            if (fila.isEmpty() || trabalhador?.isActive == true) return
            trabalhador = scope.launch { processar() }
        }
    }

    /** Próximo da fila; ao esvaziar, solta o trabalhador na mesma seção crítica (evita perder um pedido que chega agora). */
    private fun proximo(): TileId? = synchronized(fila) {
        val t = fila.removeFirstOrNull()
        if (t == null) trabalhador = null else naFila.remove(t)
        t
    }

    private suspend fun processar() {
        try {
            while (true) {
                val tile = proximo() ?: break
                var q = carregados[tile]
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
                    falhasSeguidas++
                    bloqueadoAte = agora() + recuoMs(falhasSeguidas)
                }
            }
        } finally {
            synchronized(fila) { trabalhador = null }
        }
    }

    private fun guardar(tile: TileId, q: QuadradoEmCache) {
        carregados.remove(tile)
        carregados[tile] = q
        if (carregados.size > maxQuadrados) {
            val it = carregados.keys.iterator()
            while (carregados.size > maxQuadrados && it.hasNext()) {
                if (it.next() !in protegidos) it.remove()
            }
        }
        _perigos.value = carregados.values.flatMap { it.perigos }
    }
}
