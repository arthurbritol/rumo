package app.hunter.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hunter.app.HunterViewModel
import app.hunter.model.FuelMath
import app.hunter.model.Fuelup

@Composable
fun FuelScreen(vm: HunterViewModel) {
    val fuelups by vm.fuelups.collectAsStateWithLifecycle()
    val summary by vm.fuelSummary.collectAsStateWithLifecycle()
    val odo by vm.odometerKm.collectAsStateWithLifecycle()
    val tank by vm.tankLiters.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Fuelup?>(null) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Combustível", style = MaterialTheme.typography.headlineSmall)

        Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text("Registrar abastecimento", fontSize = 18.sp)
        }

        HCard {
            if (summary.avgKmPerL == null) {
                Text("Registre dois abastecimentos de tanque cheio para calcular o consumo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MetricRow(
                "Consumo médio" to (summary.avgKmPerL?.let { "${fmtNum(it)} km/l" } ?: "—"),
                "Último trecho" to (summary.lastKmPerL?.let { "${fmtNum(it)} km/l" } ?: "—"),
            )
            MetricRow(
                "Custo por km" to (summary.avgCostPerKm?.let { fmtMoney(it) } ?: "—"),
                "Autonomia (${fmtNum(tank, 0)} L)" to (FuelMath.rangeKm(summary.avgKmPerL, tank)?.let { fmtKm(it, 0) } ?: "—"),
            )
            MetricRow("Total gasto" to fmtMoney(summary.totalCost), "Total abastecido" to "${fmtNum(summary.totalLiters)} L")
        }

        SectionTitle("Abastecimentos")
        if (fuelups.isEmpty()) Text("Nenhum abastecimento ainda.")
        fuelups.forEach { f ->
            val seg = summary.segments.firstOrNull { it.toKm == f.odometerKm }
            HCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${fmtDate(f.dateMs)} · ${fmtKm(f.odometerKm, 0)}" + if (!f.fullTank) " · parcial" else "",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text("${fmtNum(f.liters, 2)} L · ${fmtMoney(f.totalCost)} · ${fmtMoney(f.pricePerLiter)}/L")
                        seg?.let { Text("${fmtNum(it.kmPerL)} km/l · ${fmtMoney(it.costPerKm)}/km", color = MaterialTheme.colorScheme.primary) }
                    }
                    TextButton(onClick = { deleting = f }) { Text("Excluir") }
                }
            }
        }
    }

    if (adding) AddFuelDialog(defaultOdo = odo, onDismiss = { adding = false }) { o, l, c, full ->
        vm.addFuelup(o, l, c, full)
        adding = false
    }

    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Excluir abastecimento?") },
            text = { Text("${fmtDate(f.dateMs)} · ${fmtNum(f.liters, 2)} L") },
            confirmButton = { TextButton(onClick = { vm.deleteFuelup(f.id); deleting = null }) { Text("Excluir") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun AddFuelDialog(defaultOdo: Double, onDismiss: () -> Unit, onSave: (Double, Double, Double, Boolean) -> Unit) {
    var odo by remember { mutableStateOf(fmtNum(defaultOdo, 0)) }
    var liters by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var full by remember { mutableStateOf(true) }
    val o = parseNumber(odo)
    val l = parseNumber(liters)
    val c = parseNumber(cost)
    val valid = o != null && o >= 0 && l != null && l > 0 && c != null && c >= 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Novo abastecimento") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Hodômetro (km)", odo) { odo = it }
                NumberField("Litros", liters) { liters = it }
                NumberField("Valor total (R$)", cost) { cost = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Tanque cheio")
                        Text("Necessário para calcular o consumo", style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(checked = full, onCheckedChange = { full = it })
                }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(o!!, l!!, c!!, full) }) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
