package app.hunter.app.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Estado de conta para as telas; a lógica fica no [AuthRepository]. */
class AccountViewModel(private val repo: AuthRepository) : ViewModel() {
    val state: StateFlow<AuthState> = repo.state

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** Última mensagem de erro em pt-BR, ou nulo. */
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Quando o Google colide com uma conta de e-mail/senha: e-mail para o qual pedir a senha. */
    private val _linkEmail = MutableStateFlow<String?>(null)
    val linkEmail: StateFlow<String?> = _linkEmail.asStateFlow()

    /** Tipo do último erro (para a tela decidir, por exemplo, oferecer "tentar de novo" na falta de rede). */
    private val _errorKind = MutableStateFlow<AuthError?>(null)
    val errorKind: StateFlow<AuthError?> = _errorKind.asStateFlow()

    /** Conta quantas operações terminaram com sucesso; a tela observa para mostrar avisos de sucesso. */
    private val _okCount = MutableStateFlow(0)
    val okCount: StateFlow<Int> = _okCount.asStateFlow()

    fun clearError() {
        _error.value = null
        _errorKind.value = null
    }

    fun signInGoogle() = launchOp { repo.signInGoogle() }
    fun signInEmail(email: String, password: String) = launchOp { repo.signInEmail(email, password) }
    fun signUpEmail(name: String, email: String, password: String) = launchOp { repo.signUpEmail(name, email, password) }
    fun linkGoogle(password: String) {
        val email = _linkEmail.value ?: return
        launchOp { repo.linkGoogleToEmail(email, password) }
    }
    fun resetPassword(email: String) = launchOp { repo.resetPassword(email) }
    fun sendVerification() = launchOp { repo.sendVerification() }
    fun signOut() = launchOp { repo.signOut() }
    fun deleteAccount(password: String?) = launchOp { repo.deleteAccount(password) }

    fun resumeDeletionIfNeeded() {
        viewModelScope.launch { repo.resumeDeletionIfNeeded() }
    }

    private fun launchOp(block: suspend () -> AuthResult) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        _errorKind.value = null
        viewModelScope.launch {
            try {
                when (val r = block()) {
                    AuthResult.Ok -> {
                        _linkEmail.value = null
                        _okCount.value += 1
                    }
                    is AuthResult.Error -> when (r.kind.also { if (it != AuthError.CANCELLED) _errorKind.value = it }) {
                        AuthError.CANCELLED -> Unit
                        AuthError.EMAIL_IN_USE -> {
                            _linkEmail.value = r.email
                            _error.value = r.message
                        }
                        else -> _error.value = r.message
                    }
                }
            } finally {
                _busy.value = false
            }
        }
    }
}
