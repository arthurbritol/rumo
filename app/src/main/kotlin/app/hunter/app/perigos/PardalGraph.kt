package app.hunter.app.perigos

import android.app.Application
import android.content.Context
import app.hunter.nav.perigos.PardalArmazenamento
import app.hunter.nav.perigos.PardalAtualizador
import app.hunter.nav.perigos.PardalSource
import app.hunter.nav.perigos.PerigoStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Montagem do Pardal no app (uma por processo, criada na Application): armazenamento em `files/pardal-v1`, fonte local do
 * repositório de perigos, atualizador, estado por UF e gatilho de download. Escopo próprio e de vida do processo (nenhuma
 * coroutine presa a Activity/ViewModel; o gatilho só dispara trabalho curto e um download por vez).
 */
object PardalGraph {
    @Volatile var gatilho: PardalGatilho? = null
        private set
    @Volatile var status: PardalStatus? = null
        private set
    @Volatile var atualizador: PardalAtualizador? = null
        private set

    @Volatile private var armazenamento: PardalArmazenamento? = null
    @Volatile private var fonte: PardalSource? = null

    /** Apaga os dados da UF e invalida o índice em memória. */
    fun remover(uf: String) {
        armazenamento?.remover(uf)
        fonte?.invalidar(uf)
        status?.atualizar()
    }

    /** Cria tudo e também o repositório de perigos (com o Pardal como fonte local). Chamar uma vez, em `Application.onCreate`. */
    fun iniciar(app: Application, versaoApp: String) {
        if (gatilho != null) return
        val prefs = app.getSharedPreferences("app", Context.MODE_PRIVATE)
        val armazenamento = PardalArmazenamento(java.io.File(app.filesDir, "pardal-v1"))
        val fonte = PardalSource(armazenamento)
        val estado = PardalStatus(armazenamento)
        this.armazenamento = armazenamento
        this.fonte = fonte
        val rede = RedePoliticaAndroid(app)
        val repositorioPronto = { PerigoStatus.repositorio }

        PerigoStatus.iniciar(app.filesDir, versaoApp, local = fonte, pardalAtivo = { PerigoPrefs.pardalAtivo(prefs) })

        val atu = PardalAtualizador(
            armazenamento = armazenamento,
            rede = rede,
            // ASCII: o OkHttp rejeita acentos em cabeçalho.
            userAgent = "Rumo/$versaoApp (app de navegacao para motos; https://github.com/arthurbritol/rumo)",
            aoAtualizar = { uf ->
                fonte.invalidar(uf)
                estado.atualizar()
                repositorioPronto()?.aoPardalAtualizado()
            },
        )
        val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val g = PardalGatilho(
            scope = escopo,
            ativo = { PerigoPrefs.pardalAtivo(prefs) && PerigoStatus.categorias.value.isNotEmpty() && !rede.economiaDeDados() },
            permitirDadosMoveis = { !PerigoPrefs.pardalSoWifi(prefs) },
            emWifi = rede::emWifi,
            ufDoPonto = atu::ufsPara,
            ufsDaRota = atu::ufsParaRota,
            catalogoCarregado = atu::catalogoCarregado,
            carregarCatalogo = atu::carregarCatalogo,
            baixadoEm = { armazenamento.lerMeta(it)?.baixadoEm },
            atualizar = atu::atualizar,
            status = estado,
        )
        atualizador = atu
        status = estado
        gatilho = g
        PerigoStatus.aoPosicaoValida = g::aoPosicao
        escopo.launch { estado.atualizar() }
    }
}
