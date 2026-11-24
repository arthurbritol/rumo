package app.hunter.nav.perigos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Nenhuma UF baixada cobre o quadrado: quem chama cai para o Overpass. */
class SemDadosPardal(message: String) : IOException(message)

/**
 * Perigos do Pardal por quadrado, a partir do [PardalArmazenamento]. Índice em memória por UF (por quadrado), carregado sob
 * demanda e só das UFs usadas (LRU de [maxUfs]). O índice guarda o `csvSha256` do meta de onde veio: um índice velho (carregado
 * por um [buscar] lento que terminou depois de [invalidar]) nunca é reaproveitado.
 *
 * Cobertura: os bbox de TODAS as UFs do catálogo ficam em disco ([PardalArmazenamento.lerBboxes]). O bbox de uma UF é grosseiro
 * (o de SP cobre o sul de MG), então [cobre] só vale quando TODAS as UFs cujo bbox toca o quadrado estão baixadas e recentes
 * (até [RECENTE_MS], 14 dias). Sem bbox conhecido (catálogo nunca carregado) ou quadrado fora de todos: não cobre (Overpass).
 * [buscar] usa o dado de qualquer UF baixada que toque o quadrado, mesmo vencido (reserva quando a rede falha).
 */
class PardalSource(
    private val armazenamento: PardalArmazenamento,
    private val agora: () -> Long = System::currentTimeMillis,
    private val maxUfs: Int = 3,
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) : PerigoSource {
    private class Indice(val csvSha256: String, val porQuadrado: Map<TileId, List<PerigoPonto>>)

    private val lock = Any()
    private val lru = object : LinkedHashMap<String, Indice>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Indice>) = size > maxUfs
    }

    /** UFs com índice em memória agora (para testes e diagnóstico). */
    fun ufsEmMemoria(): Set<String> = synchronized(lock) { lru.keys.toSet() }

    /** O app chama depois de gravar uma UF nova, para o índice em memória não ficar velho. */
    fun invalidar(uf: String) {
        synchronized(lock) { lru.remove(uf) }
    }

    private fun toca(b: List<Double>, tile: TileId) = b[0] <= tile.norte && b[2] >= tile.sul && b[1] <= tile.leste && b[3] >= tile.oeste

    /** Alguma UF baixada, e recente, cobre o quadrado, e nenhuma UF que o toca está faltando ou vencida. */
    fun cobre(tile: TileId): Boolean {
        val tocam = armazenamento.lerBboxes().filterValues { toca(it, tile) }.keys
        if (tocam.isEmpty()) return false
        val t = agora()
        return tocam.all { uf -> armazenamento.lerMeta(uf)?.let { t - it.baixadoEm <= RECENTE_MS } == true }
    }

    /** UFs baixadas cujo bbox (do catálogo, ou o do próprio meta) toca o quadrado. */
    private fun candidatas(tile: TileId): List<String> {
        val bboxes = armazenamento.lerBboxes()
        return armazenamento.ufsBaixadas().filter { uf ->
            val b = bboxes[uf] ?: armazenamento.lerMeta(uf)?.bbox?.takeIf { it.size == 4 }
            b != null && toca(b, tile)
        }
    }

    override suspend fun buscar(tile: TileId): List<PerigoPonto> = withContext(io) {
        val ufs = candidatas(tile)
        if (ufs.isEmpty()) throw SemDadosPardal("nenhuma UF do Pardal baixada cobre o quadrado ${tile.ix},${tile.iy}")
        val vistos = HashSet<String>()
        val out = ArrayList<PerigoPonto>()
        var algum = false
        for (uf in ufs) {
            val idx = indice(uf) ?: continue
            algum = true
            idx.porQuadrado[tile]?.forEach { if (vistos.add(it.id)) out += it }
        }
        if (!algum) throw SemDadosPardal("dados do Pardal ilegíveis para o quadrado ${tile.ix},${tile.iy}")
        out
    }

    private fun indice(uf: String): Indice? {
        val atual = armazenamento.lerMeta(uf)?.csvSha256
        synchronized(lock) { lru[uf]?.let { if (it.csvSha256 == atual) return it } }
        val (meta, pontos) = armazenamento.ler(uf) ?: return null
        val idx = Indice(meta.csvSha256, pontos.groupBy { TileId.de(it.lat, it.lon) })
        synchronized(lock) { lru[uf] = idx }
        return idx
    }

    companion object {
        const val RECENTE_MS = 14L * 24 * 3600 * 1000
    }
}
