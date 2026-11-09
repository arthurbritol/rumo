package app.hunter.app.ui

import android.Manifest
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hunter.app.HunterViewModel
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
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Tripper Pod", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))

        // ----- Estado -----
        HCard {
            val (label, color) = when (pod.phase) {
                PodPhase.CONNECTED -> "Conectado" to Ok
                PodPhase.AWAITING_PIN -> "Aguardando PIN" to Warn
                PodPhase.SCANNING -> "Procurando…" to Warn
                PodPhase.CONNECTING -> "Conectando…" to Warn
                PodPhase.IDLE -> "Desconectado" to MaterialTheme.colorScheme.outline
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).background(color, CircleShape))
                Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 10.dp).weight(1f))
                pod.pairedName?.let { Pill("Pareado", MaterialTheme.colorScheme.secondary) }
            }
            pod.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        if (pod.phase == PodPhase.AWAITING_PIN) {
            HCard {
                Text("Digite o PIN que aparece na tela do Pod", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = pin, onValueChange = { pin = it.filter(Char::isLetterOrDigit).take(6) },
                    singleLine = true, label = { Text("PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                )
                PrimaryButton("Confirmar", { PodService.submitPin(context, pin); pin = "" }, enabled = pin.isNotEmpty())
            }
        }

        // ----- O que o Pod está mostrando -----
        if (pod.phase == PodPhase.CONNECTED) {
            HCard {
                Text("No mostrador agora", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val prog = nav.progress
                if (nav.active && prog != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.size(84.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
                            ManeuverGlyph(prog.upcoming.type, 58.dp, MaterialTheme.colorScheme.onPrimary)
                        }
                        Column {
                            Text(fmtDist(prog.distanceToUpcomingM), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
                            Text(
                                when (pod.bottomMode) {
                                    PodBottomMode.ARRIVAL_TIME -> "Chegada ${fmtArrival(prog.remainingS)}"
                                    PodBottomMode.TIME_REMAINING -> "Faltam ${fmtEta(prog.remainingS)}"
                                    PodBottomMode.TOTAL_DISTANCE -> "Faltam ${fmtDist(prog.remainingM)}"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CompassArrow(pod.direction) }
                    Text(
                        pod.direction?.let { "Bússola: ${it.name}" } ?: "Bússola — comece a andar (a direção vem do GPS e só atualiza acima de ~5 km/h).",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ----- Preferências do Pod -----
        SectionTitle("Linha inferior na navegação")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectChip("Chegada", pod.bottomMode == PodBottomMode.ARRIVAL_TIME, { PodService.setBottomMode(context, PodBottomMode.ARRIVAL_TIME) })
            SelectChip("Tempo", pod.bottomMode == PodBottomMode.TIME_REMAINING, { PodService.setBottomMode(context, PodBottomMode.TIME_REMAINING) })
            SelectChip("Distância", pod.bottomMode == PodBottomMode.TOTAL_DISTANCE, { PodService.setBottomMode(context, PodBottomMode.TOTAL_DISTANCE) })
        }

        if (active) {
            SecondaryButton("Desconectar", { PodService.disconnect(context) })
        } else {
            PrimaryButton("Conectar ao Pod", connect)
        }
        if (pod.pairedName != null) {
            TextButton(onClick = { PodService.forgetPairing(context) }) { Text("Esquecer pareamento", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        HCard {
            Text("Como usar", style = MaterialTheme.typography.titleMedium)
            Text(
                "1. Ligue a ignição: o Pod só anuncia por ~30 s.\n" +
                    "2. Toque em Conectar. No primeiro uso, digite o PIN exibido no Pod.\n" +
                    "3. Na aba Mapa, escolha um destino e inicie a navegação: o Pod mostra a manobra, a distância e a chegada. Sem rota, ele mostra a bússola.\n" +
                    "O app reconecta sozinho se o link cair.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Atenção: o protocolo foi validado só no Pod da Meteor 350. Na Hunter 350 ainda não foi testado: " +
                    "faça um teste com a moto parada antes de depender disso na estrada.",
                style = MaterialTheme.typography.bodySmall, color = Warn,
            )
        }

        HCard {
            Text("Sobre", style = MaterialTheme.typography.titleMedium)
            Text(
                "Mapa: © OpenStreetMap contributors, OpenFreeMap, MapLibre. Rotas: Valhalla (servidor público do OSM). " +
                    "Busca: Photon (Komoot). Protocolo do Pod: baseado no WazeTripper (Apache-2.0).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(Modifier.height(24.dp))
    }
}

@Composable
private fun CompassArrow(direction: CompassDirection?) {
    val ring = MaterialTheme.colorScheme.outline
    val arrow = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(150.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(ring, radius = size.minDimension / 2 - 4.dp.toPx(), style = Stroke(width = 3.dp.toPx()))
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
