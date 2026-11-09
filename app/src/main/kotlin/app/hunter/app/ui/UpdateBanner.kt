package app.hunter.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.hunter.app.UpdateUi

/** Cartão "Nova versão disponível": mostra as alterações e baixa/instala a release do GitHub. */
@Composable
fun UpdateBanner(ui: UpdateUi, onInstall: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    FloatingCard(modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("Nova versão disponível", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Rumo ${ui.update.versionName} (build ${ui.update.build})" + sizeLabel(ui.update.sizeBytes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val changes = changeLines(ui.update.notes)
        changes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, maxLines = 2) }

        if (ui.downloading) {
            if (ui.progress >= 0f) LinearProgressIndicator(progress = { ui.progress }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("Baixando…", style = MaterialTheme.typography.bodySmall)
        } else {
            ui.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Agora não", onDismiss, Modifier.weight(1f))
                PrimaryButton("Atualizar", onInstall, Modifier.weight(1f))
            }
        }
    }
}

private fun sizeLabel(bytes: Long) = if (bytes > 0) " · %.0f MB".format(bytes / 1_048_576.0) else ""

/** Itens principais da lista de alterações da release (sem rodapé, sem negrito, no máximo 4). */
internal fun changeLines(notes: String): List<String> =
    notes.substringBefore("\n---")
        .lines()
        .filter { it.startsWith("- ") }
        .map { it.removePrefix("- ").replace("**", "").trim() }
        .filter { it.isNotEmpty() }
        .take(4)
