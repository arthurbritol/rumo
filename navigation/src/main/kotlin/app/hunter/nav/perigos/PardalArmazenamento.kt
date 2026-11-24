package app.hunter.nav.perigos

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Metadados de uma UF baixada (arquivo `<UF>.meta`, JSON). [bbox] = [latMin, lonMin, latMax, lonMax]. */
@Serializable
data class PardalMeta(
    val uf: String,
    val artifactBuiltAt: String,
    /** sha256 de cada arquivo de origem do catálogo (radares.csv, lombadas.csv, pontos_atencao.csv). */
    val sha256: Map<String, String> = emptyMap(),
    val baixadoEm: Long,
    val contagens: Map<String, Int> = emptyMap(),
    val bbox: List<Double> = emptyList(),
    /** sha256 do `<UF>.csv` nosso: pega arquivo trocado pela metade ou adulterado. */
    val csvSha256: String = "",
    val total: Int = 0,
)

/**
 * Armazenamento por UF em [dir] (no app: `files/pardal-v1/`): `<UF>.csv` (formato nosso, TSV, uma linha por ponto:
 * id, tipo, lat, lon, limite, direção, sentido, fimLat, fimLon) e `<UF>.meta`.
 *
 * Escrita: tudo vai primeiro para arquivos temporários; só depois de escritos e relidos com sucesso é que o antigo é trocado
 * (renomear). Leitura tolerante: ausente ou corrompido (JSON ruim, linha ruim, contagem ou sha256 diferente) = UF não baixada;
 * nunca lança.
 */
class PardalArmazenamento(
    private val dir: File,
    /** Escreve o conteúdo de um arquivo; trocável nos testes para simular falha no meio da gravação. */
    private val escrever: (File, ByteArray) -> Unit = { f, b -> f.writeBytes(b) },
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun csv(uf: String) = File(dir, "$uf.csv")
    private fun meta(uf: String) = File(dir, "$uf.meta")

    /** Lê só o `.meta` (barato); null se ausente, ilegível ou de outra UF. Não confere o CSV. */
    fun lerMeta(uf: String): PardalMeta? = try {
        json.decodeFromString<PardalMeta>(meta(uf).readText()).takeIf { it.uf == uf }
    } catch (e: Exception) { null }

    /** UF completa e íntegra: meta + pontos. Null se qualquer coisa estiver errada. */
    fun ler(uf: String): Pair<PardalMeta, List<PerigoPonto>>? = try {
        val m = lerMeta(uf)
        val bytes = csv(uf).readBytes()
        if (m == null || sha(bytes) != m.csvSha256) null
        else decodificar(bytes.toString(Charsets.UTF_8))?.takeIf { it.size == m.total }?.let { m to it }
    } catch (e: Exception) { null }

    /** UFs com `.meta` legível (não garante CSV íntegro). */
    fun ufsBaixadas(): List<String> =
        dir.listFiles { f -> f.name.endsWith(".meta") }?.map { it.name.removeSuffix(".meta") }?.filter { lerMeta(it) != null }?.sorted().orEmpty()

    /** Grava [pontos] e [meta] (csvSha256 e total são preenchidos aqui). Lança se falhar, sem mexer no que havia. */
    fun gravar(meta: PardalMeta, pontos: List<PerigoPonto>) {
        dir.mkdirs()
        val uf = meta.uf
        val bytes = codificar(pontos).toByteArray(Charsets.UTF_8)
        val novo = meta.copy(csvSha256 = sha(bytes), total = pontos.size)
        val tmpCsv = File(dir, "$uf.csv.tmp")
        val tmpMeta = File(dir, "$uf.meta.tmp")
        try {
            escrever(tmpCsv, bytes)
            escrever(tmpMeta, json.encodeToString(PardalMeta.serializer(), novo).toByteArray(Charsets.UTF_8))
            // Valida o que foi escrito antes de trocar o antigo.
            check(sha(tmpCsv.readBytes()) == novo.csvSha256) { "csv gravado diferente do esperado" }
            check(decodificar(tmpCsv.readText())?.size == pontos.size) { "csv gravado não confere" }
            check(json.decodeFromString<PardalMeta>(tmpMeta.readText()) == novo) { "meta gravado não confere" }
            trocar(tmpCsv, csv(uf))
            trocar(tmpMeta, meta(uf))
        } finally {
            tmpCsv.delete()
            tmpMeta.delete()
        }
    }

    /** Apaga os arquivos da UF (csv, meta e temporários). Nunca lança; a UF passa a "não baixada". */
    fun remover(uf: String) {
        // O meta primeiro: sem ele a UF já conta como não baixada, mesmo que o csv resista.
        for (f in listOf(meta(uf), csv(uf), File(dir, "$uf.csv.tmp"), File(dir, "$uf.meta.tmp"))) {
            try { f.delete() } catch (e: Exception) { /* sem permissão: ignora */ }
        }
    }

    /** Dado conferido de novo contra o catálogo: só atualiza o instante em que foi confirmado em dia. */
    fun renovar(uf: String, baixadoEm: Long) {
        val m = lerMeta(uf) ?: return
        val tmp = File(dir, "$uf.meta.tmp")
        try {
            escrever(tmp, json.encodeToString(PardalMeta.serializer(), m.copy(baixadoEm = baixadoEm)).toByteArray(Charsets.UTF_8))
            trocar(tmp, meta(uf))
        } finally {
            tmp.delete()
        }
    }

    private fun trocar(de: File, para: File) {
        try {
            Files.move(de.toPath(), para.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(de.toPath(), para.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        private fun num(d: Double?) = d?.toString().orEmpty()

        fun codificar(pontos: List<PerigoPonto>): String = buildString {
            for (p in pontos) {
                append(p.id).append('\t').append(p.tipo.name).append('\t').append(p.lat).append('\t').append(p.lon).append('\t')
                append(p.limiteKmh?.toString().orEmpty()).append('\t').append(num(p.direcaoGraus)).append('\t')
                append(if (p.sentido) '1' else '0').append('\t').append(num(p.fimLat)).append('\t').append(num(p.fimLon)).append('\n')
            }
        }

        /** Null se alguma linha estiver malformada (arquivo corrompido). */
        fun decodificar(texto: String): List<PerigoPonto>? {
            val out = ArrayList<PerigoPonto>()
            for (linha in texto.lineSequence()) {
                if (linha.isEmpty()) continue
                val c = linha.split('\t')
                if (c.size != 9) return null
                val tipo = Perigo.entries.firstOrNull { it.name == c[1] } ?: return null
                val lat = c[2].toDoubleOrNull() ?: return null
                val lon = c[3].toDoubleOrNull() ?: return null
                if (c[4].isNotEmpty() && c[4].toIntOrNull() == null) return null
                if (c[5].isNotEmpty() && c[5].toDoubleOrNull() == null) return null
                if (c[7].isNotEmpty() && c[7].toDoubleOrNull() == null) return null
                if (c[8].isNotEmpty() && c[8].toDoubleOrNull() == null) return null
                out += PerigoPonto(c[0], tipo, lat, lon, c[4].toIntOrNull(), c[5].toDoubleOrNull(), c[6] == "1", c[7].toDoubleOrNull(), c[8].toDoubleOrNull())
            }
            return out
        }
    }
}
