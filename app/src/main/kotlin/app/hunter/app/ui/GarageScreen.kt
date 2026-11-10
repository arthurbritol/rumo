package app.hunter.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hunter.app.HunterViewModel
import app.hunter.app.MaintenanceRow
import app.hunter.app.OfflineArea
import app.hunter.app.OfflineMaps
import app.hunter.app.R
import app.hunter.app.ReminderWorker
import app.hunter.app.sync.ProfilePrefs
import app.hunter.model.DueState
import app.hunter.model.FuelMath
import app.hunter.model.FuelSegment
import app.hunter.model.Fuelup
import app.hunter.model.GeoBox
import app.hunter.podble.PodPhase
import kotlin.math.max

@Composable
fun GarageScreen(vm: HunterViewModel, onOpenProfile: () -> Unit = {}) {
    val context = LocalContext.current
    val odo by vm.odometerKm.collectAsStateWithLifecycle()
    val attention by vm.attention.collectAsStateWithLifecycle()
    val summary by vm.fuelSummary.collectAsStateWithLifecycle()
    val tank by vm.tankLiters.collectAsStateWithLifecycle()
    val pod by vm.pod.collectAsStateWithLifecycle()

    var section by rememberSaveable { mutableStateOf(0) }
    var editOdo by remember { mutableStateOf(false) }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. Topo: Cabeçalho com Nome da Moto, badge Pod com fa_bluetooth e Avatar do Perfil
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Garagem",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val p = ProfilePrefs.read(context.getSharedPreferences("app", 0))
                val bikeModel = listOfNotNull(p?.brand, p?.model).joinToString(" ").ifEmpty { "Hunter 350" }
                Text(
                    bikeModel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Badge de status do Pod com fa_bluetooth
            val podConnected = pod.phase == PodPhase.CONNECTED
            val podColor = if (podConnected) NovareSuccess else MaterialTheme.colorScheme.onSurfaceVariant
            val podBg = if (podConnected) NovareSuccess.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
            val podText = when (pod.phase) {
                PodPhase.CONNECTED -> "Pod ativo"
                PodPhase.CONNECTING -> "Conectando"
                PodPhase.SCANNING -> "Buscando"
                PodPhase.AWAITING_PIN -> "Pareando"
                PodPhase.IDLE -> if (pod.pairedName != null) "Pod pareado" else "Pod offline"
            }
            Surface(
                shape = RoundedCornerShape(50),
                color = podBg,
                border = BorderStroke(0.5.dp, podColor.copy(alpha = 0.35f)),
                modifier = Modifier.padding(end = 4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.fa_bluetooth),
                        contentDescription = null,
                        tint = podColor,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = podText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = podColor,
                    )
                }
            }

            IconButton(onClick = onOpenProfile) {
                Icon(
                    painter = painterResource(R.drawable.fa_user),
                    contentDescription = "Perfil e conta",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // 2. Cockpit Fixo Hero: Hodômetro em destaque
        HeroMetricCard(
            title = "Hodômetro",
            unit = "km",
            value = fmtNum(odo, 0),
            actionText = "Ajustar",
            onAction = { editOdo = true },
        )

        // Grid 2 colunas: Autonomia e Revisão
        val rangeKm = FuelMath.rangeKm(summary.avgKmPerL, tank)
        val rangeValue = rangeKm?.let { fmtNum(it, 0) } ?: "—"
        val rangeUnit = if (rangeKm != null) " km" else ""
        val avgSubtext = summary.avgKmPerL?.let { "${fmtNum(it)} km/l" } ?: "Sem dados de km/l"

        val firstAtt = attention.firstOrNull()
        val revValue = when {
            firstAtt == null -> "Em dia"
            firstAtt.status.remainingKm != null -> {
                val rk = firstAtt.status.remainingKm!!
                if (rk <= 0) "Venceu" else "${fmtNum(rk, 0)} km"
            }
            firstAtt.status.remainingDays != null -> {
                val rd = firstAtt.status.remainingDays!!
                if (rd <= 0) "Venceu" else "$rd dias"
            }
            else -> "Atenção"
        }
        val revSubtext = when {
            firstAtt == null -> "Tudo em dia"
            else -> firstAtt.item.name
        }
        val revColor = when {
            firstAtt == null -> NovareSuccess
            firstAtt.status.state == DueState.OVERDUE -> NovareError
            else -> NovareWarning
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Card 1: Autonomia
            HCard(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SquircleIcon(
                        iconRes = R.drawable.fa_gas_pump,
                        backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        tintColor = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "AUTONOMIA",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = rangeValue,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (rangeUnit.isNotEmpty()) {
                        Text(
                            text = rangeUnit,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 3.dp),
                        )
                    }
                }
                Text(
                    text = avgSubtext,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            // Card 2: Revisão
            HCard(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SquircleIcon(
                        iconRes = R.drawable.fa_wrench,
                        backgroundColor = revColor.copy(alpha = 0.15f),
                        tintColor = revColor,
                    )
                    Text(
                        "REVISÃO",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = revValue,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = revSubtext,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        // 3. Seções alternadas (Chips)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SelectChip("Combustível", section == 0, { section = 0 })
            SelectChip(if (attention.isNotEmpty()) "Manutenção (${attention.size})" else "Manutenção", section == 1, { section = 1 })
            SelectChip("Mapas offline", section == 2, { section = 2 })
        }

        // 4. Conteúdo da seção selecionada
        when (section) {
            0 -> FuelSection(vm)
            1 -> MaintenanceSection(vm)
            else -> OfflineSection(vm)
        }
        Spacer(Modifier.height(24.dp))
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

    RumoPrimaryButton(
        text = "Registrar abastecimento",
        onClick = { adding = true },
        leadingIconRes = R.drawable.fa_gas_pump,
    )

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
                SquircleIcon(
                    iconRes = R.drawable.fa_gas_pump,
                    backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    tintColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "${fmtDate(f.dateMs)} · ${fmtKm(f.odometerKm, 0)}" + if (!f.fullTank) " · parcial" else "",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${fmtNum(f.liters, 2)} L · ${fmtMoney(f.totalCost)} · ${fmtMoney(f.pricePerLiter)}/L",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    seg?.let {
                        Text(
                            "${fmtNum(it.kmPerL)} km/l · ${fmtMoney(it.costPerKm)}/km",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                TextButton(onClick = { deleting = f }) {
                    Text("Excluir", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SquircleIcon(
                    iconRes = R.drawable.fa_bell,
                    backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    tintColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Text("Receber lembretes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(
                "O app avisa por notificação quando um item estiver perto de vencer ou vencer (verifica a cada 12 horas).",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RumoPrimaryButton("Ativar lembretes", askNotif, leadingIconRes = R.drawable.fa_bell)
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
                SquircleIcon(
                    iconRes = R.drawable.fa_wrench,
                    backgroundColor = color.copy(alpha = 0.15f),
                    tintColor = color,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(row.item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
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
    RumoSecondaryButton("Adicionar item", { addItem = true }, leadingIconRes = R.drawable.fa_plus)
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            SquircleIcon(
                iconRes = R.drawable.fa_map_location_dot,
                backgroundColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                tintColor = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(end = 12.dp),
            )
            Text("Mapas sem internet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
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
    RumoPrimaryButton(
        text = if (mb != null) "Baixar região (≈ $mb MB)" else "Aguardando sua localização",
        onClick = {
            if (box != null) {
                OfflineMaps.download(context, "Perto de mim · $radius km · ${fmtDate(System.currentTimeMillis())}", box)
            }
        },
        enabled = box != null && (mb ?: 0) <= MAX_DOWNLOAD_MB,
        leadingIconRes = R.drawable.fa_map_location_dot,
    )
    if (box == null) Text("Abra a aba Mapa uma vez para obter sua localização.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    val route = nav.route
    if (route != null) {
        SectionTitle("Rota atual")
        val corridor = GeoBox.enclosing(route.shape.map { it.lat to it.lon }, padKm = 8.0)
        val cmb = OfflineMaps.estimateMb(corridor)
        RumoSecondaryButton(
            text = if (cmb <= MAX_DOWNLOAD_MB) "Baixar a região da rota (≈ $cmb MB)" else "Rota grande demais (≈ $cmb MB)",
            onClick = {
                if (cmb <= MAX_DOWNLOAD_MB) OfflineMaps.download(context, "Rota para ${nav.destination?.name ?: "destino"}", corridor)
            },
            enabled = cmb <= MAX_DOWNLOAD_MB,
            leadingIconRes = R.drawable.fa_route,
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
                SquircleIcon(
                    iconRes = R.drawable.fa_map_location_dot,
                    backgroundColor = (if (a.complete) Ok else Accent).copy(alpha = 0.15f),
                    tintColor = if (a.complete) Ok else Accent,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(a.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (a.complete) "${fmtNum(a.bytes / 1_048_576.0, 0)} MB" else "${(a.fraction * 100).toInt()}% · ${fmtNum(a.bytes / 1_048_576.0, 0)} MB",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (a.complete) Pill("Pronto", Ok)
            }
            if (!a.complete) ProgressBar(a.fraction, MaterialTheme.colorScheme.primary)
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
