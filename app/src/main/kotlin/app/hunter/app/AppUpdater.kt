package app.hunter.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import app.hunter.nav.AppUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Baixa o APK de uma [AppUpdate] e entrega ao instalador do Android (que sempre pede a confirmação do usuário). */
class AppUpdater(private val context: Context) {

    private val dir get() = File(context.cacheDir, "updates")

    /** `versionCode` instalado agora. */
    fun installedCode(): Int =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()

    /** Baixa para o cache (reaproveita se já baixou inteiro). [onProgress] recebe 0..1 (ou -1 se o tamanho é desconhecido). */
    suspend fun download(update: AppUpdate, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val target = File(dir, "Rumo-${update.tag}.apk")
        dir.listFiles()?.filter { it != target }?.forEach { it.delete() }
        if (target.exists() && update.sizeBytes > 0 && target.length() == update.sizeBytes) return@withContext target

        val part = File(dir, "${target.name}.part")
        val c = URL(update.apkUrl).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "Rumo (atualizador)")
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: update.sizeBytes
            var read = 0L
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastPct = -2
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        val pct = if (total > 0) (read * 100 / total).toInt() else -1
                        if (pct != lastPct) { lastPct = pct; onProgress(if (pct < 0) -1f else pct / 100f) }
                    }
                }
            }
            if (total > 0 && read != total) throw IOException("download incompleto")
            if (!part.renameTo(target)) throw IOException("não foi possível salvar o arquivo")
            target
        } finally {
            c.disconnect()
            part.delete()
        }
    }

    /**
     * Abre o instalador do sistema. Devolve `false` quando o Android ainda não deixou o Rumo instalar apps: nesse
     * caso abre a tela de permissão e o usuário volta e toca em Atualizar de novo.
     */
    fun install(apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }
}
