package app.hunter.app.ui

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hunter.app.HunterViewModel
import app.hunter.model.DueState
import app.hunter.podble.PodPhase
import app.hunter.trips.service.TrackingService

private fun rideAllPerms() = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}

@Composable
fun RideScreen(vm: HunterViewModel, openTab: (Tab) -> Unit) {
    val context = LocalContext.current
    val rec by vm.recording.collectAsStateWithLifecycle()
    val odo by vm.odometerKm.collectAsStateWithLifecycle()
    val attention by vm.attention.collectAsStateWithLifecycle()
    val pod by vm.pod.collectAsStateWithLifecycle()

    val start = rememberPermissionAction(
        all = rideAllPerms(),
        required = listOf(Manifest.permission.ACCESS_FINE_LOCATION),
    ) { TrackingService.start(context) }

    val s = rec.stats
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Hunter Companion", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Pill(
                if (pod.phase == PodPhase.CONNECTED) "Pod conectado" else "Pod offline",
                if (pod.phase == PodPhase.CONNECTED) Ok else MaterialTheme.colorScheme.outline,
            )
        }

        HCard {
            val speedKmh = ((rec.lastPoint?.speedMps ?: 0f) * 3.6f).toInt()
            Text(
                if (!rec.isRecording) "Pronto para rodar"
                else if (s.elapsedMs == 0L) "Aguardando sinal de GPS…" else "Gravando",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (rec.isRecording) "$speedKmh" else "—",
                fontSize = 96.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Text("km/h", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
            if (rec.isRecording) {
                MetricRow("Distância" to fmtKm(s.distanceKm), "Tempo" to fmtDuration(s.elapsedMs), "Média" to fmtKmh(s.avgSpeedKmh))
                MetricRow("Máxima" to fmtKmh(s.maxSpeedKmh), "Subida" to fmtM(s.elevationGainM), "Paradas" to "${s.stopCount}")
            }
        }

        // Uma ação só, grande, sem confirmação: pensado para ser usado com a moto parada.
        if (rec.isRecording) {
            Button(
                onClick = { TrackingService.stop(context) },
                modifier = Modifier.fillMaxWidth().height(80.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Bad, contentColor = androidx.compose.ui.graphics.Color.White),
            ) { Text("Parar e salvar", fontSize = 22.sp) }
        } else {
            Button(onClick = start, modifier = Modifier.fillMaxWidth().height(80.dp)) {
                Text("Iniciar pedal", fontSize = 22.sp)
            }
        }

        HCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("Hodômetro", fmtKm(odo, 0))
                androidx.compose.material3.TextButton(onClick = { openTab(Tab.Maintenance) }) { Text("Ajustar") }
            }
        }

        if (attention.isNotEmpty()) {
            SectionTitle("Manutenção")
            HCard {
                attention.take(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(row.item.name, Modifier.weight(1f))
                        Pill(
                            if (row.status.state == DueState.OVERDUE) "Atrasado" else "Perto",
                            if (row.status.state == DueState.OVERDUE) Bad else Warn,
                        )
                    }
                }
                androidx.compose.material3.TextButton(onClick = { openTab(Tab.Maintenance) }) { Text("Ver tudo") }
            }
        }
    }
}
