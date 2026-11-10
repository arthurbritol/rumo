package app.hunter.app.ui

import android.Manifest
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hunter.app.HunterViewModel
import app.hunter.app.R
import app.hunter.nav.PodBottomMode
import app.hunter.pod.CompassDirection
import app.hunter.podble.PodPhase
import app.hunter.podble.PodService

private fun podPerms() = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    add(Manifest.permission.ACCESS_FINE_LOCATION) // bússola pelo GPS (e scan BLE no Android ≤ 11)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}

private fun podRequired() =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

@Composable
fun PodScreen(vm: HunterViewModel) {
    val context = LocalContext.current
    val pod by vm.pod.collectAsStateWithLifecycle()
    val nav by vm.nav.collectAsStateWithLifecycle()
    var pin by remember { mutableStateOf("") }

    val connect = rememberPermissionAction(podPerms(), podRequired()) { PodService.connect(context) }
    val active = pod.phase != PodPhase.IDLE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Cabeçalho da tela
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 4.dp),
        ) {
            Text(
                text = "Tripper Pod",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Painel secundário turn-by-turn via Bluetooth",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 1. Cockpit do Pod (Card Principal de Conexão e Status)
        HCard {
            val (statusText, statusColor, isConnected) = when (pod.phase) {
                PodPhase.CONNECTED -> Triple("Conectado", NovareSuccess, true)
                PodPhase.AWAITING_PIN -> Triple("Aguardando PIN", NovareWarning, false)
                PodPhase.SCANNING -> Triple("Procurando…", NovareWarning, false)
                PodPhase.CONNECTING -> Triple("Conectando…", NovareWarning, false)
                PodPhase.IDLE -> Triple(
                    if (pod.pairedName != null) "Pareado (offline)" else "Desconectado",
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    false,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SquircleIcon(
                    iconRes = R.drawable.fa_bluetooth,
                    backgroundColor = if (isConnected) NovareInfo.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                    tintColor = if (isConnected) NovareInfo else MaterialTheme.colorScheme.onSurfaceVariant,
                    contentDescription = "Bluetooth Pod",
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Royal Enfield Tripper",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = pod.pairedName?.let { "Dispositivo: $it" } ?: "Display auxiliar no guidão",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(text = statusText, active = isConnected)
            }

            pod.message?.let { msg ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    Text(
                        text = msg,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 2. Entrada do PIN caso esteja pareando
        if (pod.phase == PodPhase.AWAITING_PIN) {
            HCard {
                Text(
                    text = "Pareamento Bluetooth",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Digite o código PIN de 6 dígitos exibido no mostrador da moto.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isLetterOrDigit).take(6) },
                    singleLine = true,
                    label = { Text("PIN do Pod") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                RumoPrimaryButton(
                    text = "Confirmar PIN",
                    onClick = {
                        PodService.submitPin(context, pin)
                        pin = ""
                    },
                    enabled = pin.isNotEmpty(),
                )
            }
        }

        // 3. Cockpit visual: O que o Pod está mostrando agora
        if (pod.phase == PodPhase.CONNECTED) {
            HCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "NO MOSTRADOR AGORA",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.2.sp,
                    )
                    RumoBadge("Ao vivo", color = NovareSuccess)
                }

                val prog = nav.progress
                if (nav.active && prog != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(84.dp)
                                .background(NovareAccent, RoundedCornerShape(22.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            ManeuverGlyph(prog.upcoming.type, 56.dp, Color.White)
                        }
                        Column {
                            Text(
                                text = fmtDist(prog.distanceToUpcomingM),
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = when (pod.bottomMode) {
                                    PodBottomMode.ARRIVAL_TIME -> "Chegada às ${fmtArrival(prog.remainingS)}"
                                    PodBottomMode.TIME_REMAINING -> "Faltam ${fmtEta(prog.remainingS)}"
                                    PodBottomMode.TOTAL_DISTANCE -> "Faltam ${fmtDist(prog.remainingM)}"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CompassArrow(pod.direction)
                    }
                    Text(
                        text = pod.direction?.let { "Bússola: Rumo ${it.name}" }
                            ?: "Bússola — comece a andar (a direção vem do GPS e atualiza acima de ~5 km/h).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 4. Preferências de Exibição do Pod
        SectionTitle("Linha inferior na navegação")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SelectChip(
                text = "Hora de Chegada",
                selected = pod.bottomMode == PodBottomMode.ARRIVAL_TIME,
                onClick = { PodService.setBottomMode(context, PodBottomMode.ARRIVAL_TIME) },
                modifier = Modifier.weight(1f),
            )
            SelectChip(
                text = "Tempo Restante",
                selected = pod.bottomMode == PodBottomMode.TIME_REMAINING,
                onClick = { PodService.setBottomMode(context, PodBottomMode.TIME_REMAINING) },
                modifier = Modifier.weight(1f),
            )
            SelectChip(
                text = "Distância",
                selected = pod.bottomMode == PodBottomMode.TOTAL_DISTANCE,
                onClick = { PodService.setBottomMode(context, PodBottomMode.TOTAL_DISTANCE) },
                modifier = Modifier.weight(1f),
            )
        }

        // 5. Botões de Ação Táteis
        SectionTitle("Conexão")
        if (active) {
            RumoSecondaryButton(
                text = "Desconectar do Pod",
                onClick = { PodService.disconnect(context) },
            )
            if (pod.phase == PodPhase.CONNECTED) {
                RumoSecondaryButton(
                    text = "Sincronizar relógio do Pod",
                    onClick = { PodService.connect(context) },
                    leadingIconRes = R.drawable.fa_gauge_high,
                )
            }
        } else {
            RumoPrimaryButton(
                text = "Conectar ao Pod",
                onClick = connect,
                leadingIconRes = R.drawable.fa_bluetooth,
            )
        }

        if (pod.pairedName != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = { PodService.forgetPairing(context) }) {
                    Text(
                        text = "Esquecer pareamento Bluetooth",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 6. Instruções e Ajuda
        SectionTitle("Instruções")
        HCard {
            Text(
                text = "Como usar o Tripper Pod",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "1. Ligue a ignição da moto: o Pod anuncia Bluetooth por ~30 segundos.\n" +
                    "2. Toque em Conectar ao Pod. No primeiro pareamento, digite o código PIN exibido no mostrador.\n" +
                    "3. Na aba Mapa, selecione um destino e inicie a rota: o Pod passará a exibir cada manobra, distância e previsão de chegada. Sem rota ativa, ele mostra a bússola digital.\n\n" +
                    "O aplicativo reconecta automaticamente se a conexão oscilar.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = NovareWarning.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, NovareWarning.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Atenção: o protocolo foi testado e validado no Pod da Meteor 350. Na Hunter 350, faça um teste parado antes de iniciar o rolê na estrada.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        HCard {
            Text(
                text = "Protocolos e Créditos",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Mapas e Rotas: OpenStreetMap, OpenFreeMap, MapLibre e Valhalla (servidor público OSM). " +
                    "Busca: Photon (Komoot). Protocolo Bluetooth do Pod: baseado na especificação aberta WazeTripper (Apache-2.0).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CompassArrow(direction: CompassDirection?) {
    val ring = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
    val arrow = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(150.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(
            color = ring,
            radius = size.minDimension / 2 - 4.dp.toPx(),
            style = Stroke(width = 3.dp.toPx()),
        )
        if (direction == null) return@Canvas
        rotate(direction.ordinal * 45f, c) {
            val r = size.minDimension / 2 - 18.dp.toPx()
            val p = Path().apply {
                moveTo(c.x, c.y - r)
                lineTo(c.x + r * 0.32f, c.y + r * 0.55f)
                lineTo(c.x, c.y + r * 0.25f)
                lineTo(c.x - r * 0.32f, c.y + r * 0.55f)
                close()
            }
            drawPath(p, arrow)
        }
    }
}
