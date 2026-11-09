package app.hunter.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.hunter.app.OfflineArea
import app.hunter.app.OfflineMaps
import app.hunter.app.ReminderWorker
import app.hunter.model.GeoBox
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hunter.app.HunterViewModel
import app.hunter.app.MaintenanceRow
import app.hunter.model.DueState
import app.hunter.model.FuelMath
import app.hunter.model.FuelSegment
import app.hunter.model.Fuelup
import kotlin.math.max

@Composable
fun GarageScreen(vm: HunterViewModel, onOpenProfile: () -> Unit = {}) {
    val odo by vm.odometerKm.collectAsStateWithLifecycle()
    val attention by vm.attention.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(0) }
    var editOdo by remember { mutableStateOf(false) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Garagem", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenProfile) { Icon(Icons.Default.AccountCircle, contentDescription = "Perfil e conta") }
        }

        HCard {
            Text("Hodômetro da moto", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(fmtNum(odo, 0), style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
                Text(" km", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp).weight(1f))
                TextButton(onClick = { editOdo = true }) { Text("Ajustar") }
            }
            Text(
                "Cada viagem gravada soma à quilometragem. Se o painel divergir, ajuste aqui.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectChip("Combustível", section == 0, { section = 0 })
            SelectChip(if (attention.isNotEmpty()) "Manutenção (${attention.size})" else "Manutenção", section == 1, { section = 1 })
            SelectChip("Mapas offline", section == 2, { section = 2 })
        }

        when (section) {
            0 -> FuelSection(vm)
            1 -> MaintenanceSection(vm)
            else -> OfflineSection(vm)
        }
        Box(Modifier.height(24.dp))
    }

    if (editOdo) NumberDialog("Hodômetro do painel (km)", fmtNum(odo, 0), { editOdo = false }) { vm.setOdometer(it); editOdo = false }
}

// ---------------------------------------------------------------- combustível

