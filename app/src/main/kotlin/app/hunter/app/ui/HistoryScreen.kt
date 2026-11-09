package app.hunter.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hunter.app.HunterViewModel
import app.hunter.model.TrackPoint
import app.hunter.model.TripSummary
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

@Composable
fun HistoryScreen(vm: HunterViewModel) {
    val trips by vm.finishedTrips.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<Long?>(null) }
    BackHandler(enabled = selected != null) { selected = null }

    val sel = trips.firstOrNull { it.id == selected }
    if (sel != null) {
        TripDetail(vm, sel, onBack = { selected = null })
        return
    }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Histórico", style = MaterialTheme.typography.headlineSmall)
        if (trips.isEmpty()) {
            Text("Nenhum pedal gravado ainda. Toque em \"Iniciar pedal\" na primeira aba.")
        } else {
            val totalKm = trips.sumOf { it.stats.distanceKm }
            Text("${trips.size} pedais · ${fmtKm(totalKm, 0)} no total", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(trips, key = { it.id }) { t ->
                    HCard(Modifier.clickable { selected = t.id }) {
                        Text(fmtDateTime(t.startedAtMs), style = MaterialTheme.typography.titleSmall)
                        MetricRow(
                            "Distância" to fmtKm(t.stats.distanceKm),
                            "Tempo" to fmtDuration(t.stats.elapsedMs),
                            "Média" to fmtKmh(t.stats.avgSpeedKmh),
                        )
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
    LaunchedEffect(trip.id) { points = vm.points(trip.id) }
    val s = trip.stats

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Voltar") }
        }
        Text(fmtDateTime(trip.startedAtMs), style = MaterialTheme.typography.headlineSmall)

        HCard {
            val p = points
            when {
                p == null -> Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) { Text("Carregando…") }
                p.size < 2 -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { Text("Sem pontos suficientes para o mapa.") }
                else -> {
                    RouteCanvas(p, Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(12.dp)))
                    Text("Cor = velocidade (azul lento → laranja rápido). Verde: início · Vermelho: fim.", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        HCard {
            MetricRow("Distância" to fmtKm(s.distanceKm, 2), "Tempo total" to fmtDuration(s.elapsedMs), "Em movimento" to fmtDuration(s.movingTimeMs))
            MetricRow("Média" to fmtKmh(s.avgSpeedKmh), "Máxima" to fmtKmh(s.maxSpeedKmh), "Paradas" to "${s.stopCount} (${fmtDuration(s.stoppedTimeMs)})")
            MetricRow("Subida" to fmtM(s.elevationGainM), "Descida" to fmtM(s.elevationLossM), "" to "")
        }

        points?.takeIf { p -> p.count { it.altitudeM != null } > 2 }?.let { p ->
            SectionTitle("Altimetria")
            HCard { ElevationCanvas(p, Modifier.fillMaxWidth().height(110.dp)) }
        }

        OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Excluir pedal") }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Excluir este pedal?") },
            text = { Text("O trajeto e a distância dele saem do histórico e do hodômetro calculado.") },
            confirmButton = { TextButton(onClick = { vm.deleteTrip(trip.id); confirmDelete = false; onBack() }) { Text("Excluir") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

private val Slow = Color(0xFF4DA3FF)
private val Fast = Color(0xFFFF7A1A)

private fun lerp(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f,
)

/** Desenha o traçado projetado (sem mapa de fundo: funciona offline), colorido pela velocidade. */
@Composable
private fun RouteCanvas(points: List<TrackPoint>, modifier: Modifier) {
    val bg = MaterialTheme.colorScheme.surface
    // Reduz para ≤ 1500 pontos para desenhar rápido.
    val pts = remember(points) {
        val step = max(1, points.size / 1500)
        points.filterIndexed { i, _ -> i % step == 0 || i == points.lastIndex }
    }
    Canvas(modifier) {
        drawRect(bg)
        val minLat = pts.minOf { it.latitude }
        val maxLat = pts.maxOf { it.latitude }
        val minLon = pts.minOf { it.longitude }
        val maxLon = pts.maxOf { it.longitude }
        val kx = cos(Math.toRadians((minLat + maxLat) / 2)).toFloat()
        val dx = max(((maxLon - minLon) * kx).toFloat(), 1e-6f)
        val dy = max((maxLat - minLat).toFloat(), 1e-6f)
        val pad = 24.dp.toPx()
        val scale = min((size.width - 2 * pad) / dx, (size.height - 2 * pad) / dy)
        val offX = (size.width - dx * scale) / 2
        val offY = (size.height - dy * scale) / 2
        fun pos(p: TrackPoint) = Offset(
            offX + ((p.longitude - minLon) * kx).toFloat() * scale,
            offY + (maxLat - p.latitude).toFloat() * scale,
        )
        val vmax = max(pts.maxOf { it.speedMps ?: 0f }, 1f)
        val stroke = 4.dp.toPx()
        for (i in 1 until pts.size) {
            val t = ((pts[i].speedMps ?: 0f) / vmax).coerceIn(0f, 1f)
            drawLine(lerp(Slow, Fast, t), pos(pts[i - 1]), pos(pts[i]), strokeWidth = stroke, cap = StrokeCap.Round)
        }
        drawCircle(Color(0xFF3FA34D), 7.dp.toPx(), pos(pts.first()))
        drawCircle(Color(0xFFD64545), 7.dp.toPx(), pos(pts.last()))
    }
}

@Composable
private fun ElevationCanvas(points: List<TrackPoint>, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val alts = remember(points) {
        val a = points.mapNotNull { it.altitudeM }
        val step = max(1, a.size / 400)
        a.filterIndexed { i, _ -> i % step == 0 }
    }
    Canvas(modifier) {
        if (alts.size < 2) return@Canvas
        val lo = alts.min()
        val hi = max(alts.max(), lo + 1.0)
        val path = Path()
        alts.forEachIndexed { i, a ->
            val x = size.width * i / (alts.size - 1)
            val y = size.height * (1 - ((a - lo) / (hi - lo)).toFloat())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, line, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}
