package app.hunter.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hunter.app.HunterViewModel
import app.hunter.app.PreviewUi
import app.hunter.nav.Place
import app.hunter.nav.RouteProfile
import app.hunter.trips.service.TrackingService

private fun ridePerms() = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}

private val NAV_CAMERA = MapCamera.Follow(zoom = 17.0, tilt = 50.0, rotate = true)
private val REC_CAMERA = MapCamera.Follow(zoom = 16.0)
private val IDLE_CAMERA = MapCamera.Follow(zoom = 15.0)

@Composable
fun MapScreen(
    vm: HunterViewModel,
    friends: List<FriendMarker> = emptyList(),
) {
    val context = LocalContext.current
    val live by vm.live.collectAsStateWithLifecycle()
    val rec by vm.recording.collectAsStateWithLifecycle()
    val nav by vm.nav.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val attention by vm.attention.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val focusTarget by vm.focusTarget.collectAsStateWithLifecycle()
    val focusNonce by vm.focusNonce.collectAsStateWithLifecycle()

    var searching by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(true) }
    var nonce by remember { mutableIntStateOf(0) }
    var locationOk by remember { mutableStateOf(false) }

    LaunchedEffect(focusNonce) {
        if (focusTarget != null) {
            follow = false
            nonce++
        }
    }

    val hasLocation = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    val askLocation = rememberPermissionAction(ridePerms(), listOf(Manifest.permission.ACCESS_FINE_LOCATION)) { locationOk = true }
    val beginNav = rememberPermissionAction(ridePerms(), listOf(Manifest.permission.ACCESS_FINE_LOCATION)) {
        if (vm.commitNavigation()) {
            follow = true
            nonce++
            TrackingService.start(context)
        }
    }
    val startRecording = rememberPermissionAction(ridePerms(), listOf(Manifest.permission.ACCESS_FINE_LOCATION)) {
        follow = true
        nonce++
        TrackingService.start(context)
    }

    // GPS para o mapa enquanto o app está na tela (para ao ir para segundo plano; a gravação tem o próprio serviço).
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(hasLocation, locationOk, lifecycle) {
        val allowed = hasLocation || locationOk
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> if (allowed) vm.startLocation()
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> vm.stopLocation()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        if (allowed && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) vm.startLocation()
        onDispose { lifecycle.removeObserver(obs); vm.stopLocation() }
    }

    BackHandler(enabled = searching || preview != null) {
        if (searching) searching = false else vm.cancelPreview()
    }

    val navigating = nav.active
    // Estes valores são lembrados: sem isso a lista era recriada a cada fix de GPS (1 Hz) e o mapa
    // reenviava as rotas inteiras ao motor de renderização a cada segundo.
    val previewRoute = preview?.route
    val previewPlace = preview?.place
    val fitPoints = remember(previewRoute, previewPlace) {
        previewRoute?.shape ?: listOfNotNull(
            live?.let { app.hunter.nav.LatLon(it.latitude, it.longitude) },
            previewPlace?.latLon,
        )
    }
    val fitCamera = remember(fitPoints) { MapCamera.Fit(fitPoints) }
    val camera: MapCamera = when {
        preview != null -> fitCamera
        focusTarget != null && !follow -> MapCamera.Target(focusTarget!!.lat, focusTarget!!.lon)
        !follow -> MapCamera.Free
        navigating -> NAV_CAMERA
        else -> if (rec.isRecording) REC_CAMERA else IDLE_CAMERA
    }
    val altRoutes = remember(preview?.routes, preview?.selected) {
        preview?.let { p -> p.routes.filterIndexed { i, _ -> i != p.selected }.map { it.shape } } ?: emptyList()
    }

    Box(Modifier.fillMaxSize()) {
        HunterMap(
            modifier = Modifier.fillMaxSize(),
            me = live,
            friends = friends,
            route = if (navigating) nav.route?.shape else preview?.route?.shape,
            altRoutes = altRoutes,
            destination = if (navigating) nav.destination?.latLon else preview?.place?.latLon,
            camera = camera,
            cameraNonce = nonce,
            paddingTopDp = if (navigating) 200 else if (preview != null) 110 else 0,
            paddingBottomDp = if (navigating) 120 else if (preview != null) 440 else 0,
            onLongPress = { if (!navigating) vm.previewAt(it) },
            onUserGesture = {
                if (preview == null) {
                    follow = false
                    vm.clearFocus()
                }
            },
        )

        // ----- Navegação -----
        if (navigating) {
            NavBanner(vm, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp))
            NavBottom(vm, Modifier.align(Alignment.BottomCenter).padding(12.dp), onStop = { TrackingService.stop(context); vm.stopNavigationNow() })
            if (!follow) {
                CircleButton(
                    Icons.Default.LocationOn, "Centralizar", { follow = true; nonce++; vm.clearFocus() },
                    Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 190.dp),
                    container = MaterialTheme.colorScheme.primary, tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            if (nav.arrived) {
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text("Você chegou!") },
                    text = { Text(nav.destination?.name.orEmpty()) },
                    confirmButton = { TextButton(onClick = { TrackingService.stop(context); vm.stopNavigationNow() }) { Text("Concluir viagem") } },
                )
            }
        }

        // ----- Pré-visualização de rota -----
        val pv = preview
        if (pv != null && !navigating) {
            PreviewCard(vm, pv, onStart = beginNav, modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp))
        }

        // ----- Ocioso / gravando -----
        if (!navigating && pv == null) {
            Column(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp).fillMaxWidth(),
            ) {
                SearchPill(onClick = { searching = true })
                if (!online) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Warn.copy(alpha = 0.95f),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(
                            "Sem internet: rotas e busca indisponíveis",
                            Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium, color = Color.Black,
                        )
                    }
                }
            }

            Column(
                Modifier.align(Alignment.BottomCenter).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!follow) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        CircleButton(Icons.Default.LocationOn, "Centralizar", { follow = true; nonce++; vm.clearFocus() })
                    }
                }
                if (!hasLocation && !locationOk) {
                    FloatingCard {
                        Text("Permita a localização", style = MaterialTheme.typography.titleMedium)
                        Text("O mapa precisa saber onde você está para traçar rotas e gravar suas viagens.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PrimaryButton("Permitir localização", askLocation)
                    }
                } else {
                    IdleBottom(vm, recording = rec.isRecording, onRecord = startRecording, onStopRecording = { TrackingService.stop(context) }, attention = attention.size)
                }
            }
        }

        if (searching) {
            SearchOverlay(vm, onClose = { searching = false }, onPick = { vm.previewTo(it); searching = false })
        }
    }
}

