package app.hunter.nav.perigos

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Conteúdo de um quadrado em cache. Quadrado sem perigos também é guardado (lista vazia): não consulta de novo por 30 dias. */
data class QuadradoEmCache(val buscadoEm: Long, val perigos: List<PerigoPonto>) {
    /** Relógio um pouco atrasado não invalida (idade negativa vale até 30 dias de diferença); muito no futuro, sim. */
    fun valido(agora: Long) = agora - buscadoEm in -VALIDADE_CACHE_MS..VALIDADE_CACHE_MS
}

/** Onde os quadrados ficam guardados. Trocável nos testes. */
interface PerigoCacheStore {
    fun ler(tile: TileId): QuadradoEmCache?
    fun gravar(tile: TileId, q: QuadradoEmCache)
}

@Serializable
private class ArquivoQuadrado(
    @SerialName("v") val versao: Int,
    @SerialName("em") val buscadoEm: Long,
    @SerialName("p") val itens: List<ItemQuadrado>,
)

@Serializable
private class ItemQuadrado(
    val id: String,
    @SerialName("t") val tipo: String,
    val lat: Double,
    val lon: Double,
    @SerialName("lim") val limite: Int? = null,
    @SerialName("dir") val direcao: Double? = null,
)

/**
 * Um arquivo JSON por quadrado em [raiz] (`perigos-v1/{ix}_{iy}.json`). Versão no conteúdo: se mudar o formato, o arquivo
 * antigo é ignorado e o quadrado é buscado de novo (sem migração). Escrita atômica (arquivo temporário + renomear); arquivo
 * corrompido ou de outra versão conta como ausente. Não usa Room de propósito: é só cache descartável e dispensa migração de esquema.
 */
class ArquivoPerigoCache(raiz: File) : PerigoCacheStore {
    private val dir = File(raiz, "perigos-v$VERSAO")
    private val json = Json { ignoreUnknownKeys = true }

    private fun arquivo(t: TileId) = File(dir, "${t.ix}_${t.iy}.json")

    override fun ler(tile: TileId): QuadradoEmCache? {
        val f = arquivo(tile)
        if (!f.isFile) return null
        return try {
            val a = json.decodeFromString(ArquivoQuadrado.serializer(), f.readText())
            if (a.versao != VERSAO) return null
            QuadradoEmCache(a.buscadoEm, a.itens.mapNotNull { i ->
                val tipo = runCatching { Perigo.valueOf(i.tipo) }.getOrNull() ?: return@mapNotNull null
                PerigoPonto(i.id, tipo, i.lat, i.lon, i.limite, i.direcao)
            })
        } catch (e: Exception) {
            null
        }
    }

    override fun gravar(tile: TileId, q: QuadradoEmCache) {
        try {
            dir.mkdirs()
            val a = ArquivoQuadrado(VERSAO, q.buscadoEm, q.perigos.map { ItemQuadrado(it.id, it.tipo.name, it.lat, it.lon, it.limiteKmh, it.direcaoGraus) })
            val tmp = File(dir, "${tile.ix}_${tile.iy}.tmp")
            tmp.writeText(json.encodeToString(ArquivoQuadrado.serializer(), a))
            val alvo = arquivo(tile)
            try {
                java.nio.file.Files.move(tmp.toPath(), alvo.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(tmp.toPath(), alvo.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            // cache é só otimização: sem espaço/permissão, segue sem ele
        }
    }

    /** Apaga arquivos com mais de [maxIdadeMs] (limpeza ocasional; o cache ocupa pouco). */
    fun limpar(agora: Long, maxIdadeMs: Long = 4 * VALIDADE_CACHE_MS) {
        dir.listFiles()?.forEach { if (agora - it.lastModified() > maxIdadeMs) it.delete() }
    }

    companion object { const val VERSAO = 1 }
}
