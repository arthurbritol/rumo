package app.hunter.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hunter.app.HunterViewModel
import app.hunter.app.MaintenanceRow
import app.hunter.model.DueState

@Composable
fun MaintenanceScreen(vm: HunterViewModel) {
    val rows by vm.maintenance.collectAsStateWithLifecycle()
    val odo by vm.odometerKm.collectAsStateWithLifecycle()
    val tank by vm.tankLiters.collectAsStateWithLifecycle()
    var editOdo by remember { mutableStateOf(false) }
    var addItem by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<MaintenanceRow?>(null) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Manutenção", style = MaterialTheme.typography.headlineSmall)

        HCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Metric("Hodômetro da moto", fmtKm(odo, 0), Modifier.weight(1f))
                OutlinedButton(onClick = { editOdo = true }) { Text("Ajustar") }
            }
            Text(
                "Cada pedal gravado soma à quilometragem. Se o painel divergir, ajuste aqui.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionTitle("Lembretes")
        rows.forEach { row ->
            val (color, label) = when (row.status.state) {
                DueState.OVERDUE -> Bad to "Atrasado"
                DueState.SOON -> Warn to "Perto"
                DueState.OK -> Ok to "Em dia"
                DueState.UNKNOWN -> MaterialTheme.colorScheme.outline to "Sem registro"
            }
            HCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.item.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Pill(label, color)
                }
                Text(describe(row), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { vm.markDone(row.item) }) { Text("Feito agora") }
                    TextButton(onClick = { deleting = row }) { Text("Excluir") }
                }
            }
        }
        OutlinedButton(onClick = { addItem = true }, modifier = Modifier.fillMaxWidth()) { Text("Adicionar item") }
        Text(
            "Os intervalos iniciais são sugestões genéricas: confira com o manual da Hunter 350.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionTitle("Tanque")
        TankRow(tank) { vm.setTank(it) }
    }

    if (editOdo) NumberDialog("Hodômetro do painel (km)", fmtNum(odo, 0), { editOdo = false }) { vm.setOdometer(it); editOdo = false }

    if (addItem) AddItemDialog({ addItem = false }) { n, km, d -> vm.addMaintenanceItem(n, km, d); addItem = false }

    deleting?.let { r ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Excluir \"${r.item.name}\"?") },
            confirmButton = { TextButton(onClick = { vm.deleteItem(r.item.id); deleting = null }) { Text("Excluir") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

private fun describe(row: MaintenanceRow): String {
    val item = row.item
    val parts = mutableListOf<String>()
    item.intervalKm?.let { parts += "a cada ${it} km" }
    item.intervalDays?.let { parts += "a cada ${it} dias" }
    val interval = parts.joinToString(" ou ").ifEmpty { "sem intervalo" }
    val s = row.status
    val remain = buildList {
        s.remainingKm?.let { add(if (it >= 0) "faltam ${fmtNum(it, 0)} km" else "passou ${fmtNum(-it, 0)} km") }
        s.remainingDays?.let { add(if (it >= 0) "faltam $it dias" else "passou ${-it} dias") }
    }
    return if (remain.isEmpty()) "$interval · marque \"Feito agora\" para começar a contar"
    else "$interval · ${remain.joinToString(" / ")}"
}

@Composable
private fun TankRow(tank: Double, onSave: (Double) -> Unit) {
    var edit by remember { mutableStateOf(false) }
    HCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Metric("Capacidade (autonomia)", "${fmtNum(tank)} L", Modifier.weight(1f))
            OutlinedButton(onClick = { edit = true }) { Text("Alterar") }
        }
    }
    if (edit) NumberDialog("Capacidade do tanque (L)", fmtNum(tank), { edit = false }) { if (it > 0) onSave(it); edit = false }
}

@Composable
private fun NumberDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val v = parseNumber(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { NumberField("Valor", text) { text = it } },
        confirmButton = { TextButton(enabled = v != null && v >= 0, onClick = { onSave(v!!) }) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun AddItemDialog(onDismiss: () -> Unit, onSave: (String, Int?, Int?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var km by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("") }
    val kmV = parseNumber(km)?.toInt()
    val dayV = parseNumber(days)?.toInt()
    val valid = name.isNotBlank() && (kmV != null && kmV > 0 || dayV != null && dayV > 0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Novo item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                NumberField("A cada (km) — opcional", km) { km = it }
                NumberField("A cada (dias) — opcional", days) { days = it }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(name.trim(), kmV?.takeIf { it > 0 }, dayV?.takeIf { it > 0 }) }) { Text("Adicionar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
