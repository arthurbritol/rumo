package app.hunter.app.ui

import android.Manifest
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hunter.app.HunterViewModel
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
    var pin by remember { mutableStateOf("") }

    val connect = rememberPermissionAction(podPerms(), podRequired()) { PodService.connect(context) }
    val active = pod.phase != PodPhase.IDLE

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Tripper Pod", style = MaterialTheme.typography.headlineSmall)

        HCard {
            val (label, color) = when (pod.phase) {
                PodPhase.CONNECTED -> "Conectado" to Ok
                PodPhase.AWAITING_PIN -> "Aguardando PIN" to Warn
                PodPhase.SCANNING -> "Procurando…" to Warn
                PodPhase.CONNECTING -> "Conectando…" to Warn
                PodPhase.IDLE -> "Desconectado" to MaterialTheme.colorScheme.outline
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Metric("Estado", label, Modifier.weight(1f))
                Pill(label, color)
            }
            pod.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            pod.pairedName?.let { Text("Pod pareado: $it", style = MaterialTheme.typography.labelMedium) }
        }

        if (pod.phase == PodPhase.AWAITING_PIN) {
            HCard {
                Text("Digite o PIN que aparece na tela do Pod:")
                OutlinedTextField(
                    value = pin, onValueChange = { pin = it.filter(Char::isLetterOrDigit).take(6) },
                    singleLine = true, label = { Text("PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { PodService.submitPin(context, pin); pin = "" }, enabled = pin.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text("Confirmar")
                }
            }
        }

        if (pod.phase == PodPhase.CONNECTED) {
            HCard {
                Text("Bússola no Pod", style = MaterialTheme.typography.titleSmall)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CompassArrow(pod.direction) }
                Text(
                    pod.direction?.let { "Direção enviada: ${it.name}" } ?: "Comece a andar: a direção vem do GPS e só atualiza em movimento (>5 km/h).",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        if (active) {
            OutlinedButton(onClick = { PodService.disconnect(context) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Desconectar", fontSize = 16.sp)
            }
        } else {
            Button(onClick = connect, modifier = Modifier.fillMaxWidth().height(64.dp)) { Text("Conectar ao Pod", fontSize = 18.sp) }
        }
        if (pod.pairedName != null) {
            TextButton(onClick = { PodService.forgetPairing(context) }) { Text("Esquecer pareamento") }
        }

        HCard {
            Text("Como usar", style = MaterialTheme.typography.titleSmall)
            Text(
                "1. Ligue a ignição: o Pod só anuncia por ~30 s.\n" +
                    "2. Toque em Conectar. No primeiro uso, digite o PIN exibido no Pod.\n" +
                    "3. Com a conexão ativa, ande: a bússola do Pod segue o rumo do GPS.\n" +
                    "O app reconecta sozinho se o link cair.",
            )
            Text(
                "Atenção: o protocolo foi validado só no Pod da Meteor 350. Na Hunter 350 ainda não foi testado " +
                    "(a moto chega em 30/11/2026): faça o teste de bancada antes de depender disso na estrada.",
                style = MaterialTheme.typography.labelMedium, color = Warn,
            )
        }
    }
}

@Composable
private fun CompassArrow(direction: CompassDirection?) {
    val ring = MaterialTheme.colorScheme.onSurfaceVariant
    val arrow = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(150.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(ring, radius = size.minDimension / 2 - 4.dp.toPx(), style = Stroke(width = 2.dp.toPx()))
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
