package app.hunter.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
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
import androidx.lifecycle.viewmodel.compose.viewModel
import app.hunter.app.BuildConfig
import app.hunter.app.HunterApp
import app.hunter.app.HunterViewModel
import app.hunter.app.PreviewUi
import app.hunter.app.R
import app.hunter.app.account.AuthState
import app.hunter.app.fuel.ResolvedFuelStation
import app.hunter.app.nearby.EncontroUiState
import app.hunter.app.nearby.NearbyUiState
import app.hunter.app.nearby.NearbyViewModel
import app.hunter.app.nearby.avisoPontoLonge
import app.hunter.app.nearby.distanciaAproximada
import app.hunter.app.nearby.nearbyViewModelFactory
import app.hunter.nearby.Ponto
import app.hunter.nearby.Preferencia
import app.hunter.nav.Place
import app.hunter.nav.RouteProfile
import app.hunter.trips.service.TrackingService

private fun ridePerms() = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}

private val NAV_CAMERA = MapCamera.Follow(zoom = 17.0, tilt = 50.0, rotate = true, adaptive = true)
private val REC_CAMERA = MapCamera.Follow(zoom = 16.0)
private val IDLE_CAMERA = MapCamera.Follow(zoom = 15.0)

@Composable
fun MapScreen(
    vm: HunterViewModel,
    friends: List<FriendMarker> = emptyList(),
    nearbyVm: NearbyViewModel = viewModel(factory = nearbyViewModelFactory(LocalContext.current.applicationContext as HunterApp)),
    /** Para o convite de amizade que nasce de um encontro ("Quero ser amigo" das duas pessoas). */
    social: app.hunter.app.social.SocialViewModel? = null,
) {
    val context = LocalContext.current
    if (social != null) LaunchedEffect(nearbyVm, social) { nearbyVm.convidarAmigo = social::conviteDeEncontro }
    val live by vm.live.collectAsStateWithLifecycle()
    val rec by vm.recording.collectAsStateWithLifecycle()
    val nav by vm.nav.collectAsStateWithLifecycle()
    val alertaPerigo by vm.alertaPerigo.collectAsStateWithLifecycle()
    val perigosNoMapa by vm.perigosNoMapa.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val attention by vm.attention.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val focusTarget by vm.focusTarget.collectAsStateWithLifecycle()
    val focusNonce by vm.focusNonce.collectAsStateWithLifecycle()
    val fuelLayerEnabled by vm.fuelLayerEnabled.collectAsStateWithLifecycle()
    val trafficEnabled by vm.trafficEnabled.collectAsStateWithLifecycle()
    val trafficTileUrl = remember { TrafficLayer.template() }
    val isSearchingFuelOnline by vm.isSearchingFuelOnline.collectAsStateWithLifecycle()
    val fuelSearchStatus by vm.fuelSearchStatus.collectAsStateWithLifecycle()
    val stateAverages by vm.stateAverages.collectAsStateWithLifecycle()
    val cityAverages by vm.cityAverages.collectAsStateWithLifecycle()
    val stationReference by vm.stationReference.collectAsStateWithLifecycle()
    val visibleFuelStations by vm.visibleFuelStations.collectAsStateWithLifecycle()
    val selectedFuelStation by vm.selectedFuelStation.collectAsStateWithLifecycle()

    // Rolê por perto: o botão só existe com o servidor configurado e com conta logada (como o de trânsito).
    val authState by (context.applicationContext as HunterApp).account.gateway.state.collectAsStateWithLifecycle()
    val signedIn = authState is AuthState.SignedIn
    val nearbyAvailable = BuildConfig.NEARBY_URL.isNotBlank() && signedIn
    val nearbyState by nearbyVm.estado.collectAsStateWithLifecycle()
    val nearbyRiders = (nearbyState as? NearbyUiState.Procurando)?.vizinhos.orEmpty()
    val nearbyPref by nearbyVm.preferencia.collectAsStateWithLifecycle()
    var showNearbySheet by remember { mutableStateOf(false) }
    var selectedRider by remember { mutableStateOf<String?>(null) }
    val rider = selectedRider?.let { id -> nearbyRiders.firstOrNull { it.sessao == id } }
    var pendingNearbyPref by remember { mutableStateOf<Preferencia?>(null) }
    // Encontros: proposta tocada no mapa, ponto marcado com o dedo para propor, e o encontro do usuário.
    val encontro by nearbyVm.encontro.collectAsStateWithLifecycle()
    val nearbyAviso by nearbyVm.aviso.collectAsStateWithLifecycle()
    val nearbyEnviando by nearbyVm.enviando.collectAsStateWithLifecycle()
    val fimPessoas by nearbyVm.fimPessoas.collectAsStateWithLifecycle()
    val amigosMarcados by nearbyVm.amigosMarcados.collectAsStateWithLifecycle()
    val nearbyProposals = (nearbyState as? NearbyUiState.Procurando)?.propostas.orEmpty()
    var selectedProposal by remember { mutableStateOf<String?>(null) }
    var proposalPoint by remember { mutableStateOf<app.hunter.nav.LatLon?>(null) }
    val proposal = selectedProposal?.let { id -> nearbyProposals.firstOrNull { it.id == id } }
    val activeEncounter = encontro as? EncontroUiState.Ativo
    val encounterRiders = activeEncounter?.encontro?.let { e -> e.participantes.filter { it.sessao != e.eu } }.orEmpty()
    // Cartões de propor/entrar substituem o painel inferior (como o da moto tocada); o do encontro convive com ele.
    val nearbyCardShown = proposalPoint != null || proposal != null
    var encounterExpanded by remember { mutableStateOf(true) }
    LaunchedEffect(activeEncounter == null) { if (activeEncounter != null) encounterExpanded = true }
    val encounterCardShown = !nearbyCardShown && (encontro is EncontroUiState.Encerrado || (activeEncounter != null && encounterExpanded))

    var searching by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(true) }
    var nonce by remember { mutableIntStateOf(0) }
    var locationOk by remember { mutableStateOf(false) }
    var editingFuelStation by remember { mutableStateOf<ResolvedFuelStation?>(null) }

    LaunchedEffect(focusNonce) {
        if (focusTarget != null) {
            follow = false
            nonce++
        }
    }

    // Saiu da conta com a procura ligada: para tudo.
    LaunchedEffect(signedIn) { if (!signedIn) nearbyVm.desligar() }
    LaunchedEffect(nearbyState) {
        if (nearbyState is NearbyUiState.Desligado) {
            selectedRider = null
            selectedProposal = null
            proposalPoint = null
        }
    }
    // O encontro começou (ou a proposta foi enviada): fecha o que estava aberto para propor ou entrar.
    LaunchedEffect(encontro) {
        // Aguardando: os cartões de propor/entrar ficam ("Propondo…" / "Entrando…"); só o encontro ativo os fecha.
        if (encontro is EncontroUiState.Ativo) {
            selectedProposal = null
            proposalPoint = null
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
    val startNearby = rememberPermissionAction(ridePerms(), listOf(Manifest.permission.ACCESS_FINE_LOCATION)) {
        pendingNearbyPref?.let { nearbyVm.ligar(it) }
        pendingNearbyPref = null
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

    BackHandler(enabled = searching || preview != null || selectedFuelStation != null || editingFuelStation != null) {
        when {
            editingFuelStation != null -> editingFuelStation = null
            searching -> searching = false
            selectedFuelStation != null -> vm.selectFuelStation(null)
            preview != null -> vm.cancelPreview()
        }
    }

    val navigating = nav.active
    // Navegação enxuta: o que aparece depende de estar navegando e da orientação (ver NavLayout.kt).
    val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val el = navegacaoEnxuta(
        EstadoDaTela(navegando = navigating, encontroAtivo = activeEncounter != null, seguindo = follow, gravando = rec.isRecording),
        if (landscape) Orientacao.Paisagem else Orientacao.Retrato,
    )
    // Ao começar a navegar, nada fica selecionado: os cartões não reaparecem do nada ao encerrar.
    LaunchedEffect(navigating) {
        if (navigating) {
            selectedRider = null
            selectedProposal = null
            proposalPoint = null
        }
    }
    val leftInset = WindowInsets.safeDrawing.asPaddingValues().calculateLeftPadding(LayoutDirection.Ltr)
    val sidePanelDp = if (el.painelLateral) NAV_PANEL_WIDTH.value.toInt() + leftInset.value.toInt() + 8 else 0
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

    // Altura (a partir de baixo) dos botões flutuantes da direita; o cartão de perigo usa a mesma, do outro lado.
    val controlsBottom = when {
        navigating -> if (landscape) 16.dp else 104.dp
        selectedFuelStation != null -> 340.dp
        rider != null && !navigating -> 250.dp
        nearbyCardShown && !navigating -> 300.dp
        encounterCardShown && !navigating -> 430.dp
        rec.isRecording -> 210.dp
        else -> 190.dp
    }

    Box(Modifier.fillMaxSize()) {
        HunterMap(
            modifier = Modifier.fillMaxSize(),
            me = live,
            friends = friends,
            nearby = nearbyRiders,
            proposals = nearbyProposals,
            encounterPoint = activeEncounter?.encontro?.ponto ?: proposalPoint?.let { Ponto(it.lat, it.lon) },
            encounterPointLabel = if (activeEncounter != null) "Ponto do encontro" else "Ponto escolhido",
            encounterRiders = encounterRiders,
            nearbySearching = nearbyState !is NearbyUiState.Desligado,
            fuelStations = if (fuelLayerEnabled) visibleFuelStations else emptyList(),
            perigos = perigosNoMapa,
            stateAverages = if (fuelLayerEnabled) stateAverages else emptyList(),
            cityAverages = if (fuelLayerEnabled) cityAverages else emptyList(),
            trafficTileUrl = trafficTileUrl,
            showTraffic = trafficEnabled,
            route = if (navigating) nav.route?.shape else preview?.route?.shape,
            altRoutes = altRoutes,
            destination = if (navigating) nav.destination?.latLon else preview?.place?.latLon,
            navProgress = if (navigating) nav.progress else null,
            camera = camera,
            cameraNonce = nonce,
            paddingTopDp = when {
                el.bannerManobra -> 172
                navigating -> 24
                preview != null -> 110
                else -> 0
            },
            paddingBottomDp = when {
                el.barraInferior -> 100
                navigating -> 24
                preview != null -> 440
                selectedFuelStation != null -> 360
                else -> 0
            },
            paddingStartDp = sidePanelDp,
            onLongPress = {
                if (!navigating) {
                    // Procurando rolê e fora de um encontro, segurar no mapa marca o ponto de uma proposta.
                    if (nearbyState is NearbyUiState.Procurando && (encontro is EncontroUiState.Nenhum || encontro is EncontroUiState.Encerrado)) {
                        proposalPoint = it
                        selectedRider = null
                        selectedProposal = null
                        nearbyVm.limparFim()
                    } else {
                        vm.previewAt(it)
                    }
                }
            },
            onFuelStationClick = { if (!navigating) vm.selectFuelStation(it) },
            onNearbyClick = { if (!navigating) { selectedRider = it; selectedProposal = null } },
            onProposalClick = {
                // Em encontro (ou esperando resposta) as propostas dos outros não abrem cartão.
                if (!navigating && (encontro is EncontroUiState.Nenhum || encontro is EncontroUiState.Encerrado)) {
                    nearbyVm.limparFim() // o cartão de fim sai e o da proposta aparece
                    selectedProposal = it
                    selectedRider = null
                    proposalPoint = null
                }
            },
            onBoundingBoxChanged = { vm.updateMapBoundingBox(it) },
            onUserGesture = {
                if (preview == null) {
                    follow = false
                    vm.clearFocus()
                }
            },
        )

        // Controles flutuantes na lateral do mapa (Camada de postos + Centralizar)
        if (!searching && preview == null && (el.camadas || el.recentralizar)) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .then(if (navigating) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom)) else Modifier)
                    .padding(end = 16.dp, bottom = controlsBottom),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.End,
            ) {
                if (el.camadas) SquircleIconButton(
                    iconRes = R.drawable.fa_gas_pump,
                    description = "Camada de postos de combustível",
                    selected = fuelLayerEnabled,
                    onClick = { vm.toggleFuelLayer() },
                )
                if (el.camadas && trafficTileUrl != null) {
                    SquircleIconButton(
                        iconRes = R.drawable.fa_traffic_light,
                        description = "Camada de trânsito",
                        selected = trafficEnabled,
                        onClick = { vm.toggleTraffic() },
                    )
                }
                if (el.camadas && nearbyAvailable) {
                    SquircleIconButton(
                        iconRes = R.drawable.fa_motorcycle,
                        description = "Procurar rolê",
                        selected = nearbyState !is NearbyUiState.Desligado,
                        onClick = { showNearbySheet = true },
                    )
                }
                if (el.recentralizar) {
                    CircleButton(
                        icon = Icons.Default.LocationOn,
                        description = "Centralizar",
                        onClick = { follow = true; nonce++; vm.clearFocus() },
                        container = MaterialTheme.colorScheme.primary,
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }

        // ----- Aviso do próximo radar/lombada/travessia -----
        // Navegando: sempre que houver (essencial). Só gravando: apenas com a tela livre de cartões (que ocupam a mesma faixa).
        val alerta = alertaPerigo
        val livreParaAviso = navigating || (!searching && preview == null && selectedFuelStation == null && rider == null &&
            !nearbyCardShown && !encounterCardShown)
        if (el.alertaPerigo && alerta != null && livreParaAviso) {
            PerigoCard(
                alerta,
                Modifier
                    .align(Alignment.BottomStart)
                    .then(if (navigating) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Bottom)) else Modifier)
                    .padding(start = if (el.painelLateral) NAV_PANEL_WIDTH + 12.dp else 16.dp, bottom = controlsBottom),
            )
        }

        // ----- Navegação -----
        if (navigating) {
            val stopNav = { TrackingService.stop(context); vm.stopNavigationNow() }
            if (el.bannerManobra) NavBannerCompact(vm, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp))
            if (el.barraInferior) NavBarCompact(vm, onStop = stopNav, modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp))
            if (el.painelLateral) NavSidePanel(vm, onStop = stopNav, leftInset = leftInset, modifier = Modifier.align(Alignment.CenterStart))
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

        // ----- Card de Detalhes do Posto -----
        AnimatedVisibility(
            visible = selectedFuelStation != null && el.ocioso,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp),
        ) {
            selectedFuelStation?.let { station ->
                val distM = live?.let { loc ->
                    app.hunter.model.Geo.distanceM(loc.latitude, loc.longitude, station.lat, station.lon)
                }
                FuelStationCard(
                    station = station,
                    distanceM = distM,
                    onNavigate = { vm.navigateToStation(station) },
                    onAddStop = { vm.addStationAsStop(station) },
                    onClose = { vm.selectFuelStation(null) },
                    onEditPrices = { editingFuelStation = station },
                    cityReference = stationReference,
                )
            }
        }

        // ----- Rolê por perto: cartão da moto tocada e folha de ajustes -----
        if (rider != null && el.ocioso && pv == null && selectedFuelStation == null) {
            NearbyRiderCard(
                vizinho = rider,
                distancia = live?.let { distanciaAproximada(it.latitude, it.longitude, rider) },
                onClose = { selectedRider = null },
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                enviando = nearbyEnviando,
                onBloquear = {
                    nearbyVm.bloquear(rider.sessao)
                    selectedRider = null
                },
                onDenunciar = { motivo -> nearbyVm.denunciar(rider.sessao, motivo) },
            )
        }
        // O cartão do encontro fica acima do painel inferior (que continua lá); com navegação ativa, só a pílula de resumo.
        // Navegando: só a pílula de resumo, compacta (retrato: logo abaixo do banner; paisagem: canto superior direito).
        if (el.pilulaEncontro) {
            (encontro as? EncontroUiState.Ativo)?.let { e ->
                val chipMod = if (landscape) {
                    Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End)).padding(8.dp)
                } else {
                    Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 112.dp)
                }
                NearbyEncounterChip(e, onClick = { encounterExpanded = true }, modifier = chipMod)
            }
        }
        if (el.ocioso && pv == null && selectedFuelStation == null && rider == null) {
            val acima = Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = if (rec.isRecording) 220.dp else 200.dp)
            val e = encontro
            if (e is EncontroUiState.Ativo) {
                if (encounterCardShown) {
                    NearbyEncounterCard(
                        e,
                        onSair = nearbyVm::sair,
                        onMinimizar = { encounterExpanded = false },
                        modifier = acima,
                        enviando = nearbyEnviando,
                        onBloquear = nearbyVm::bloquear,
                        onDenunciar = nearbyVm::denunciar,
                        onIrAtePonto = { vm.previewAt(app.hunter.nav.LatLon(e.encontro.ponto.lat, e.encontro.ponto.lon)) },
                    )
                }
            } else if (e is EncontroUiState.Encerrado && encounterCardShown && el.ocioso) {
                NearbyEndCard(
                    e.motivo,
                    onClose = nearbyVm::limparFim,
                    modifier = acima,
                    pessoas = fimPessoas,
                    enviando = nearbyEnviando,
                    onBloquear = nearbyVm::bloquear,
                    onDenunciar = nearbyVm::denunciar,
                    marcadas = amigosMarcados,
                    onQueroAmigo = nearbyVm::querSerAmigo,
                )
            }
        }
        if (nearbyCardShown && el.ocioso && pv == null && selectedFuelStation == null && rider == null) {
            val cardModifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
            if (encontro is EncontroUiState.Aguardando || encontro is EncontroUiState.Nenhum) {
                val ponto = proposalPoint
                if (ponto != null) {
                    NearbyCreateProposalCard(
                        aguardando = encontro is EncontroUiState.Aguardando,
                        onPropor = { vagas -> nearbyVm.proporPonto(ponto.lat, ponto.lon, vagas) },
                        onClose = { proposalPoint = null },
                        modifier = cardModifier,
                        aviso = avisoPontoLonge(live?.latitude, live?.longitude, ponto.lat, ponto.lon, nearbyPref?.raioKm ?: RAIO_PADRAO_KM),
                    )
                } else if (proposal != null) {
                    NearbyProposalCard(
                        proposta = proposal,
                        aguardando = encontro is EncontroUiState.Aguardando,
                        onEntrar = { nearbyVm.entrar(proposal.id) },
                        onClose = { selectedProposal = null },
                        modifier = cardModifier,
                    )
                }
            }
        }
        if (showNearbySheet && nearbyAvailable) {
            NearbySheet(
                estado = nearbyState,
                preferencia = nearbyPref,
                onLigar = {
                    pendingNearbyPref = it
                    startNearby()
                },
                onDesligar = { nearbyVm.desligar() },
                onMudarRaio = nearbyVm::mudarRaio,
                onDismiss = { showNearbySheet = false },
            )
        }

        editingFuelStation?.let { st ->
            EditFuelPricesDialog(
                station = st,
                onDismiss = { editingFuelStation = null },
                onSave = { gc, ga, et ->
                    vm.updateStationPrices(st.id, gc, ga, et)
                    editingFuelStation = null
                },
            )
        }

        // ----- Ocioso / gravando -----
        if (el.ocioso && pv == null && selectedFuelStation == null) {
            Column(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp).fillMaxWidth(),
            ) {
                SearchPill(onClick = { searching = true })
                if (nearbyAvailable) {
                    NearbyStrip(nearbyState, onClick = { showNearbySheet = true }, modifier = Modifier.padding(top = 8.dp))
                    nearbyAviso?.let { NearbyNotice(it, onClose = nearbyVm::limparAviso, modifier = Modifier.padding(top = 8.dp)) }
                    // Encontro minimizado: pílula com o resumo; tocar reabre o cartão.
                    val ativo = activeEncounter
                    if (ativo != null && !encounterExpanded) {
                        NearbyEncounterChip(ativo, onClick = { encounterExpanded = true }, modifier = Modifier.padding(top = 8.dp))
                    }
                }
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
                if (fuelLayerEnabled) {
                    if (isSearchingFuelOnline) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.95f),
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text(
                                "Buscando postos próximos...",
                                Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    } else {
                        Surface(
                            onClick = { vm.searchFuelStationsInArea() },
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                            modifier = Modifier.padding(top = 8.dp),
                            shadowElevation = 3.dp,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                androidx.compose.material3.Icon(
                                    painter = androidx.compose.ui.res.painterResource(R.drawable.fa_gas_pump),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    fuelSearchStatus ?: "Buscar postos nesta área",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }

            Column(
                Modifier.align(Alignment.BottomCenter).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (rider != null || nearbyCardShown) {
                    // O cartão da moto tocada (ou o do encontro) ocupa o lugar do painel inferior.
                } else if (!hasLocation && !locationOk) {
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


/** Largura do painel lateral da navegação em paisagem (sem contar a área segura à esquerda). */
private val NAV_PANEL_WIDTH = 224.dp

private val NAV_SHAPE = RoundedCornerShape(22.dp)

@Composable
private fun NavSurface(modifier: Modifier, shape: androidx.compose.ui.graphics.Shape = NAV_SHAPE, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        shadowElevation = 12.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        content = content,
    )
}

/** Estado de "recalculando" / "preparando" no lugar da manobra. */
@Composable
private fun NavWaiting(rerouting: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
        Text(if (rerouting) "Recalculando rota…" else "Preparando navegação…", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun NavSpeed(kmh: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$kmh", fontSize = 32.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
        Text("km/h", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NavVoiceToggle(voice: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (voice) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                if (voice) "Voz" else "Mudo",
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                color = if (voice) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NavStopButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = Bad, modifier = modifier) {
        Box(Modifier.height(48.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text("Encerrar", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun ConfirmStopDialog(show: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    if (!show) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Encerrar navegação?") },
        text = { Text("A viagem gravada até aqui será salva no histórico.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Encerrar", color = Bad) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Continuar") } },
    )
}

/** Retrato: banner de manobra compacto no topo (ícone, distância e via). */
@Composable
private fun NavBannerCompact(vm: HunterViewModel, modifier: Modifier) {
    val nav by vm.nav.collectAsStateWithLifecycle()
    val p = nav.progress
    NavSurface(modifier.fillMaxWidth()) {
        if (nav.rerouting || p == null) {
            Box(Modifier.padding(16.dp)) { NavWaiting(nav.rerouting) }
        } else {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(64.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) { ManeuverGlyph(p.upcoming.type, 44.dp, MaterialTheme.colorScheme.onPrimary) }
                Column(Modifier.weight(1f)) {
                    Text(fmtDist(p.distanceToUpcomingM), fontSize = 30.sp, fontWeight = FontWeight.Black)
                    Text(p.upcoming.instruction, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    p.then?.takeIf { !it.isDestination }?.let { next ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Depois", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ManeuverGlyph(next.type, 16.dp, MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(next.streetName ?: "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Cartão compacto do próximo perigo: ícone, distância, nome e (radar) limite. Largura limitada (~210 dp) para caber ao lado dos
 * botões da direita mesmo em telas estreitas; vermelho quando o radar tem limite e a velocidade está acima.
 */
@Composable
private fun PerigoCard(a: app.hunter.nav.perigos.AlertaPerigo, modifier: Modifier) {
    val density = LocalDensity.current.density
    val icone = remember(a.perigo.tipo, density) { perigoBitmap(a.perigo.tipo, (48 * density).toInt()).asImageBitmap() }
    val acima = a.acimaDoLimite
    NavSurface(modifier.widthIn(max = 210.dp)) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            androidx.compose.foundation.Image(icone, contentDescription = a.perigo.tipo.nome, modifier = Modifier.size(48.dp))
            Column {
                Text(fmtDist(a.distanciaM.toDouble()), fontSize = 26.sp, fontWeight = FontWeight.Black, maxLines = 1, color = if (acima) Bad else MaterialTheme.colorScheme.onSurface)
                Text(a.perigo.tipo.nome, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                a.limiteKmh?.let {
                    Text(
                        if (acima) "Limite $it km/h · acima" else "Limite $it km/h",
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (acima) Bad else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Retrato: barra fina embaixo (velocidade, chegada/tempo/distância, voz, encerrar). */
@Composable
private fun NavBarCompact(vm: HunterViewModel, onStop: () -> Unit, modifier: Modifier) {
    val nav by vm.nav.collectAsStateWithLifecycle()
    val rec by vm.recording.collectAsStateWithLifecycle()
    val voice by vm.voice.collectAsStateWithLifecycle()
    var confirm by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val p = nav.progress
    val kmh = ((rec.lastPoint?.speedMps ?: 0f) * 3.6f).toInt()

    NavSurface(modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NavSpeed(kmh)
            Column(Modifier.weight(1f)) {
                Text(p?.let { fmtArrival(it.remainingS) } ?: "--:--", fontSize = 26.sp, fontWeight = FontWeight.Black, maxLines = 1)
                Text(
                    p?.let { "${fmtEta(it.remainingS)} · ${fmtDist(it.remainingM)}" } ?: "Calculando…",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            NavVoiceToggle(voice, vm::toggleVoice)
            NavStopButton({ confirm = true })
        }
    }
    ConfirmStopDialog(confirm, onConfirm = { confirm = false; onStop() }, onDismiss = { confirm = false })
}

/** Paisagem: coluna estreita à esquerda (manobra, velocidade, chegada, voz e encerrar); o mapa fica com o resto. */
@Composable
private fun NavSidePanel(vm: HunterViewModel, onStop: () -> Unit, leftInset: Dp, modifier: Modifier) {
    val nav by vm.nav.collectAsStateWithLifecycle()
    val rec by vm.recording.collectAsStateWithLifecycle()
    val voice by vm.voice.collectAsStateWithLifecycle()
    var confirm by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val p = nav.progress
    val kmh = ((rec.lastPoint?.speedMps ?: 0f) * 3.6f).toInt()

    NavSurface(
        modifier.fillMaxHeight().width(NAV_PANEL_WIDTH + leftInset),
        shape = RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = leftInset + 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (nav.rerouting || p == null) {
                NavWaiting(nav.rerouting)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.size(56.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) { ManeuverGlyph(p.upcoming.type, 40.dp, MaterialTheme.colorScheme.onPrimary) }
                    Text(fmtDist(p.distanceToUpcomingM), fontSize = 28.sp, fontWeight = FontWeight.Black, maxLines = 1)
                }
                Text(p.upcoming.instruction, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NavSpeed(kmh)
                Column(Modifier.weight(1f)) {
                    Text(p?.let { fmtArrival(it.remainingS) } ?: "--:--", fontSize = 24.sp, fontWeight = FontWeight.Black, maxLines = 1)
                    Text(
                        p?.let { "${fmtEta(it.remainingS)} · ${fmtDist(it.remainingM)}" } ?: "Calculando…",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NavVoiceToggle(voice, vm::toggleVoice)
                NavStopButton({ confirm = true }, Modifier.weight(1f))
            }
        }
    }
    ConfirmStopDialog(confirm, onConfirm = { confirm = false; onStop() }, onDismiss = { confirm = false })
}
