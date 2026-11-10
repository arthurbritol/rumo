package app.hunter.app

import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.hunter.app.account.AccountViewModel
import app.hunter.app.account.AuthState
import app.hunter.app.account.accountViewModelFactory
import app.hunter.app.social.SocialViewModel
import app.hunter.app.social.UNKNOWN_FRIEND_NAME
import app.hunter.app.social.friendsBadge
import app.hunter.app.social.pendingCount
import app.hunter.app.social.socialViewModelFactory
import app.hunter.app.sync.ProfilePrefs
import app.hunter.app.sync.safeDisplayName
import app.hunter.app.ui.AccountNotice
import app.hunter.app.ui.FriendMarker
import app.hunter.app.ui.FriendsRoute
import app.hunter.app.ui.GarageScreen
import app.hunter.app.ui.HunterTheme
import app.hunter.app.ui.MapScreen
import app.hunter.app.ui.PodScreen
import app.hunter.app.ui.ProfileRoute
import app.hunter.app.ui.Tab
import app.hunter.app.ui.TripsScreen
import app.hunter.app.ui.UpdateBanner
import app.hunter.app.ui.formatFriendElapsed
import app.hunter.app.ui.onboarding.AppBikeSaver
import app.hunter.app.ui.onboarding.BikeSetupScreen
import app.hunter.app.ui.onboarding.EXISTING_USER_NOTICE_KEY
import app.hunter.app.ui.onboarding.EntryRoute
import app.hunter.app.ui.onboarding.ONBOARDING_IN_PROGRESS_KEY
import app.hunter.app.ui.onboarding.LoginRoute
import app.hunter.app.ui.onboarding.OnboardingScreen
import app.hunter.app.ui.onboarding.PermissionsScreen
import app.hunter.app.ui.onboarding.SharedPrefsOnboardingPrefs
import app.hunter.app.ui.onboarding.resolveEntry
import app.hunter.sync.OnboardingFlow
import app.hunter.sync.Step
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    // Só é criado quando o destino é o app principal: o HunterViewModel semeia a manutenção e grava `seeded`,
    // e isso não pode acontecer antes de resolveEntry decidir se é uma instalação nova.
    private val vm: HunterViewModel by viewModels()
    private val account get() = (application as HunterApp).account
    private val accountVm: AccountViewModel by viewModels { accountViewModelFactory(account.repo) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            HunterTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppEntry(application as HunterApp, { vm }, accountVm)
                }
            }
        }
    }

    override fun onDestroy() {
        // O gateway guarda só uma referência fraca; limpar aqui evita usar uma Activity destruída no login Google.
        if (account.gateway.activity === this) account.gateway.activity = null
        super.onDestroy()
    }
}

private object Route {
    const val MAIN = "MAIN"
    const val ONBOARDING = "ONBOARDING"
}

private enum class Overlay { NONE, PROFILE, LOGIN_FROM_PROFILE, LOGIN, INTRO }

