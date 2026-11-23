package app.hunter.nav.perigos

import app.hunter.nav.LatLon
import app.hunter.nav.Route
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Um perigo à frente e a distância até ele (m): pela rota quando há rota, em linha reta quando não há. */
data class AlvoPerigo(val perigo: PerigoPonto, val distanciaM: Double)

/**
 * Quais perigos estão à frente. Puro (sem Android, sem rede), chamado ~1 vez por segundo.
 *
 * Com rota ativa: os que estão ADIANTE NO TRAÇADO até [ROTA_ALCANCE_M] e a até [ROTA_DESVIO_M] do traçado.
 * Sem rota (modo livre/gravando): os dentro de [LIVRE_ALCANCE_M] e a ±[LIVRE_ANGULO]º do rumo, só com velocidade
 * acima de [VELOCIDADE_MIN_MPS]. O perigo que já era o alvo ([alvoAtualId]) tem folga maior (histerese) para não piscar.
 * Perigo cuja `direction` aponta para o outro sentido (ou câmera cruzando a via) é ignorado.
 */
object PerigosAdiante {
    const val ROTA_ALCANCE_M = 1500.0
    const val ROTA_DESVIO_M = 30.0
    const val ROTA_DESVIO_HISTERESE_M = 40.0
    const val LIVRE_ALCANCE_M = 600.0
    const val LIVRE_ALCANCE_HISTERESE_M = 650.0
    const val LIVRE_ANGULO = 35.0
    const val LIVRE_ANGULO_HISTERESE = 50.0
    const val VELOCIDADE_MIN_MPS = 3.0

    private const val M_POR_GRAU = 111_194.9
    /** `direction` mais de 100º fora do sentido de quem anda = sentido contrário. */
    private const val SENTIDO_MAX_DIF = 100.0
    /** Câmera: só o eixo importa; mais de 60º fora do eixo da via ela mira outra rua. */
    private const val EIXO_MAX_DIF = 60.0

    /**
     * Perigos adiante, do mais perto ao mais longe.
     * @param rota traçado e [alongM] (distância já percorrida nele); se nulo, usa o modo livre com [rumoGraus] e [velocidadeMps].
     */
    fun adiante(
        aqui: LatLon,
        rumoGraus: Double?,
        velocidadeMps: Double,
        rota: Route?,
        alongM: Double?,
        perigos: List<PerigoPonto>,
        alvoAtualId: String? = null,
    ): List<AlvoPerigo> =
        if (rota != null && alongM != null) naRota(aqui, rota, alongM, perigos, alvoAtualId)
        else livre(aqui, rumoGraus, velocidadeMps, perigos, alvoAtualId)

    private fun livre(aqui: LatLon, rumo: Double?, vel: Double, perigos: List<PerigoPonto>, alvoAtualId: String?): List<AlvoPerigo> {
        if (rumo == null || vel <= VELOCIDADE_MIN_MPS) return emptyList()
        val kx = cos(Math.toRadians(aqui.lat))
        val out = ArrayList<AlvoPerigo>()
        for (p in perigos) {
            val atual = p.id == alvoAtualId
            val alcance = if (atual) LIVRE_ALCANCE_HISTERESE_M else LIVRE_ALCANCE_M
            val dx = (p.lon - aqui.lon) * kx * M_POR_GRAU
            val dy = (p.lat - aqui.lat) * M_POR_GRAU
            if (abs(dx) > alcance || abs(dy) > alcance) continue
            val d = sqrt(dx * dx + dy * dy)
            if (d > alcance || d < 1.0) continue
            val rumoAoPerigo = (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
            if (difAngular(rumo, rumoAoPerigo) > (if (atual) LIVRE_ANGULO_HISTERESE else LIVRE_ANGULO)) continue
            if (!vale(p, rumo)) continue
            out += AlvoPerigo(p, d)
        }
        return out.sortedBy { it.distanciaM }
    }

    private fun naRota(aqui: LatLon, rota: Route, along: Double, perigos: List<PerigoPonto>, alvoAtualId: String?): List<AlvoPerigo> {
        val shape = rota.shape
        val cum = rota.cumulativeM
        if (shape.size < 2) return emptyList()
        // Janela de segmentos: de um pouco antes de [along] até o alcance (+ folga).
        val ini = max(0, primeiroSegmentoApos(cum, along - 60.0))
        val fim = min(shape.size - 2, primeiroSegmentoApos(cum, along + ROTA_ALCANCE_M + 60.0))
        if (ini > fim) return emptyList()
        val kx = cos(Math.toRadians(aqui.lat))
        val raioGrau = (ROTA_ALCANCE_M + 100.0) / M_POR_GRAU
        val out = ArrayList<AlvoPerigo>()
        for (p in perigos) {
            if (abs(p.lat - aqui.lat) > raioGrau || abs(p.lon - aqui.lon) * kx > raioGrau) continue
            // Procura, na janela, o trecho do traçado a até [maxDev] do perigo e o mais perto ADIANTE de quem anda: em rota
            // que passa duas vezes pela mesma rua (ida e volta), não confunde o trecho de volta com o de ida.
            val maxDev = if (p.id == alvoAtualId) ROTA_DESVIO_HISTERESE_M else ROTA_DESVIO_M
            var d = Double.MAX_VALUE
            for (i in ini..fim) {
                val a = shape[i]
                val b = shape[i + 1]
                val ax = (a.lon - p.lon) * kx * M_POR_GRAU
                val ay = (a.lat - p.lat) * M_POR_GRAU
                val dx = (b.lon - a.lon) * kx * M_POR_GRAU
                val dy = (b.lat - a.lat) * M_POR_GRAU
                val len2 = dx * dx + dy * dy
                val t = if (len2 <= 1e-9) 0.0 else ((-ax * dx) + (-ay * dy)).div(len2).coerceIn(0.0, 1.0)
                val px = ax + t * dx
                val py = ay + t * dy
                if (sqrt(px * px + py * py) > maxDev) continue
                val dist = cum[i] + t * (cum[i + 1] - cum[i]) - along
                if (dist < 0.0 || dist > ROTA_ALCANCE_M || dist >= d) continue
                if (!vale(p, rumoDoSegmento(a, b))) continue
                d = dist
            }
            if (d == Double.MAX_VALUE) continue
            out += AlvoPerigo(p, d)
        }
        return out.sortedBy { it.distanciaM }
    }

    /** Menor índice de segmento cujo fim está além de [alvoM] (busca binária em [cum]); limitado ao último segmento. */
    private fun primeiroSegmentoApos(cum: DoubleArray, alvoM: Double): Int {
        var lo = 0
        var hi = cum.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] < alvoM) lo = mid + 1 else hi = mid
        }
        return max(0, lo - 1)
    }

    /** O perigo vale para quem anda com [rumoViaGraus]? Sem `direction` ou sem rumo, vale. */
    fun vale(p: PerigoPonto, rumoViaGraus: Double?): Boolean {
        val dir = p.direcaoGraus ?: return true
        val rumo = rumoViaGraus ?: return true
        val dif = difAngular(rumo, dir)
        return if (p.tipo.soEixo) min(dif, 180.0 - dif) <= EIXO_MAX_DIF else dif <= SENTIDO_MAX_DIF
    }

    /** Diferença entre dois rumos, 0..180. */
    fun difAngular(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    private fun rumoDoSegmento(a: LatLon, b: LatLon): Double {
        val dx = (b.lon - a.lon) * cos(Math.toRadians((a.lat + b.lat) / 2))
        val dy = b.lat - a.lat
        if (dx == 0.0 && dy == 0.0) return 0.0
        return (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
    }
}
