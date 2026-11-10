package app.hunter.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hunter.app.HunterViewModel
import app.hunter.app.R
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 4.dp),
        ) {
            Text(
                text = "Viagens",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Histórico e telemetria de trajetos gravados",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (trips.isEmpty()) {
            HCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SquircleIcon(
                        iconRes = R.drawable.fa_route,
                        backgroundColor = NovareAccent.copy(alpha = 0.15f),
                        tintColor = NovareAccent,
                        contentDescription = "Trajeto",
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Nenhuma viagem ainda",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Grave seus trajetos pelo mapa",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = "Na aba Mapa, trace uma rota ou toque em \"Gravar viagem\". Tudo fica salvo aqui, com o traçado completo no mapa e as estatísticas detalhadas de velocidade e altitude.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        } else {
            // Hero card com hodômetro total das viagens gravadas
            HCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "TOTAL RODADO",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.2.sp,
                    )
                    StatusPill(
                        text = "${trips.size} ${if (trips.size == 1) "viagem" else "viagens"}",
                        active = true,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = String.format(Locale.getDefault(), "%.0f", trips.sumOf { it.stats.distanceKm }),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = " km",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                MetricRow(
                    "Tempo em movimento" to fmtDuration(trips.sumOf { it.stats.movingTimeMs }),
                    "Velocidade máxima" to fmtKmh(trips.maxOf { it.stats.maxSpeedKmh }),
                    "Distância média" to fmtKm(trips.map { it.stats.distanceKm }.average(), 1),
                )
            }

            SectionTitle("Histórico de viagens")

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(trips, key = { it.id }) { t ->
                    HCard(onClick = { selected = t.id }) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SquircleIcon(
                                iconRes = R.drawable.fa_route,
                                backgroundColor = NovareAccent.copy(alpha = 0.15f),
                                tintColor = NovareAccent,
                                contentDescription = "Rota",
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = tripName(t),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = fmtDateTime(t.startedAtMs),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        // Métricas tabulares organizadas
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            Column {
                                Text(
                                    text = "DISTÂNCIA",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    letterSpacing = 0.8.sp,
                                )
                                Text(
                                    text = fmtKm(t.stats.distanceKm),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "DURAÇÃO",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    letterSpacing = 0.8.sp,
                                )
                                Text(
                                    text = fmtDuration(t.stats.elapsedMs),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "VEL. MÉDIA",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    letterSpacing = 0.8.sp,
                                )
                                Text(
                                    text = fmtKmh(t.stats.avgSpeedKmh),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Bloco do Mapa com botão de voltar sobreposto
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp),
        ) {
            val p = points
            val placeholder = tripMapPlaceholder(p?.size)
            if (p != null && placeholder == null) {
                HunterMap(
                    modifier = Modifier.fillMaxSize(),
                    trail = p,
                    camera = MapCamera.Fit(p.map { LatLon(it.latitude, it.longitude) }),
                    paddingTopDp = 70,
                    interactive = false, // dentro de uma tela rolável: o gesto é para rolar a página
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = placeholder.orEmpty(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            }
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .padding(12.dp)
                    .size(40.dp)
                    .align(Alignment.TopStart),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SquircleIcon(
                    iconRes = R.drawable.fa_route,
                    backgroundColor = NovareAccent.copy(alpha = 0.15f),
                    tintColor = NovareAccent,
                    contentDescription = "Viagem",
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = tripName(trip),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = fmtDateTime(trip.startedAtMs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = "Cor do traçado no mapa: azul = devagar, laranja = rápido.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Cartão de métricas detalhadas
            HCard {
                Text(
                    text = "DISTÂNCIA PERCORRIDA",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.2.sp,
                )
                Text(
                    text = fmtKm(s.distanceKm, 2),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary,
                )
                MetricRow(
                    "Tempo total" to fmtDuration(s.elapsedMs),
                    "Em movimento" to fmtDuration(s.movingTimeMs),
                    "Paradas" to "${s.stopCount}",
                )
                MetricRow(
                    "Vel. média" to fmtKmh(s.avgSpeedKmh),
                    "Vel. máxima" to fmtKmh(s.maxSpeedKmh),
                    "Tempo parado" to fmtDuration(s.stoppedTimeMs),
                )
                MetricRow(
                    "Ganho de altitude" to fmtM(s.elevationGainM),
                    "Perda de altitude" to fmtM(s.elevationLossM),
                    "" to "",
                )
            }

            // Altimetria gráfica
            points?.takeIf { pts -> pts.count { it.altitudeM != null } > 2 }?.let { pts ->
                SectionTitle("Perfil de Altitude")
                HCard {
                    ElevationCanvas(pts, Modifier.fillMaxWidth().height(120.dp))
                }
            }

            SectionTitle("Gerenciar Trajeto")
            RumoSecondaryButton(
                text = "Renomear viagem",
                onClick = { renaming = true },
                leadingIconRes = R.drawable.fa_wrench,
            )
            Surface(
                onClick = { confirmDelete = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(RUMO_SECONDARY_BUTTON_HEIGHT_DP.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, NovareError.copy(alpha = 0.5f)),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Excluir viagem",
                        color = NovareError,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Excluir esta viagem?") },
            text = { Text("O traçado e a distância dela saem do histórico e do hodômetro calculado.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteTrip(trip.id); confirmDelete = false; onBack() }) {
                    Text("Excluir", color = NovareError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
    if (renaming) {
        var name by remember { mutableStateOf(trip.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Nome da viagem") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.renameTrip(trip.id, name.trim()); renaming = false }) {
                    Text("Salvar", color = NovareAccent, fontWeight = FontWeight.Bold)
                }
            },
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
            if (i == 0) {
                path.moveTo(x, y)
                area.moveTo(x, size.height)
                area.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        area.lineTo(size.width, size.height)
        area.close()
        drawPath(area, fill)
        drawPath(path, line, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}