@Composable
private fun FuelSection(vm: HunterViewModel) {
    val fuelups by vm.fuelups.collectAsStateWithLifecycle()
    val summary by vm.fuelSummary.collectAsStateWithLifecycle()
    val odo by vm.odometerKm.collectAsStateWithLifecycle()
    val tank by vm.tankLiters.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Fuelup?>(null) }
    var editTank by remember { mutableStateOf(false) }

    PrimaryButton("Registrar abastecimento", { adding = true })

    HCard {
        Text("Consumo médio", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(summary.avgKmPerL?.let { fmtNum(it) } ?: "—", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
            Text(" km/l", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (summary.avgKmPerL == null) {
            Text("Registre dois abastecimentos de tanque cheio para calcular o consumo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MetricRow(
            "Custo por km" to (summary.avgCostPerKm?.let { fmtMoney(it) } ?: "—"),
            "Último trecho" to (summary.lastKmPerL?.let { "${fmtNum(it)} km/l" } ?: "—"),
        )
        MetricRow(
            "Autonomia" to (FuelMath.rangeKm(summary.avgKmPerL, tank)?.let { fmtKm(it, 0) } ?: "—"),
            "Tanque" to "${fmtNum(tank, 0)} L",
        )
        TextButton(onClick = { editTank = true }) { Text("Alterar capacidade do tanque") }
    }

    if (summary.segments.size >= 2) {
        SectionTitle("Consumo por trecho")
        HCard { ConsumptionBars(summary.segments.takeLast(10), summary.avgKmPerL, Modifier.fillMaxWidth().height(150.dp)) }
    }

    HCard {
        MetricRow("Total gasto" to fmtMoney(summary.totalCost), "Total abastecido" to "${fmtNum(summary.totalLiters)} L")
    }

    SectionTitle("Abastecimentos")
    if (fuelups.isEmpty()) Text("Nenhum abastecimento ainda.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    fuelups.forEach { f ->
        val seg = summary.segments.firstOrNull { it.toKm == f.odometerKm }
        HCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "${fmtDate(f.dateMs)} · ${fmtKm(f.odometerKm, 0)}" + if (!f.fullTank) " · parcial" else "",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text("${fmtNum(f.liters, 2)} L · ${fmtMoney(f.totalCost)} · ${fmtMoney(f.pricePerLiter)}/L", style = MaterialTheme.typography.bodyMedium)
                    seg?.let { Text("${fmtNum(it.kmPerL)} km/l · ${fmtMoney(it.costPerKm)}/km", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge) }
                }
                TextButton(onClick = { deleting = f }) { Text("Excluir", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }

    if (adding) AddFuelDialog(defaultOdo = odo, onDismiss = { adding = false }) { o, l, c, full ->
        vm.addFuelup(o, l, c, full)
        adding = false
    }
    if (editTank) NumberDialog("Capacidade do tanque (L)", fmtNum(tank), { editTank = false }) { if (it > 0) vm.setTank(it); editTank = false }

    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Excluir abastecimento?") },
            text = { Text("${fmtDate(f.dateMs)} · ${fmtNum(f.liters, 2)} L") },
            confirmButton = { TextButton(onClick = { vm.deleteFuelup(f.id); deleting = null }) { Text("Excluir", color = Bad) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

/** Barras de km/l por trecho, com a linha tracejada da média. */
@Composable
private fun ConsumptionBars(segments: List<FuelSegment>, avg: Double?, modifier: Modifier) {
    val bar = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val line = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        val max = max(segments.maxOf { it.kmPerL }, avg ?: 0.0) * 1.15
        val gap = 8.dp.toPx()
        val w = (size.width - gap * (segments.size - 1)) / segments.size
        segments.forEachIndexed { i, s ->
            val h = (s.kmPerL / max).toFloat() * size.height
            drawRoundRect(
                color = if (i == segments.lastIndex) bar else dim,
                topLeft = Offset(i * (w + gap), size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(8.dp.toPx()),
            )
        }
        avg?.let {
            val y = size.height - (it / max).toFloat() * size.height
            drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
        }
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

// ---------------------------------------------------------------- manutenção

@Composable
private fun MaintenanceSection(vm: HunterViewModel) {
    val rows by vm.maintenance.collectAsStateWithLifecycle()
    var addItem by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<MaintenanceRow?>(null) }
    val context = LocalContext.current

    // Lembretes por notificação (Android 13+ pede permissão).
    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    var notifOk by remember { mutableStateOf(!needsPermission || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) }
    val askNotif = rememberPermissionAction(listOf(Manifest.permission.POST_NOTIFICATIONS), listOf(Manifest.permission.POST_NOTIFICATIONS)) {
        notifOk = true
        ReminderWorker.runNow(context)
    }
    if (!notifOk) {
        HCard {
            Text("Receber lembretes", style = MaterialTheme.typography.titleMedium)
            Text(
                "O app avisa por notificação quando um item estiver perto de vencer ou vencer (verifica a cada 12 horas).",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryButton("Ativar lembretes", askNotif)
        }
    } else {
        Text(
            "Lembretes ativos: você é avisado quando um item chega perto de vencer e quando vence.",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    rows.forEach { row ->
        val (color, label) = when (row.status.state) {
            DueState.OVERDUE -> Bad to "Atrasado"
            DueState.SOON -> Warn to "Perto"
            DueState.OK -> Ok to "Em dia"
            DueState.UNKNOWN -> MaterialTheme.colorScheme.outline to "Sem registro"
        }
        HCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(row.item.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Pill(label, color)
            }
            val used = usedFraction(row)
            if (used != null) ProgressBar(used, color)
            Text(describe(row), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { vm.markDone(row.item) }) { Text("Feito agora") }
                TextButton(onClick = { deleting = row }) { Text("Excluir", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    SecondaryButton("Adicionar item", { addItem = true })
    Text(
        "Os intervalos iniciais são sugestões genéricas: confira com o manual da Hunter 350.",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (addItem) AddItemDialog({ addItem = false }) { n, km, d -> vm.addMaintenanceItem(n, km, d); addItem = false }
    deleting?.let { r ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Excluir \"${r.item.name}\"?") },
            confirmButton = { TextButton(onClick = { vm.deleteItem(r.item.id); deleting = null }) { Text("Excluir", color = Bad) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

// ---------------------------------------------------------------- mapas offline

@Composable
private fun OfflineSection(vm: HunterViewModel) {
    val context = LocalContext.current
    val areas by OfflineMaps.areas.collectAsStateWithLifecycle()
    val error by OfflineMaps.lastError.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val nav by vm.nav.collectAsStateWithLifecycle()
    var radius by remember { mutableStateOf(25) }
    var deleting by remember { mutableStateOf<OfflineArea?>(null) }

    LaunchedEffect(Unit) { OfflineMaps.refresh(context) }

    HCard {
        Text("Mapas sem internet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Baixe uma região para o mapa continuar aparecendo sem sinal na estrada. Atenção: a busca de endereços e " +
                "o cálculo de rotas ainda precisam de internet; uma rota já iniciada segue funcionando.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SectionTitle("Ao redor de mim")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(10, 25, 50).forEach { r -> SelectChip("$r km", radius == r, { radius = r }) }
    }
    val here = live
    val box = here?.let { GeoBox.around(it.latitude, it.longitude, radius.toDouble()) }
    val mb = box?.let { OfflineMaps.estimateMb(it) }
    PrimaryButton(
        if (mb != null) "Baixar região (≈ $mb MB)" else "Aguardando sua localização",
        {
            if (box != null) {
                OfflineMaps.download(context, "Perto de mim · $radius km · ${fmtDate(System.currentTimeMillis())}", box)
            }
        },
        enabled = box != null && (mb ?: 0) <= MAX_DOWNLOAD_MB,
    )
    if (box == null) Text("Abra a aba Mapa uma vez para obter sua localização.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    val route = nav.route
    if (route != null) {
        SectionTitle("Rota atual")
        val corridor = GeoBox.enclosing(route.shape.map { it.lat to it.lon }, padKm = 8.0)
        val cmb = OfflineMaps.estimateMb(corridor)
        SecondaryButton(
            if (cmb <= MAX_DOWNLOAD_MB) "Baixar a região da rota (≈ $cmb MB)" else "Rota grande demais (≈ $cmb MB)",
            {
                if (cmb <= MAX_DOWNLOAD_MB) OfflineMaps.download(context, "Rota para ${nav.destination?.name ?: "destino"}", corridor)
            },
        )
    }

    error?.let {
        Text(it, color = Bad, style = MaterialTheme.typography.bodySmall)
    }

    SectionTitle("Baixados")
    if (areas.isEmpty()) Text("Nenhum mapa baixado.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    areas.forEach { a ->
        HCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(a.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (a.complete) Pill("Pronto", Ok)
            }
            if (!a.complete) ProgressBar(a.fraction, MaterialTheme.colorScheme.primary)
            Text(
                if (a.complete) "${fmtNum(a.bytes / 1_048_576.0, 0)} MB" else "${(a.fraction * 100).toInt()}% · ${fmtNum(a.bytes / 1_048_576.0, 0)} MB",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            a.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Bad) }
            TextButton(onClick = { deleting = a }) { Text("Excluir", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }

    deleting?.let { a ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Excluir \"${a.name}\"?") },
            text = { Text("O mapa dessa região volta a depender de internet.") },
            confirmButton = { TextButton(onClick = { OfflineMaps.delete(a.name); deleting = null }) { Text("Excluir", color = Bad) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

private const val MAX_DOWNLOAD_MB = 400

/** Fração do intervalo já consumida (0..1), pela medida que está mais adiantada. */
private fun usedFraction(row: MaintenanceRow): Float? {
    val i = row.item
    val s = row.status
    val ik = i.intervalKm
    val rk = s.remainingKm
    val id = i.intervalDays
    val rd = s.remainingDays
    val byKm = if (ik != null && rk != null) 1.0 - rk / ik else null
    val byDays = if (id != null && rd != null) 1.0 - rd.toDouble() / id else null
    val v = listOfNotNull(byKm, byDays).maxOrNull() ?: return null
    return v.coerceIn(0.0, 1.0).toFloat()
}

@Composable
private fun ProgressBar(fraction: Float, color: Color) {
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).fillMaxHeight().background(color, RoundedCornerShape(50)))
    }
}

private fun describe(row: MaintenanceRow): String {
    val item = row.item
    val parts = mutableListOf<String>()
    item.intervalKm?.let { parts += "a cada $it km" }
    item.intervalDays?.let { parts += "a cada $it dias" }
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
                OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
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