// ---------------------------------------------------------------- busca

private val QUICK_SEARCHES = listOf("Posto de gasolina", "Restaurante", "Mecânica de moto", "Padaria", "Hotel", "Mirante")

/** Distância em linha reta até o lugar, para escolher o resultado certo de relance. */
@Composable
private fun DistanceLabel(p: Place, me: app.hunter.model.TrackPoint?) {
    if (me == null) return
    val m = app.hunter.model.Geo.distanceM(me.latitude, me.longitude, p.lat, p.lon)
    Text(fmtDist(m), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SearchPill(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(end = 56.dp).height(56.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        shadowElevation = 14.dp,
    ) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary)
            Text("Para onde?", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SearchOverlay(vm: HunterViewModel, onClose: () -> Unit, onPick: (Place) -> Unit) {
    val s by vm.search.collectAsStateWithLifecycle()
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    DisposableEffect(Unit) { onDispose { vm.clearSearch() } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding()) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
                TextField(
                    value = s.query,
                    onValueChange = vm::onQuery,
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    placeholder = { Text("Endereço, lugar ou cidade") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { s.results.firstOrNull()?.let(onPick) }),
                    shape = RoundedCornerShape(18.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    trailingIcon = {
                        if (s.query.isNotEmpty()) IconButton(onClick = { vm.onQuery("") }) { Icon(Icons.Default.Close, "Limpar") }
                    },
                )
            }
            if (s.loading) {
                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary)
            } else {
                Spacer(Modifier.height(2.dp))
            }
            s.error?.let {
                Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                if (s.query.trim().length >= 2) {
                    items(s.local) { p -> ListRow(p.name, p.subtitle, Icons.Default.Star, { onPick(p) }, trailing = { DistanceLabel(p, live) }) }
                    items(s.results.filter { r -> s.local.none { it.lat == r.lat && it.lon == r.lon } }) { p ->
                        ListRow(p.name, p.subtitle, Icons.Default.Place, { onPick(p) }, trailing = { DistanceLabel(p, live) })
                    }
                } else {
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(QUICK_SEARCHES) { q -> SelectChip(q, false, { vm.onQuery(q) }) }
                        }
                    }
                    if (favs.isNotEmpty()) {
                        item { SectionTitle("Favoritos", Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                        items(favs) { p -> ListRow(p.name, p.subtitle, Icons.Default.Star, { onPick(p) }) }
                    }
                    val rest = recents.filter { r -> favs.none { it.lat == r.lat && it.lon == r.lon } }
                    if (rest.isNotEmpty()) {
                        item { SectionTitle("Recentes", Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                        items(rest) { p -> ListRow(p.name, p.subtitle, Icons.Default.Place, { onPick(p) }) }
                    }
                    item {
                        Text(
                            "Dica: no mapa, segure o dedo em qualquer ponto para escolher o destino.",
                            Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- ocioso

@Composable
private fun IdleBottom(vm: HunterViewModel, recording: Boolean, onRecord: () -> Unit, onStopRecording: () -> Unit, attention: Int) {
    val recents by vm.recents.collectAsStateWithLifecycle()
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val rec by vm.recording.collectAsStateWithLifecycle()
    val quick = (favs + recents.filter { r -> favs.none { it.lat == r.lat && it.lon == r.lon } }).take(8)

    if (quick.isNotEmpty() && !recording) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(quick) { p ->
                Surface(
                    onClick = { vm.previewTo(p) },
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                    shadowElevation = 8.dp,
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(if (favs.any { it.lat == p.lat && it.lon == p.lon }) Icons.Default.Star else Icons.Default.Place, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(p.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                    }
                }
            }
        }
    }

    FloatingCard {
        if (recording) {
            val s = rec.stats
            val kmh = ((rec.lastPoint?.speedMps ?: 0f) * 3.6f).toInt()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(Bad, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(if (s.elapsedMs == 0L) "Aguardando GPS…" else "Gravando viagem", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text("$kmh", fontSize = 34.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                Text(" km/h", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MetricRow("Distância" to fmtKm(s.distanceKm), "Tempo" to fmtDuration(s.elapsedMs), "Máx." to fmtKmh(s.maxSpeedKmh))
            PrimaryButton("Encerrar viagem", onStopRecording, container = Bad, content = Color.White)
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Pronto para rodar", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (attention > 0) "$attention item(ns) de manutenção pedem atenção" else "Busque um destino ou grave uma viagem livre",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (attention > 0) Warn else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SecondaryButton("●  Gravar viagem", onRecord)
        }
    }
}

// ---------------------------------------------------------------- pré-visualização

@Composable
private fun PreviewCard(vm: HunterViewModel, pv: PreviewUi, onStart: () -> Unit, modifier: Modifier) {
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val isFav = vm.isFavorite(pv.place, favs)
    FloatingCard(modifier.heightIn(max = 540.dp), scrollable = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(pv.place.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                pv.place.subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = { vm.toggleFavorite(pv.place) }) {
                Icon(Icons.Default.Star, "Favoritar", tint = if (isFav) Warn else MaterialTheme.colorScheme.outline)
            }
            IconButton(onClick = vm::cancelPreview) { Icon(Icons.Default.Close, "Cancelar") }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(RouteProfile.entries) { prof -> SelectChip(prof.label, prof == pv.profile, { vm.setProfile(prof) }) }
        }
        Text(pv.profile.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        when {
            pv.loading -> Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Text("Calculando rota para moto…")
            }
            pv.error != null -> {
                Text(pv.error, color = Bad)
                SecondaryButton("Tentar de novo", vm::retryRoutes)
            }
            else -> {
                pv.routes.forEachIndexed { i, r ->
                    val sel = i == pv.selected
                    Surface(
                        onClick = { vm.selectRoute(i) },
                        shape = RoundedCornerShape(18.dp),
                        color = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(fmtEta(r.durationS), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                                Text(
                                    "${fmtDist(r.distanceM)} · chega às ${fmtArrival(r.durationS)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (r.hasToll) Pill("Pedágio", Warn)
                            if (r.hasHighway && !r.hasToll) Pill("Rodovia", Info)
                            if (!r.hasToll && !r.hasHighway) Pill("Estradas locais", Ok)
                        }
                    }
                }
                FuelAdviceBlock(vm, pv)
                PrimaryButton("Iniciar navegação", onStart)
            }
        }
    }
}

/** Parada escolhida e/ou aviso de autonomia com postos perto do ponto sugerido. */
@Composable
private fun FuelAdviceBlock(vm: HunterViewModel, pv: PreviewUi) {
    val via = pv.via
    if (via != null) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Place, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Parada: ${via.name}", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = vm::clearVia) { Text("Remover") }
            }
        }
        return
    }
    val f = pv.fuel
    when {
        f == null && pv.noFuelData -> Text(
            "Dica: registre um abastecimento de tanque cheio na Garagem para o app planejar paradas pela autonomia.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        f == null -> Unit
        !f.advice.needsStop -> Text(
            "Autonomia estimada ~${f.state.rangeKm.toInt()} km: dá para chegar sem abastecer." +
                if (f.state.usedDefaultConsumption) " (consumo genérico até você registrar mais abastecimentos)" else "",
            style = MaterialTheme.typography.bodySmall, color = Ok,
        )
        else -> Surface(shape = RoundedCornerShape(18.dp), color = Warn.copy(alpha = 0.16f)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (f.advice.refuelBeforeLeaving) "Tanque baixo: abasteça antes de sair" else "Abasteça no caminho",
                    style = MaterialTheme.typography.titleSmall, color = Warn,
                )
                Text(
                    "Autonomia ~${f.state.rangeKm.toInt()} km (${f.state.litersLeft.let { "%.1f".format(it) }} L estimados) · rota de ${(pv.route?.distanceM ?: 0.0).div(1000).toInt()} km. " +
                        "Procure um posto por volta do km ${(f.advice.stopAtKm ?: 0.0).toInt()}.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (f.loadingStations) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Buscando postos…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                f.stationsError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                f.stations.take(4).forEach { s ->
                    ListRow(s.name, s.subtitle, Icons.Default.Place, { vm.chooseStation(s) }, trailing = {
                        Text("Adicionar", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    })
                }
            }
        }
    }
}

// ---------------------------------------------------------------- navegação

@Composable
private fun NavBanner(vm: HunterViewModel, modifier: Modifier) {
    val nav by vm.nav.collectAsStateWithLifecycle()
    val p = nav.progress
    FloatingCard(modifier) {
        if (nav.rerouting || p == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Text(if (nav.rerouting) "Recalculando rota…" else "Preparando navegação…", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    Modifier.size(84.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(22.dp)),
                    contentAlignment = Alignment.Center,
                ) { ManeuverGlyph(p.upcoming.type, 58.dp, MaterialTheme.colorScheme.onPrimary) }
                Column(Modifier.weight(1f)) {
                    Text(fmtDist(p.distanceToUpcomingM), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
                    Text(
                        p.upcoming.instruction,
                        style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            p.then?.takeIf { !it.isDestination }?.let { next ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Depois", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ManeuverGlyph(next.type, 22.dp, MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(next.streetName ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun NavBottom(vm: HunterViewModel, modifier: Modifier, onStop: () -> Unit) {
    val nav by vm.nav.collectAsStateWithLifecycle()
    val rec by vm.recording.collectAsStateWithLifecycle()
    val voice by vm.voice.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    val p = nav.progress
    val kmh = ((rec.lastPoint?.speedMps ?: 0f) * 3.6f).toInt()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f), shadowElevation = 12.dp, modifier = Modifier.size(84.dp)) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("$kmh", fontSize = 30.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                    Text("km/h", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SelectChip(if (voice) "Voz ligada" else "Voz desligada", voice, vm::toggleVoice)
        }
        FloatingCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p?.let { fmtArrival(it.remainingS) } ?: "--:--", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
                    Text(
                        p?.let { "${fmtEta(it.remainingS)} · ${fmtDist(it.remainingM)}" } ?: "Calculando…",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    nav.destination?.let {
                        Text(it.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Surface(
                    onClick = { confirm = true },
                    shape = RoundedCornerShape(18.dp),
                    color = Bad,
                ) {
                    Box(Modifier.height(56.dp).padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
                        Text("Encerrar", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Encerrar navegação?") },
            text = { Text("A viagem gravada até aqui será salva no histórico.") },
            confirmButton = { TextButton(onClick = { confirm = false; onStop() }) { Text("Encerrar", color = Bad) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Continuar") } },
        )
    }
}
