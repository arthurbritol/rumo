package app.hunter.app.perigos

import app.hunter.nav.Route
import app.hunter.nav.perigos.PardalArmazenamento
import app.hunter.nav.perigos.PardalSource
import app.hunter.nav.perigos.ResultadoPardal
import app.hunter.nav.perigos.TileId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Situação do Pardal em uma UF, para a tela de configurações. */
data class PardalUfStatus(
    val uf: String,
    /** Instante (ms) do último download/renovação; null = nunca baixada. */
    val baixadaEm: Long? = null,
    val contagem: Int = 0,
    /** Baixada há no máximo 14 dias (é o que faz o Pardal valer no mapa e nos avisos). */
    val recente: Boolean = false,
    val baixando: Boolean = false,
    val erro: String? = null,
    /** Espaço ocupado em disco (csv + meta); 0 se não baixada. */
    val bytes: Long = 0,
)

/**
 * Pedido de confirmação de dados móveis guardado pelo [PardalGatilho] (a UI mostra e chama confirmar ou dispensar).
 * Só nasce de ação manual (Atualizar agora / Escolher estados); [ufs] junta todas as UFs pedidas, [bytes] a soma dos tamanhos
 * conhecidos (null = nenhum conhecido).
 */
data class PardalPedidoDadosMoveis(val ufs: List<String>, val bytes: Long?)

/**
 * Estado por UF do Pardal. Reconstrói a parte "em disco" do [PardalArmazenamento] e mantém "baixando"/"erro" em memória.
 */
class PardalStatus(
    private val armazenamento: PardalArmazenamento,
    private val agora: () -> Long = System::currentTimeMillis,
) {
    private val _ufs = MutableStateFlow<Map<String, PardalUfStatus>>(emptyMap())
    /** Por UF (sigla): só as que foram baixadas, estão baixando ou falharam. */
    val ufs: StateFlow<Map<String, PardalUfStatus>> = _ufs.asStateFlow()

    private val baixando = HashSet<String>()
    private val erros = HashMap<String, String>()
    private val lock = Any()

    /** Relê o disco (chamar depois de gravar uma UF e na partida). */
    fun atualizar() {
        val discos = armazenamento.ufsBaixadas().mapNotNull { armazenamento.lerMeta(it) }.associateBy { it.uf }
        synchronized(lock) {
            val siglas = discos.keys + baixando + erros.keys
            _ufs.value = siglas.associateWith { uf ->
                val m = discos[uf]
                PardalUfStatus(
                    uf = uf,
                    baixadaEm = m?.baixadoEm,
                    contagem = m?.total ?: 0,
                    bytes = if (m != null) armazenamento.bytesEmDisco(uf) else 0L,
                    recente = m != null && agora() - m.baixadoEm <= PardalSource.RECENTE_MS,
                    baixando = uf in baixando,
                    erro = erros[uf],
                )
            }
        }
    }

    fun baixando(uf: String, sim: Boolean) {
        synchronized(lock) { if (sim) { baixando += uf; erros -= uf } else baixando -= uf }
        atualizar()
    }

    fun erro(uf: String, motivo: String?) {
        synchronized(lock) { if (motivo == null) erros -= uf else erros[uf] = motivo }
        atualizar()
    }
}

/**
 * Decide quando baixar o Pardal: ao entrar em um quadrado novo de 0,1 grau (todas as UFs cujo bbox contém a posição; a verificação de
 * cada UF é limitada a uma por [VERIFICACAO_MS], o que também pega o Wi-Fi que voltou) e ao abrir a prévia de rota / iniciar a navegação
 * (todas as UFs da rota). Baixa a UF que falta ou está vencida (mais de [VENCE_MS]); um download por vez.
 *
 * Gatilhos AUTOMÁTICOS (posição/rota) fora do Wi-Fi com "só Wi-Fi" nunca perguntam nada: apenas esperam (a UF nem é marcada como
 * verificada, então a próxima mudança de quadrado tenta de novo). Só a ação manual ([baixarAgora]) gera [pedido].
 * Dependências injetadas (sem Android).
 */
