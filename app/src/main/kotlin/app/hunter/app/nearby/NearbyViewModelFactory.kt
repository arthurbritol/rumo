package app.hunter.app.nearby

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.hunter.app.BuildConfig
import app.hunter.app.HunterApp
import app.hunter.app.account.AuthState
import app.hunter.nav.NavServices
import app.hunter.nav.RouteProfile
import app.hunter.trips.recording.AndroidLocationSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import app.hunter.app.veiculo.rolePermitido
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map

/** Uso: `viewModel<NearbyViewModel>(factory = nearbyViewModelFactory(app))`. A identidade vai só no token, nunca o uid. */
fun nearbyViewModelFactory(app: HunterApp): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = app.account.gateway
        val client = NearbyClient(BuildConfig.NEARBY_URL, { forceRefresh -> gateway.idToken(forceRefresh) }, clientScope)
        NearbyViewModel(
            link = client,
            signedIn = { gateway.state.value is AuthState.SignedIn },
            positions = { AndroidLocationSource(app, includeNetwork = true).updates() },
            onActive = { ativo -> if (ativo) NearbyService.start(app) else NearbyService.stop(app) },
            stopRequests = NearbyService.stopRequests,
            startFailures = NearbyService.startFailures,
            onDispose = { clientScope.cancel() },
            rolePermitido = app.veiculos.veiculoAtivo.map { rolePermitido(it?.tipo) },
            etaCalculator = EtaCalculator { from, to -> NavServices.router.route(from, to, RouteProfile.FASTEST).firstOrNull() },
        )
    }
}
