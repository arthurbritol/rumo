package app.hunter.app.account

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/** Uso: `viewModel<AccountViewModel>(factory = accountViewModelFactory(repo))`. */
fun accountViewModelFactory(repo: AuthRepository): ViewModelProvider.Factory = viewModelFactory {
    initializer { AccountViewModel(repo) }
}