class PardalGatilho(
    private val scope: CoroutineScope,
    /** Pardal habilitado: preferência ligada, alguma categoria de perigo ligada e fora do modo economia de dados. */
    private val ativo: () -> Boolean,
    /** `!pardalSoWifi`: pode usar dados móveis sem perguntar. */
    private val permitirDadosMoveis: () -> Boolean,
    private val emWifi: () -> Boolean,
    /** TODAS as UFs cujo bbox contém o ponto (vazia se fora ou se não há bbox conhecido). */
    private val ufDoPonto: (Double, Double) -> List<String>,
    private val ufsDaRota: (Route) -> List<String>,
    /** Há bbox conhecidos (catálogo carregado agora ou guardado em disco). */
    private val catalogoCarregado: () -> Boolean,
    private val carregarCatalogo: suspend () -> Boolean,
    /** Instante do último download da UF; null = não baixada. */
    private val baixadoEm: (String) -> Long?,
    private val atualizar: suspend (String, Boolean) -> ResultadoPardal,
    private val status: PardalStatus? = null,
    private val agora: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val verificadoEm = HashMap<String, Long>()
    private var ultimoQuadrado: TileId? = null
    private val naFila = HashSet<String>()
    private val trava = Mutex()

    private val _pedido = MutableStateFlow<PardalPedidoDadosMoveis?>(null)
    /** Download parado por falta de Wi-Fi numa ação manual: a UI pergunta ao usuário e chama [confirmarDadosMoveis] ou [dispensarPedido]. */
    val pedido: StateFlow<PardalPedidoDadosMoveis?> = _pedido.asStateFlow()

    /** Posição válida do GPS (chamar à vontade: só trabalha quando o quadrado de 0,1 grau muda). */
    fun aoPosicao(lat: Double, lon: Double) {
        if (!ativo()) return
        val q = TileId.de(lat, lon)
        synchronized(lock) {
            if (q == ultimoQuadrado) return
            ultimoQuadrado = q
        }
        scope.launch { ufsDe(lat, lon).forEach { verificar(it) } }
    }

    /** Prévia de rota aberta ou navegação iniciada: todas as UFs da rota. */
    fun aoRota(rota: Route) {
        if (!ativo()) return
        scope.launch {
            // O catálogo é necessário para saber a UF antes do primeiro download.
            if (!catalogoCarregado() && !tentarCatalogo()) return@launch
            ufsDaRota(rota).forEach { verificar(it) }
        }
    }

    /** O usuário aceitou usar dados móveis: baixa TODAS as UFs do pedido pendente. */
    fun confirmarDadosMoveis() {
        val p = _pedido.value ?: return
        _pedido.value = null
        for (uf in p.ufs) {
            if (!reservar(uf)) continue
            scope.launch { executar(uf, permitirMoveis = true, interativo = true) }
        }
    }

    /** Pedido do usuário (Perfil): baixa/atualiza a UF agora. Fora do Wi-Fi com "só Wi-Fi" ligado, vira [pedido]. Toque repetido não duplica. */
    fun baixarAgora(uf: String) {
        if (!reservar(uf)) return
        scope.launch { executar(uf, permitirMoveis = permitirDadosMoveis(), interativo = true) }
    }

    fun dispensarPedido() { _pedido.value = null }

    private suspend fun ufsDe(lat: Double, lon: Double): List<String> {
        ufDoPonto(lat, lon).takeIf { it.isNotEmpty() }?.let { return it }
        if (catalogoCarregado() || !tentarCatalogo()) return emptyList()
        return ufDoPonto(lat, lon)
    }

    /** Catálogo (pequeno) no máximo uma vez por hora e só onde baixar é permitido. */
    private suspend fun tentarCatalogo(): Boolean {
        if (!permitirDadosMoveis() && !emWifi()) return false
        val t = agora()
        synchronized(lock) {
            val ult = verificadoEm[CATALOGO]
            if (ult != null && t - ult < VERIFICACAO_MS) return false
            verificadoEm[CATALOGO] = t
        }
        return try { carregarCatalogo() } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
    }

    private suspend fun verificar(uf: String) {
        val t = agora()
        val permitir = permitirDadosMoveis()
        synchronized(lock) {
            val ult = verificadoEm[uf]
            if (ult != null && t - ult < VERIFICACAO_MS) return
            val em = baixadoEm(uf)
            // Em dia: só marca a verificação (nada a baixar).
            if (em != null && t - em <= VENCE_MS) { verificadoEm[uf] = t; return }
            // Automático, precisa baixar, mas sem Wi-Fi e só-Wi-Fi: espera (não pergunta e não marca como verificada).
            if (!permitir && !emWifi()) return
            verificadoEm[uf] = t
        }
        if (reservar(uf)) executar(uf, permitirMoveis = permitir, interativo = false)
    }

    /** Marca a UF como na fila/baixando (aparece já na tela); false = já está na fila. */
    private fun reservar(uf: String): Boolean {
        if (!synchronized(lock) { naFila.add(uf) }) return false
        status?.baixando(uf, true)
        return true
    }

    private suspend fun executar(uf: String, permitirMoveis: Boolean, interativo: Boolean) {
        try {
            trava.withLock {
                when (val r = atualizar(uf, permitirMoveis)) {
                    is ResultadoPardal.Atualizada, is ResultadoPardal.JaEmDia -> status?.erro(uf, null)
                    is ResultadoPardal.Falhou -> status?.erro(uf, r.motivo)
                    is ResultadoPardal.PrecisaConfirmarDadosMoveis -> if (interativo) _pedido.update { juntar(it, uf, r.bytes) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status?.erro(uf, e.message ?: e.javaClass.simpleName)
        } finally {
            synchronized(lock) { naFila.remove(uf) }
            status?.baixando(uf, false)
        }
    }

    private fun juntar(antigo: PardalPedidoDadosMoveis?, uf: String, bytes: Long?): PardalPedidoDadosMoveis {
        if (antigo == null) return PardalPedidoDadosMoveis(listOf(uf), bytes)
        if (uf in antigo.ufs) return antigo
        val soma = if (antigo.bytes == null && bytes == null) null else (antigo.bytes ?: 0L) + (bytes ?: 0L)
        return PardalPedidoDadosMoveis(antigo.ufs + uf, soma)
    }

    companion object {
        /** Baixada há mais que isso = pede atualização (14 dias é o limite de "recente"). */
        const val VENCE_MS = 7L * 24 * 3600 * 1000
        const val VERIFICACAO_MS = 3_600_000L
        private const val CATALOGO = "#catalogo"
    }
}
