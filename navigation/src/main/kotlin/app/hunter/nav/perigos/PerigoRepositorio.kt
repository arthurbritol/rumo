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
 * - Com [local]: quadrado coberto pelo Pardal vem dele (sem entrar na fila de rede, sem recuo/intervalo, sem gravar no cache
 *   em disco) e tem prioridade sobre o cache do Overpass; as duas fontes nunca se misturam no mesmo quadrado.
 *   [aoPardalAtualizado] reavalia os quadrados em memória e os da fila quando uma UF é gravada, removida ou o Pardal é
 *   ligado/desligado (quadrados que deixaram de ser cobertos saem da memória e voltam pelo caminho antigo).
 * - Reserva: sem cache para o quadrado e com o Overpass falhando ou em recuo, usa o Pardal mesmo vencido (se a UF está em disco).
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
    /** Fonte local (Pardal): quadrado coberto por UF baixada e recente vem dela, sem rede e sem cache em disco. */
    private val local: PardalSource? = null,
    /** false = nunca usa o [local] (só Overpass). */
    private val pardalAtivo: () -> Boolean = { true },
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
    private var ultimoAmplo = false
    // Quadrados que esperam o fim do recuo de falha (na ordem da fila: posição antes de rota) e a retomada agendada.
    private val aguardandoRede = LinkedHashSet<TileId>()
    private var retomadaAgendada = false
    // Quadrados em memória cujo conteúdo veio do Pardal, e classificações (Pardal x rede) em andamento.
    private val deLocal = HashSet<TileId>()
    private var classificando = 0

    // Só o trabalhador (uma coroutine por vez) mexe nestes.
    private var falhasSeguidas = 0
    private var bloqueadoAte = Long.MIN_VALUE / 2
    // /2: evita estouro ao somar o intervalo e subtrair o relógio (com MIN_VALUE puro o 1º `delay` seria de ~292 milhões de anos).
    private var ultimaRede = Long.MIN_VALUE / 2

    private val _perigos = MutableStateFlow<List<PerigoPonto>>(emptyList())
    /** Todos os perigos dos quadrados carregados (as categorias desligadas são filtradas por quem usa). */
    val perigos: StateFlow<List<PerigoPonto>> = _perigos.asStateFlow()

    /** Fila vazia e trabalhador parado (útil em testes). */
    fun ocioso(): Boolean = synchronized(lock) { !rodando && fila.isEmpty() && classificando == 0 }

    internal fun estado(): String = synchronized(lock) { "rodando=$rodando fila=${fila.size} naFila=${naFila.size} mem=${carregados.size}" }

    /** Quantos quadrados estão em memória. */
    fun quadradosEmMemoria(): Int = synchronized(lock) { carregados.size }

    /**
     * Posição mudou: se o quadrado é outro, carrega os 3x3 ao redor ([amplo], navegando/gravando) ou só o quadrado atual
     * (mapa aberto sem viagem), na frente da fila. Passar de estreito para amplo no mesmo quadrado completa o 3x3.
     */
    fun aoMudarPosicao(lat: Double, lon: Double, amplo: Boolean = true) {
        val c = TileId.de(lat, lon)
        val ao = if (amplo) TileId.aoRedor(lat, lon) else listOf(c)
        synchronized(lock) {
            if (c == ultimoCentro && (ultimoAmplo || !amplo)) return
            ultimoCentro = c
            ultimoAmplo = amplo
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
        val (c, amplo) = synchronized(lock) { ultimoCentro to ultimoAmplo }
        if (c == null) return
        val lat = c.sul + TileId.TAMANHO_GRAUS / 2
        val lon = c.oeste + TileId.TAMANHO_GRAUS / 2
        pedir(if (amplo) TileId.aoRedor(lat, lon) else listOf(c), urgente = true)
    }

    /**
     * Insere na fila ([urgente] vai para a frente, mantendo a ordem). Quadrados já carregados e válidos são ignorados.
     * Com [local] ativo, antes separa os que o Pardal cobre (carregados na hora, fora da fila de rede).
     */
    fun pedir(tiles: List<TileId>, urgente: Boolean) {
        val pardal = local?.takeIf { pardalAtivo() }
        if (pardal == null) {
            enfileirar(tiles, urgente)
            return
        }
        val precisam = synchronized(lock) {
            val t = agora()
            classificando++
            tiles.filter { !(carregados[it]?.valido(t) ?: false) }
        }
        if (precisam.isEmpty()) {
            synchronized(lock) { classificando-- }
            return
        }
        scope.launch(io) {
            try {
                val rede = ArrayList<TileId>()
                for (t in precisam) if (!carregarDoPardal(pardal, t)) rede += t
                enfileirar(rede, urgente)
            } finally {
                synchronized(lock) { classificando-- }
            }
        }
    }

    /** true se o Pardal cobre o quadrado e ele foi carregado dessa fonte. Falha de leitura = false (cai para a rede). */
    private suspend fun carregarDoPardal(pardal: PardalSource, tile: TileId): Boolean {
        return try {
            if (!pardal.cobre(tile)) return false
            guardar(tile, QuadradoEmCache(agora(), pardal.buscar(tile)), doPardal = true)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /**
     * O conjunto de dados do Pardal mudou (UF gravada ou removida, Pardal ligado/desligado). Quadrados que vinham do Pardal e deixaram
     * de ser cobertos saem da memória e voltam pelo caminho antigo (cache/Overpass); os em memória e os da fila que o Pardal agora
     * cobre passam a vir dele (os já carregados são substituídos no lugar, sem piscar). Chamar depois de [PardalSource.invalidar].
     */
    fun aoPardalAtualizado() {
        scope.launch(io) {
            val pardal = local?.takeIf { pardalAtivo() }
            val soltar = synchronized(lock) { deLocal.toList() }.filter { pardal == null || !pardal.cobre(it) }
            if (soltar.isNotEmpty()) {
                synchronized(lock) { soltar.forEach { if (it in deLocal) { carregados.remove(it); deLocal.remove(it) } } }
                publicar()
                pedir(soltar, urgente = false)
            }
            if (pardal == null) return@launch
            val alvos = synchronized(lock) { (carregados.keys + fila + aguardandoRede).toSet() }
            for (t in alvos) {
                if (!carregarDoPardal(pardal, t)) continue
                synchronized(lock) {
                    fila.remove(t)
                    naFila.remove(t)
                    aguardandoRede.remove(t)
                }
            }
        }
    }

    /** Reserva: o Pardal mesmo vencido, se a UF está em disco. true = o quadrado foi carregado dele. */
    private suspend fun reservaDoPardal(tile: TileId): Boolean {
        val pardal = local?.takeIf { pardalAtivo() } ?: return false
        return try {
            withContext(io) { guardar(tile, QuadradoEmCache(agora(), pardal.buscar(tile)), doPardal = true) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private fun enfileirar(tiles: List<TileId>, urgente: Boolean) {
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
                    // Se o Pardal assumiu o quadrado enquanto o cache era lido, o cache do Overpass não sobrescreve.
                    if (q != null) guardar(tile, q)
                }
                if (q != null && q.valido(agora())) continue
                // Quadrado do Pardal que venceu (30 dias) e não é mais coberto: o Overpass pode substituí-lo.
                synchronized(lock) { deLocal -= tile }
                // Falta ou venceu: só vai à rede fora do recuo de falha.
                if (agora() < bloqueadoAte) {
                    // Em recuo: não descarta; guarda na ordem e retoma sozinho quando o recuo acabar. Sem nada em memória,
                    // o Pardal vencido serve de reserva enquanto isso.
                    if (q == null && reservaDoPardal(tile)) continue
                    synchronized(lock) { aguardandoRede += tile }
                    agendarRetomada()
                    continue
                }
                val espera = ultimaRede + intervaloMinMs - agora()
                if (espera > 0) delay(espera)
                ultimaRede = agora()
                try {
                    val lista = source.buscar(tile)
                    val novo = QuadradoEmCache(agora(), lista)
                    // O Pardal pode ter assumido o quadrado durante a consulta (UF baixada agora): o resultado do Overpass é descartado.
                    val pardal = local?.takeIf { pardalAtivo() }
                    val assumido = synchronized(lock) { tile in deLocal } || (pardal != null && withContext(io) { carregarDoPardal(pardal, tile) })
                    if (!assumido) {
                        withContext(io) { cache.gravar(tile, novo) }
                        guardar(tile, novo)
                    }
                    falhasSeguidas = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Nada vai para o cache; o recuo vale o maior entre o exponencial e o Retry-After do servidor.
                    falhasSeguidas++
                    bloqueadoAte = agora() + maxOf(recuoMs(falhasSeguidas), (e as? HttpFailure)?.retryAfterMs ?: 0L)
                    if (q == null) reservaDoPardal(tile)
                }
            }
        } catch (e: Throwable) {
            // Saída anormal (cancelamento, erro inesperado): solta o trabalhador para um próximo pedido poder reiniciar.
            synchronized(lock) { rodando = false }
            throw e
        }
    }

    /** Um único despertador: ao fim do recuo recoloca os quadrados que esperavam (mantendo a ordem de prioridade). */
    private fun agendarRetomada() {
        val espera = synchronized(lock) {
            if (retomadaAgendada) return
            retomadaAgendada = true
            (bloqueadoAte - agora()).coerceAtLeast(0L) + 1
        }
        scope.launch {
            delay(espera)
            val lista = synchronized(lock) {
                retomadaAgendada = false
                // Posição atual antes da rota (a ordem de chegada ao recuo pode ter posto a rota na frente).
                aguardandoRede.partition { it in protegidosPosicao }.let { it.first + it.second }.also { aguardandoRede.clear() }
            }
            pedir(lista, urgente = false)
        }
    }

    /**
     * Põe o quadrado em memória. Conteúdo que NÃO é do Pardal não sobrescreve um quadrado que o Pardal assumiu (corrida entre a
     * leitura do cache/Overpass e a chegada do Pardal). Devolve false se foi ignorado por isso.
     */
    private fun guardar(tile: TileId, q: QuadradoEmCache, doPardal: Boolean = false): Boolean {
        val lista = synchronized(lock) {
            if (!doPardal && tile in deLocal && carregados[tile]?.valido(agora()) == true) return false
            carregados.remove(tile)
            carregados[tile] = q
            if (doPardal) deLocal += tile else deLocal -= tile
            if (carregados.size > maxQuadrados) {
                val it = carregados.keys.iterator()
                while (carregados.size > maxQuadrados && it.hasNext()) {
                    val k = it.next()
                    if (k !in protegidosPosicao && k !in protegidosJanela) { it.remove(); deLocal -= k }
                }
            }
            // distinctBy: perigo na divisa pode vir nos dois quadrados com o mesmo id.
            carregados.values.flatMap { it.perigos }.distinctBy { it.id }
        }
        _perigos.value = lista
        return true
    }

    private fun publicar() {
        _perigos.value = synchronized(lock) { carregados.values.flatMap { it.perigos }.distinctBy { it.id } }
    }

    companion object {
        /** Na prévia: no máximo os primeiros quadrados da rota escolhida. */
        const val QUADRADOS_PREVIA = 10
        /** Navegando: quanto da rota à frente fica carregado (m). */
        const val JANELA_M = 25_000.0
    }
}
