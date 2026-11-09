package app.hunter.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Aviso discreto para quem já usava o app: criar uma conta protege os dados. Sempre dispensável. */
@Composable
fun AccountNotice(onCreate: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    FloatingCard(modifier.padding(horizontal = 12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                "Seus dados estão só neste celular. Crie uma conta para protegê-los e recuperá-los se trocar de aparelho.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Agora não") }
                TextButton(onClick = onCreate) { Text("Criar conta") }
            }
        }
    }
}
