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
import app.hunter.model.TipoVeiculo
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map

/** Uso: `viewModel<NearbyViewModel>(factory = nearbyViewModelFactory(app))`. A identidade vai só no token, nunca o uid. */
fun nearbyViewModelFactory(app: HunterApp): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = app.account.gateway
        val client = NearbyClient(
            BuildConfig.NEARBY_URL,
            { forceRefresh -> gateway.idToken(forceRefresh) },
            clientScope,
            veiculo = { if (app.veiculos.veiculoAtivo.value?.tipo == TipoVeiculo.CARRO) VEICULO_CARRO else VEICULO_MOTO },
        )
        NearbyViewModel(
            link = client,
            signedIn = { gateway.state.value is AuthState.SignedIn },
            positions = { AndroidLocationSource(app, includeNetwork = true).updates() },
            onActive = { ativo -> if (ativo) NearbyService.start(app) else NearbyService.stop(app) },
            stopRequests = NearbyService.stopRequests,
            startFailures = NearbyService.startFailures,
            onDispose = { clientScope.cancel() },
            // Trocar de moto para carro (ou o contrário) muda de Celula: o rolê desliga e a pessoa liga de novo no outro tipo.
            rolePermitido = app.veiculos.veiculoAtivo.map { it?.tipo }.filterNotNull().distinctUntilChanged().withIndex().map { it.index == 0 },
            etaCalculator = EtaCalculator { from, to -> NavServices.router.route(from, to, RouteProfile.FASTEST).firstOrNull() },
        )
    }
}
