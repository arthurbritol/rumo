package app.hunter.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.hunter.app.fuel.ResolvedFuelStation
import java.util.Locale

private fun formatPriceForInput(price: Double?): String {
    if (price == null || !price.isFinite() || price <= 0.0) return ""
    return String.format(Locale.US, "%.2f", price)
}

private fun parsePriceInput(text: String): Double? {
    val clean = text.trim().replace(',', '.')
    val parsed = clean.toDoubleOrNull() ?: return null
    return if (parsed > 0.0 && parsed < 30.0) parsed else null
}

/**
 * Diálogo Novare para informar ou atualizar os preços da bomba de combustível
 * (Gasolina Comum, Gasolina Aditivada e Etanol) diretamente pelo motociclista.
 */
@Composable
fun EditFuelPricesDialog(
    station: ResolvedFuelStation,
    onDismiss: () -> Unit,
    onSave: (precoGasolina: Double?, precoGasolinaAdit: Double?, precoEtanol: Double?) -> Unit,
) {
    var gcText by remember(station) { mutableStateOf(formatPriceForInput(station.precoGasolina)) }
    var gaText by remember(station) { mutableStateOf(formatPriceForInput(station.precoGasolinaAdit)) }
    var etText by remember(station) { mutableStateOf(formatPriceForInput(station.precoEtanol)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Informar Preços da Bomba",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = listOfNotNull(station.nome, station.endereco.takeIf { it.isNotBlank() })
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = gcText,
                    onValueChange = { gcText = it },
                    label = { Text("Gasolina Comum (R$/l)") },
                    placeholder = { Text("Ex: 6.38") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    ),
                )
                OutlinedTextField(
                    value = gaText,
                    onValueChange = { gaText = it },
                    label = { Text("Gasolina Aditivada (R$/l)") },
                    placeholder = { Text("Ex: 6.65") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    ),
                )
                OutlinedTextField(
                    value = etText,
                    onValueChange = { etText = it },
                    label = { Text("Etanol (R$/l)") },
                    placeholder = { Text("Ex: 4.00") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    ),
                )
            }
        },
        confirmButton = {
            val hasValidPrice = parsePriceInput(gcText) != null || parsePriceInput(gaText) != null || parsePriceInput(etText) != null
            TextButton(
                enabled = hasValidPrice,
                onClick = {
                    val gc = parsePriceInput(gcText)
                    val ga = parsePriceInput(gaText)
                    val et = parsePriceInput(etText)
                    onSave(gc, ga, et)
                },
            ) {
                Text(
                    text = "Salvar Preços",
                    fontWeight = FontWeight.Bold,
                    color = if (hasValidPrice) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Cancelar",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