/** Decide entre apresentação (instalação nova) e app, e hospeda perfil, login e "Rever apresentação" por cima. */
@Composable
private fun AppEntry(app: HunterApp, vmProvider: () -> HunterViewModel, accountVm: AccountViewModel) {
    val account = app.account
    val prefs = remember { app.getSharedPreferences("app", 0) }
    val onboardingPrefs = remember { SharedPrefsOnboardingPrefs(app) }
    var route by rememberSaveable { mutableStateOf<String?>(null) }
    var overlay by rememberSaveable { mutableStateOf(Overlay.NONE.name) }
    val authState by accountVm.state.collectAsStateWithLifecycle()
    val signedIn = authState is AuthState.SignedIn
    // Um só SocialViewModel para o app: a aba, o marcador de pendências e o Perfil enxergam o mesmo estado.
    val socialVm: SocialViewModel = viewModel(factory = socialViewModelFactory(account.social))
    val socialState by socialVm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (route == null) {
            route = withContext(Dispatchers.IO) {
                when (resolveEntry(prefs, app.syncDao, onboardingPrefs)) {
                    EntryRoute.ONBOARDING -> Route.ONBOARDING
                    else -> Route.MAIN
                }
            }
        }
    }

    val bikeSaver = remember { AppBikeSaver(app.maintenanceDao, app.tripDao, prefs, account.scheduler::requestSync) }
    // Avaliado a cada transição, depois da primeira sincronização do login: o perfil vindo da conta já está gravado.
    val hasBikeInAccount: () -> Boolean = {
        ProfilePrefs.read(prefs)?.let { it.brand != null && it.model != null } == true
    }

    @Composable
    fun Onboarding(flow: OnboardingFlow, initialStep: Step, onFinished: () -> Unit) {
        OnboardingScreen(
            flow = flow,
            signedIn = signedIn,
            hasBikeInAccount = hasBikeInAccount,
            prefs = onboardingPrefs,
            onFinished = onFinished,
            initialStep = initialStep,
            login = { onSkip, onDone ->
                LoginRoute(
                    vm = accountVm, available = account.available,
                    setActivity = { account.gateway.activity = it },
                    onDone = onDone, onSkip = onSkip,
                )
            },
            permissions = { onNext -> PermissionsScreen(onFinished = onNext) },
            bike = { short, onNext ->
                BikeSetupScreen(
                    short = short,
                    name = (authState as? AuthState.SignedIn)?.name.orEmpty(),
                    saver = bikeSaver,
                    onDone = onNext,
                )
            },
        )
    }

    when (route) {
        null -> Box(Modifier.fillMaxSize())
        Route.ONBOARDING -> {
            val flow = remember { OnboardingFlow(seen = false) }
            Onboarding(flow, flow.first()) {
                prefs.edit().putBoolean(ONBOARDING_IN_PROGRESS_KEY, false).apply()
                route = Route.MAIN
            }
        }
        else -> Box(Modifier.fillMaxSize()) {
            HunterRoot(
                vm = vmProvider(),
                prefs = prefs,
                socialVm = socialVm,
                signedIn = signedIn,
                myName = { safeDisplayName(ProfilePrefs.read(prefs)?.displayName.orEmpty()) },
                accountAvailable = account.available,
                onOpenProfile = { overlay = Overlay.PROFILE.name },
                onCreateAccount = { overlay = Overlay.LOGIN.name },
            )
            when (Overlay.valueOf(overlay)) {
                Overlay.NONE -> Unit
                Overlay.PROFILE -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize().statusBarsPadding()) {
                        ProfileRoute(
                            vm = accountVm,
                            authAvailable = account.available,
                            hasUnsynced = account::hasUnsynced,
                            deletionPending = account::deletionPending,
                            needsPassword = { account.gateway.providerIsPassword },
                            setActivity = { account.gateway.activity = it },
                            readProfile = { ProfilePrefs.read(prefs) },
                            onSaveProfile = { form ->
                                account.saveProfile(form.toProfile(voice = prefs.getBoolean(ProfilePrefs.VOICE, true)))
                            },
                            onSignIn = { overlay = Overlay.LOGIN_FROM_PROFILE.name },
                            onReopenIntro = { overlay = Overlay.INTRO.name },
                            onBack = { overlay = Overlay.NONE.name },
                            updateCheck = vmProvider().updateCheck.collectAsStateWithLifecycle().value,
                            onCheckUpdate = { vmProvider().checkUpdate(force = true) },
                            searchable = socialState.searchVisible,
                            onSearchableChange = socialVm::answerSearchPrompt,
                        )
                    }
                    BackHandler { overlay = Overlay.NONE.name }
                }
                Overlay.LOGIN, Overlay.LOGIN_FROM_PROFILE -> {
                    val back = if (overlay == Overlay.LOGIN_FROM_PROFILE.name) Overlay.PROFILE else Overlay.NONE
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize().statusBarsPadding()) {
                            LoginRoute(
                                vm = accountVm, available = account.available,
                                setActivity = { account.gateway.activity = it },
                                onDone = { overlay = back.name }, onSkip = { overlay = back.name },
                            )
                        }
                        BackHandler { overlay = back.name }
                    }
                }
                Overlay.INTRO -> {
                    val flow = remember { OnboardingFlow(seen = true) }
                    Onboarding(flow, flow.reopenIntro()) { overlay = Overlay.PROFILE.name }
                    BackHandler { overlay = Overlay.PROFILE.name }
                }
            }
        }
    }
}

