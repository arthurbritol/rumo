package app.hunter.app.nearby

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.hunter.app.BuildConfig
import app.hunter.app.HunterApp
import app.hunter.app.account.AuthState
import app.hunter.trips.recording.AndroidLocationSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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
        )
    }
}
