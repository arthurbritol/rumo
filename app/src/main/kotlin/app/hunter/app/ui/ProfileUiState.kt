package app.hunter.app.ui

import app.hunter.app.account.AuthState

/** O que a tela de perfil mostra; calculado por [profileUi] (puro, testável). */
data class ProfileUi(
    val signedIn: Boolean,
    val title: String,
    val subtitle: String?,
    /** Aviso em destaque: visitante (sem conta), e-mail não confirmado ou exclusão em andamento. */
    val notice: String?,
    val showSignIn: Boolean,
    val signInEnabled: Boolean,
    val showResendVerification: Boolean,
    val showSignOut: Boolean,
    val signOutEnabled: Boolean,
    val signOutHint: String?,
    val showDelete: Boolean,
    val deleteEnabled: Boolean,
    /** Uma exclusão de conta foi interrompida e está sendo concluída (retoma sozinha). */
    val deletionResuming: Boolean,
)

const val DELETE_WORD = "APAGAR"

/** Confirmação forte da exclusão: a palavra exata (espaços nas pontas são ignorados). */
fun deleteConfirmed(typed: String): Boolean = typed.trim() == DELETE_WORD

/**
 * [pendingSync]: há alterações ainda não enviadas à nuvem (sair fica bloqueado).
 * [deletionPending]: há uma exclusão de conta a concluir neste aparelho.
 * [authAvailable]: o app foi construído com Firebase (sem ele não há login).
 */
fun profileUi(
    state: AuthState,
    pendingSync: Boolean,
    deletionPending: Boolean = false,
    authAvailable: Boolean = true,
): ProfileUi = when (state) {
    AuthState.Guest -> ProfileUi(
        signedIn = false,
        title = "Visitante",
        subtitle = null,
        notice = if (authAvailable) {
            "Sem conta, desinstalar apaga tudo: seus dados ficam só neste celular. " +
                "Entre para guardá-los com segurança."
        } else {
            "Sem conta, desinstalar apaga tudo: seus dados ficam só neste celular. " +
                "O login não está disponível nesta versão."
        },
        showSignIn = true,
        signInEnabled = authAvailable,
        showResendVerification = false,
        showSignOut = false,
        signOutEnabled = false,
        signOutHint = null,
        showDelete = false,
        deleteEnabled = false,
        deletionResuming = false,
    )
    is AuthState.SignedIn -> {
        val unverified = !state.verified && state.email != null
        ProfileUi(
            signedIn = true,
            title = state.name.ifBlank { state.email ?: "Conta" },
            subtitle = state.email,
            notice = when {
                deletionPending -> "Concluindo a exclusão da conta. Mantenha o app aberto com internet."
                unverified -> "Confirme seu e-mail: enviamos um link para ${state.email}."
                else -> null
            },
            showSignIn = false,
            signInEnabled = false,
            showResendVerification = unverified && !deletionPending,
            showSignOut = true,
            signOutEnabled = !pendingSync && !deletionPending,
            signOutHint = if (pendingSync && !deletionPending) "Ainda sincronizando, tente de novo com internet." else null,
            showDelete = true,
            deleteEnabled = !deletionPending,
            deletionResuming = deletionPending,
        )
    }
}