@Composable
private fun HunterRoot(
    vm: HunterViewModel,
    prefs: SharedPreferences,
    socialVm: SocialViewModel,
    signedIn: Boolean,
    myName: () -> String,
    accountAvailable: Boolean,
    onOpenProfile: () -> Unit,
    onCreateAccount: () -> Unit,
) {
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    val tab = Tab.entries[tabIndex]
    val nav by vm.nav.collectAsStateWithLifecycle()

    // Toda vez que o app volta para a frente (o processo pode ficar vivo por dias): procura versão nova.
    // Também recalcula a semana dos amigos e publica (respeitando o intervalo mínimo).
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        vm.checkUpdate()
        socialVm.onForeground()
    }
    val social by socialVm.state.collectAsStateWithLifecycle()
    val badge = friendsBadge(pendingCount(social.invitesReceived.size, social.roles.count { it.pending }))
    var friendsSubOpen by remember { mutableStateOf(false) }

    val activeLocations by socialVm.activeFriendsOnRoad.collectAsStateWithLifecycle()
    var ticker by remember { mutableStateOf(0L) }
    LaunchedEffect(tab == Tab.Map) {
        if (tab == Tab.Map) {
            while (true) {
                delay(30_000L)
                ticker = System.currentTimeMillis()
            }
        }
    }
    val friendMarkers = remember(social.friends, activeLocations, tab == Tab.Map, ticker) {
        if (tab != Tab.Map) return@remember emptyList()
        val now = System.currentTimeMillis()
        activeLocations.mapNotNull { (uid, loc) ->
            val timeAgo = formatFriendElapsed(loc.updatedAtMs, now) ?: return@mapNotNull null
            val friend = social.friends.firstOrNull { it.uid == uid }
            val name = friend?.name ?: UNKNOWN_FRIEND_NAME
            FriendMarker(
                uid = uid,
                name = name,
                lat = loc.latitude,
                lon = loc.longitude,
                bearing = loc.bearing,
                timeAgoText = timeAgo,
            )
        }
    }

    LaunchedEffect(Unit) {
        socialVm.focusFriendOnMapEvent.collect { (lat, lon) ->
            tabIndex = Tab.Map.ordinal
            vm.focusOn(lat, lon)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // Durante a navegação a barra some: mais mapa e nenhum toque acidental com a moto andando.
            if (!(nav.active && tab == Tab.Map)) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    Tab.entries.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = i == tabIndex,
                            onClick = { tabIndex = i },
                            icon = {
                                if (t == Tab.Friends && badge != null) {
                                    BadgedBox(badge = { Badge { Text(badge) } }) { Icon(t.icon, t.label) }
                                } else {
                                    Icon(t.icon, t.label)
                                }
                            },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            // O mapa fica SEMPRE montado por baixo: trocar de aba não recria o mapa nem recarrega os tiles
            // (era a maior causa de lentidão). As outras abas aparecem por cima, opacas.
            MapScreen(vm, friends = friendMarkers)
            Crossfade(targetState = tab, animationSpec = tween(160), label = "tab") { t ->
                if (t != Tab.Map) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize().statusBarsPadding()) {
                            when (t) {
                                Tab.Trips -> TripsScreen(vm)
                                Tab.Garage -> GarageScreen(vm, onOpenProfile)
                                Tab.Friends -> FriendsRoute(
                                    vm = socialVm,
                                    accountAvailable = accountAvailable,
                                    accountSignedIn = signedIn,
                                    myName = myName,
                                    onSignIn = onCreateAccount,
                                    onSubScreen = { friendsSubOpen = it },
                                    onFocusFriendOnMap = { lat, lon ->
                                        tabIndex = Tab.Map.ordinal
                                        vm.focusOn(lat, lon)
                                    },
                                )
                                Tab.Pod -> PodScreen(vm)
                                Tab.Map -> {}
                            }
                        }
                    }
                }
            }
            // Composto depois do MapScreen para ter prioridade sobre o "voltar" do mapa.
            BackHandler(enabled = tab != Tab.Map && !(tab == Tab.Friends && friendsSubOpen)) { tabIndex = Tab.Map.ordinal }

            // Aviso de versão nova por cima de tudo, exceto durante a navegação (nada atrapalha a condução).
            val update by vm.update.collectAsStateWithLifecycle()
            update?.takeIf { !nav.active }?.let { u ->
                UpdateBanner(
                    u, onInstall = vm::installUpdate, onDismiss = vm::dismissUpdate,
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
                )
            }

            // Quem já usava o app: aviso discreto e dispensável para criar conta (nunca durante a navegação).
            var showNotice by remember { mutableStateOf(prefs.getBoolean(EXISTING_USER_NOTICE_KEY, false)) }
            // Entrou numa conta: o aviso perde o sentido. Efeito (não durante a composição) para gravar a preferência.
            LaunchedEffect(signedIn, showNotice) {
                if (signedIn && showNotice) {
                    prefs.edit().putBoolean(EXISTING_USER_NOTICE_KEY, false).apply()
                    showNotice = false
                }
            }
            if (showNotice && !signedIn && accountAvailable && !nav.active) {
                AccountNotice(
                    onCreate = onCreateAccount,
                    onDismiss = {
                        prefs.edit().putBoolean(EXISTING_USER_NOTICE_KEY, false).apply()
                        showNotice = false
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                )
            }
        }
    }
}
