package app.hunter.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hunter.app.HunterViewModel
import app.hunter.model.TrackPoint
import app.hunter.model.TripSummary
import app.hunter.nav.LatLon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

private fun tripName(t: TripSummary): String =
    t.title?.takeIf { it.isNotBlank() }
        ?: ("Viagem de " + SimpleDateFormat("EEEE", Locale("pt", "BR")).format(Date(t.startedAtMs)))

@Composable
fun TripsScreen(vm: HunterViewModel) {
    val trips by vm.finishedTrips.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<Long?>(null) }
    BackHandler(enabled = selected != null) { selected = null }

    val sel = trips.firstOrNull { it.id == selected }
    if (sel != null) {
        TripDetail(vm, sel, onBack = { selected = null })
        return
    }

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Viagens", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
        if (trips.isEmpty()) {
            HCard {
                Text("Nenhuma viagem ainda", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Na aba Mapa, trace uma rota ou toque em \"Gravar viagem\". Tudo fica salvo aqui, com o traçado e as estatísticas.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            HCard {
                Text("Total rodado", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(fmtKm(trips.sumOf { it.stats.distanceKm }, 0), style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
                MetricRow(
                    "Viagens" to "${trips.size}",
                    "Tempo em movimento" to fmtDuration(trips.sumOf { it.stats.movingTimeMs }),
                    "Vel. máxima" to fmtKmh(trips.maxOf { it.stats.maxSpeedKmh }),
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
                items(trips, key = { it.id }) { t ->
                    HCard(onClick = { selected = t.id }) {
                        Text(tripName(t), style = MaterialTheme.typography.titleMedium)
                        Text(fmtDateTime(t.startedAtMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                            Text(fmtKm(t.stats.distanceKm), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            Column(horizontalAlignment = Alignment.End) {
                                Text(fmtDuration(t.stats.elapsedMs), style = MaterialTheme.typography.titleMedium)
                                Text("média ${fmtKmh(t.stats.avgSpeedKmh)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TripDetail(vm: HunterViewModel, trip: TripSummary, onBack: () -> Unit) {
    var points by remember(trip.id) { mutableStateOf<List<TrackPoint>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    LaunchedEffect(trip.id) { points = vm.points(trip.id) }
    val s = trip.stats

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().height(340.dp)) {
            val p = points
            if (p != null && p.size >= 2) {
                HunterMap(
                    modifier = Modifier.fillMaxSize(),
                    trail = p,
                    camera = MapCamera.Fit(p.map { LatLon(it.latitude, it.longitude) }),
                    paddingTopDp = 70,
                    interactive = false, // dentro de uma tela rolável: o gesto é para rolar a página
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (p == null) "Carregando…" else "Sem pontos suficientes para o mapa.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onBack, modifier = Modifier.padding(8.dp).align(Alignment.TopStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
            }
        }

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tripName(trip), style = MaterialTheme.typography.headlineMedium)
            Text(fmtDateTime(trip.startedAtMs), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Cor do traçado: azul = devagar, laranja = rápido.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            HCard {
                Text(fmtKm(s.distanceKm, 2), style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
                MetricRow("Tempo total" to fmtDuration(s.elapsedMs), "Em movimento" to fmtDuration(s.movingTimeMs), "Paradas" to "${s.stopCount}")
                MetricRow("Média" to fmtKmh(s.avgSpeedKmh), "Máxima" to fmtKmh(s.maxSpeedKmh), "Tempo parado" to fmtDuration(s.stoppedTimeMs))
                MetricRow("Subida" to fmtM(s.elevationGainM), "Descida" to fmtM(s.elevationLossM), "" to "")
            }

            points?.takeIf { pts -> pts.count { it.altitudeM != null } > 2 }?.let { pts ->
                SectionTitle("Altimetria")
                HCard { ElevationCanvas(pts, Modifier.fillMaxWidth().height(120.dp)) }
            }

            SecondaryButton("Renomear", { renaming = true })
            SecondaryButton("Excluir viagem", { confirmDelete = true }, color = Bad)
            Box(Modifier.height(16.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Excluir esta viagem?") },
            text = { Text("O traçado e a distância dela saem do histórico e do hodômetro calculado.") },
            confirmButton = { TextButton(onClick = { vm.deleteTrip(trip.id); confirmDelete = false; onBack() }) { Text("Excluir", color = Bad) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
    if (renaming) {
        var name by remember { mutableStateOf(trip.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Nome da viagem") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) },
            confirmButton = { TextButton(onClick = { vm.renameTrip(trip.id, name.trim()); renaming = false }) { Text("Salvar") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun ElevationCanvas(points: List<TrackPoint>, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    val alts = remember(points) {
        val a = points.mapNotNull { it.altitudeM }
        val step = max(1, a.size / 400)
        a.filterIndexed { i, _ -> i % step == 0 }
    }
    Canvas(modifier.clip(RoundedCornerShape(12.dp))) {
        if (alts.size < 2) return@Canvas
        val lo = alts.min()
        val hi = max(alts.max(), lo + 1.0)
        val path = Path()
        val area = Path()
        alts.forEachIndexed { i, a ->
            val x = size.width * i / (alts.size - 1)
            val y = size.height * 0.08f + size.height * 0.84f * (1 - ((a - lo) / (hi - lo)).toFloat())
            if (i == 0) { path.moveTo(x, y); area.moveTo(x, size.height); area.lineTo(x, y) } else { path.lineTo(x, y); area.lineTo(x, y) }
        }
        area.lineTo(size.width, size.height)
        area.close()
        drawPath(area, fill)
        drawPath(path, line, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}
