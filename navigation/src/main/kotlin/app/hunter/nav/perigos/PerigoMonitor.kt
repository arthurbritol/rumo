package app.hunter.nav.perigos

import app.hunter.model.TrackPoint
import app.hunter.nav.LatLon
import app.hunter.nav.NavUiState
import app.hunter.nav.UrlConnectionHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Estado dos avisos visível para a UI e o serviço (mesmo processo → singleton, como o [app.hunter.nav.NavStatus]). */
object PerigoStatus {
    private val _alerta = MutableStateFlow<AlertaPerigo?>(null)
    /** Próximo perigo à frente (cartão do mapa); nulo quando não há. */
    val alerta: StateFlow<AlertaPerigo?> = _alerta.asStateFlow()
    fun publicar(a: AlertaPerigo?) { _alerta.value = a }

    private val _categorias = MutableStateFlow(CategoriaPerigo.values().toSet())
    /** Categorias ligadas (padrão: todas). A UI de configurações grava aqui e persiste nas preferências. */
    val categorias: StateFlow<Set<CategoriaPerigo>> = _categorias.asStateFlow()
    fun definirCategorias(c: Set<CategoriaPerigo>) { _categorias.value = c }

    @Volatile private var repo: PerigoRepositorio? = null
    /** Nulo até [iniciar] (a Application chama); sem ele, tudo vira no-op. */
    val repositorio: PerigoRepositorio? get() = repo

    /** Cria o repositório com cache em [dirCache] e o Overpass como fonte. Chamar uma vez, na Application. */
    fun iniciar(dirCache: File) {
        if (repo != null) return
        val cache = ArquivoPerigoCache(dirCache)
        runCatching { cache.limpar(System.currentTimeMillis()) }
        repo = PerigoRepositorio(
            source = OverpassPerigoSource(UrlConnectionHttp(connectTimeoutMs = 6_000, readTimeoutMs = 30_000, attempts = 1)),
            cache = cache,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }
}

/**
 * Roda a checagem de proximidade a ~1 Hz no fluxo de posição que o serviço já tem (nenhum listener de GPS novo):
 * pede quadrados ao repositório (mudança de quadrado e rota nova), calcula o alvo e devolve o que falar.
 */
class PerigoMonitor(
    private val repo: PerigoRepositorio?,
    private val categorias: () -> Set<CategoriaPerigo> = { PerigoStatus.categorias.value },
    private val publicar: (AlertaPerigo?) -> Unit = PerigoStatus::publicar,
    private val agora: () -> Long = System::currentTimeMillis,
    private val perigos: () -> List<PerigoPonto> = { repo?.perigos?.value.orEmpty() },
) {
    private val avisador = AvisadorDePerigos()
    private var ultimoCalculo = Long.MIN_VALUE / 2
    private var ultimaTentativa = agora()
    private var versaoRota = -1
    private var estavaAtivo = false

    private var listaOrigem: List<PerigoPonto>? = null
    private var listaCategorias: Set<CategoriaPerigo>? = null
    private var listaFiltrada: List<PerigoPonto> = emptyList()

    /** @return frases a falar (pode ser vazio). */
    fun onPoint(p: TrackPoint, nav: NavUiState): List<String> {
        val t = agora()
        if (t - ultimoCalculo < INTERVALO_MS) return emptyList()
        ultimoCalculo = t

        repo?.aoMudarPosicao(p.latitude, p.longitude)
        if (t - ultimaTentativa >= RETENTAR_MS) { ultimaTentativa = t; repo?.tentarDeNovo() }
        val route = nav.route
        if (nav.active && route != null && nav.routeVersion != versaoRota) {
            versaoRota = nav.routeVersion
            repo?.aoCalcularRota(route.shape)
        }
        if (nav.active != estavaAtivo) { estavaAtivo = nav.active; avisador.reset() }

        val acc = p.accuracyM
        val lista = filtrados(perigos())
        if (lista.isEmpty() || (acc != null && acc > PRECISAO_MAX_M)) {
            publicar(null)
            return emptyList()
        }
        val progress = nav.progress
        val naRota = nav.active && route != null && progress != null && !progress.offRoute && !nav.rerouting && !nav.arrived
        val r = avisador.atualizar(
            aqui = LatLon(p.latitude, p.longitude),
            rumoGraus = p.bearingDeg?.toDouble(),
            velocidadeMps = (p.speedMps ?: 0f).toDouble(),
            rota = if (naRota) route else null,
            alongM = if (naRota) progress?.alongM else null,
            perigos = lista,
        )
        publicar(r.alerta)
        return r.falas
    }

    /** Fim da viagem: limpa o cartão e a memória de "já avisei". */
    fun encerrar() {
        avisador.reset()
        publicar(null)
    }

    private fun filtrados(todos: List<PerigoPonto>): List<PerigoPonto> {
        val cats = categorias()
        if (todos !== listaOrigem || cats != listaCategorias) {
            listaOrigem = todos
            listaCategorias = cats
            listaFiltrada = todos.filter { it.tipo.categoria in cats }
        }
        return listaFiltrada
    }

    private companion object {
        const val INTERVALO_MS = 900L
        const val RETENTAR_MS = 5 * 60_000L
        const val PRECISAO_MAX_M = 100f
    }
}
