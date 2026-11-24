package app.hunter.nav.perigos

import app.hunter.model.Geo
import app.hunter.nav.LatLon
import app.hunter.nav.Route
import kotlin.math.roundToInt

/** O que o cartão do mapa mostra: o próximo perigo, a distância (arredondada a 10 m) e, em radar, o limite. */
data class AlertaPerigo(
    val perigo: PerigoPonto,
    val distanciaM: Int,
    val limiteKmh: Int?,
    /** Radar com limite conhecido e velocidade atual acima dele (só texto, sem multa na tela). */
    val acimaDoLimite: Boolean,
)

data class ResultadoAviso(val alerta: AlertaPerigo?, val falas: List<String>)

/**
 * Decide o que mostrar e falar, sem Android. Cada limiar de [Perigo.limiaresM] dispara UMA vez por perigo por passagem;
 * o perigo ultrapassado não volta, e a "passagem" só é esquecida quando a pessoa se afasta mais de [ESQUECER_M] dele
 * (aí, se voltar, avisa de novo). Se vários limiares são cruzados de uma vez (já entrou perto), fala só o mais curto.
 */
class AvisadorDePerigos {
    private class Estado(val ponto: PerigoPonto) {
        val falados = HashSet<Int>()
        var passado = false
    }

    private val estados = HashMap<String, Estado>()
    private var alvoId: String? = null

    fun reset() {
        estados.clear()
        alvoId = null
    }

    /**
     * @param perigos já filtrados pelas categorias ligadas.
     * @param rota/[alongM] com rota ativa e confiável; nulos no modo livre.
     */
    fun atualizar(
        aqui: LatLon,
        rumoGraus: Double?,
        velocidadeMps: Double,
        rota: Route?,
        alongM: Double?,
        perigos: List<PerigoPonto>,
    ): ResultadoAviso {
        val adiante = PerigosAdiante.adiante(aqui, rumoGraus, velocidadeMps, rota, alongM, perigos, alvoId)
        val idsAdiante = adiante.mapTo(HashSet()) { it.perigo.id }

        // O alvo anterior sumiu de "à frente": só conta como ultrapassado se ficou ATRÁS (projeção negativa na rota, ou mais de
        // 90º do rumo com velocidade confiável). Parar no sinal, rumo nulo ou GPS ruim não apagam nem "passam" o perigo.
        alvoId?.let { id ->
            val st = estados[id]
            if (st != null && id !in idsAdiante && PerigosAdiante.ultrapassou(st.ponto, aqui, rumoGraus, velocidadeMps, rota, alongM)) st.passado = true
        }
        // Esquece passagens de perigos que ficaram longe (voltar depois avisa de novo).
        estados.values.removeAll { it.ponto.id !in idsAdiante && Geo.distanceM(aqui.lat, aqui.lon, it.ponto.lat, it.ponto.lon) > ESQUECER_M }

        val alvo = adiante.firstOrNull { a ->
            estados[a.perigo.id]?.passado != true && a.distanciaM <= a.perigo.tipo.alcanceM
        }
        if (alvo == null) {
            alvoId = null
            return ResultadoAviso(null, emptyList())
        }
        val p = alvo.perigo
        alvoId = p.id
        val st = estados.getOrPut(p.id) { Estado(p) }

        val velKmh = velocidadeMps * 3.6
        val acima = (p.tipo == Perigo.RADAR || p.tipo == Perigo.RADAR_DE_TRECHO) && p.limiteKmh != null && velKmh > p.limiteKmh + MARGEM_KMH
        val alerta = AlertaPerigo(p, (alvo.distanciaM / 10).roundToInt() * 10, p.limiteKmh, acima)

        val limiares = p.tipo.limiaresM
        val cruzados = limiares.filter { alvo.distanciaM <= it && it !in st.falados }
        val falas = ArrayList<String>(1)
        // Sem rota a voz só fala com a via alinhada; o cartão aparece mesmo assim e o limiar fica para quando alinhar.
        val alinhado = rota != null || alvo.desvioLateralM <= PerigosAdiante.LIVRE_VOZ_DESVIO_M
        if (cruzados.isNotEmpty() && alinhado) {
            st.falados += limiares.filter { alvo.distanciaM <= it }
            falas += texto(p, alvo.distanciaM, acima, curto = p.tipo.curtoNoUltimo && cruzados.min() == limiares.min())
        }
        return ResultadoAviso(alerta, falas)
    }

    companion object {
        /** Depois de se afastar disto (m) a passagem é esquecida. */
        const val ESQUECER_M = 2_000.0
        /** Tolerância do GPS ao comparar velocidade com o limite (km/h). */
        const val MARGEM_KMH = 2.0

        /** Radar de trecho: "..., média de 80 km/h. Você está acima da média." "Radar à frente, 300 metros, limite 60. Você está acima do limite." (distância real, arredondada de 50 em 50 m) / "Radar adiante" (último limiar do radar). */
        fun texto(p: PerigoPonto, distanciaM: Double, acima: Boolean, curto: Boolean): String {
            val base = if (curto) "${p.tipo.nome} adiante" else "${p.tipo.nome} à frente, ${distanciaFalada(distanciaM)}"
            val trecho = p.tipo == Perigo.RADAR_DE_TRECHO
            val limite = p.limiteKmh?.takeIf { p.tipo == Perigo.RADAR || trecho }?.let { if (trecho) ", média de $it km/h" else ", limite $it" }.orEmpty()
            val aviso = if (acima) (if (trecho) ". Você está acima da média." else ". Você está acima do limite.") else "."
            return base + limite + aviso
        }
    }
}
