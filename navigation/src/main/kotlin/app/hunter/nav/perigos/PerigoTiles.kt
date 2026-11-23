package app.hunter.nav.perigos

import app.hunter.nav.LatLon
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Quadrado fixo de [TAMANHO_GRAUS] graus (~11 km) onde os perigos são buscados e guardados em cache.
 * O índice é inteiro (grade global), então a consulta ao servidor usa coordenadas arredondadas e nunca a posição exata.
 */
data class TileId(val ix: Int, val iy: Int) {
    val sul: Double get() = iy / ESCALA - 90.0
    val norte: Double get() = (iy + 1) / ESCALA - 90.0
    val oeste: Double get() = ix / ESCALA - 180.0
    val leste: Double get() = (ix + 1) / ESCALA - 180.0

    companion object {
        const val ESCALA = 10.0
        const val TAMANHO_GRAUS = 1.0 / ESCALA

        fun de(lat: Double, lon: Double) = TileId(floor((lon + 180.0) * ESCALA).toInt(), floor((lat + 90.0) * ESCALA).toInt())

        /** O quadrado de [lat],[lon] e os 8 vizinhos (3x3), o do meio primeiro. */
        fun aoRedor(lat: Double, lon: Double): List<TileId> {
            val c = de(lat, lon)
            val out = ArrayList<TileId>(9)
            out += c
            for (dy in -1..1) for (dx in -1..1) if (dx != 0 || dy != 0) out += TileId(c.ix + dx, c.iy + dy)
            return out
        }

        /**
         * Quadrados dos próximos [comprimentoM] m da rota a partir de [alongM] (a janela que fica carregada na navegação),
         * na ordem em que a rota passa. Limitado a [limite].
         */
        fun daJanela(rota: app.hunter.nav.Route, alongM: Double, comprimentoM: Double = 25_000.0, limite: Int = 40): List<TileId> {
            val cum = rota.cumulativeM
            if (rota.shape.size < 2) return emptyList()
            val ini = alongM.coerceIn(0.0, cum.last())
            val fim = (ini + comprimentoM).coerceAtMost(cum.last())
            val pts = ArrayList<LatLon>()
            pts += rota.pointAt(ini)
            for (i in rota.shape.indices) if (cum[i] > ini && cum[i] < fim) pts += rota.shape[i]
            pts += rota.pointAt(fim)
            return aoLongoDe(pts, limite)
        }

        /**
         * Quadrados tocados pelo traçado, na ordem em que a rota passa (os do começo primeiro). Cada ponto amostrado
         * (a cada ~4 km no máximo) também inclui os vizinhos a ~65 m, para pegar perigos colados na divisa.
         * Limitado a [limite] quadrados (rotas enormes: só o começo).
         */
        fun aoLongoDe(shape: List<LatLon>, limite: Int = 80): List<TileId> {
            if (shape.isEmpty()) return emptyList()
            val out = LinkedHashSet<TileId>()
            fun add(lat: Double, lon: Double) {
                val m = 0.0006
                out += de(lat, lon)
                out += de(lat + m, lon + m); out += de(lat + m, lon - m); out += de(lat - m, lon + m); out += de(lat - m, lon - m)
            }
            add(shape[0].lat, shape[0].lon)
            for (i in 1 until shape.size) {
                val a = shape[i - 1]
                val b = shape[i]
                val passos = max(1, (max(kotlin.math.abs(b.lat - a.lat), kotlin.math.abs(b.lon - a.lon)) / 0.04).toInt() + 1)
                for (k in 1..passos) {
                    val t = k.toDouble() / passos
                    add(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
                }
                if (out.size >= limite) break
            }
            return out.take(limite)
        }
    }
}

/** Quanto tempo o cache vale (30 dias). Vencido ainda serve sem internet; com internet é buscado de novo. */
const val VALIDADE_CACHE_MS = 30L * 24 * 60 * 60 * 1000

/** Recuo exponencial entre falhas seguidas: 1 min, 2, 4... até 30 min. */
fun recuoMs(falhasSeguidas: Int): Long {
    if (falhasSeguidas <= 0) return 0
    val min = 60_000L
    return min(30 * min, min shl min(falhasSeguidas - 1, 5))
}
