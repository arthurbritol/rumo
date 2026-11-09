package app.hunter.app.ui.onboarding

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hunter.app.account.AccountViewModel
import app.hunter.app.account.AuthError
import app.hunter.app.account.AuthState
import app.hunter.app.ui.HunterTheme
import app.hunter.app.ui.PrimaryButton

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private enum class PendingOp { GOOGLE, SIGN_IN, SIGN_UP, RESET }

/**
 * Liga [LoginScreen] ao [AccountViewModel]. [setActivity] é o `activity` do gateway: é definido antes
 * do login Google (a folha de contas precisa de uma Activity) e limpo quando a operação termina.
 * [available] vem de `AccountConfig.isAvailable(context)`. [onDone] roda quando a conta está logada
 * (depois do aviso de confirmação, no cadastro por e-mail); [onSkip] é o "Pular".
 */
@Composable
fun LoginRoute(
    vm: AccountViewModel,
    available: Boolean,
    setActivity: (Activity?) -> Unit,
    onDone: () -> Unit,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val errorKind by vm.errorKind.collectAsStateWithLifecycle()
    val linkEmail by vm.linkEmail.collectAsStateWithLifecycle()
    val okCount by vm.okCount.collectAsStateWithLifecycle()

    var pending by remember { mutableStateOf<PendingOp?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var waitingNotice by remember { mutableStateOf(false) }
    var signedUpEmail by remember { mutableStateOf("") }

    DisposableEffect(Unit) { onDispose { setActivity(null) } }
    LaunchedEffect(busy) { if (!busy) setActivity(null) }

    // Conta criada: mostra o aviso de e-mail de confirmação e espera "Continuar". Outros logins seguem direto.
    LaunchedEffect(state) {
        if (state is AuthState.SignedIn) {
            if (pending == PendingOp.SIGN_UP) {
                info = "Enviamos um e-mail de confirmação para $signedUpEmail. Confira a caixa de entrada."
                waitingNotice = true
            } else {
                onDone()
            }
        }
    }
    LaunchedEffect(okCount) {
        if (okCount > 0 && pending == PendingOp.RESET) {
            info = "Se o e-mail tiver conta, enviamos um link para criar uma nova senha."
            pending = null
        }
    }

    LoginScreen(
        available = available,
        busy = busy,
        error = error,
        errorKind = errorKind,
        linkEmail = linkEmail,
        info = info,
        onInfoContinue = if (waitingNotice) ({ onDone() }) else null,
        onGoogle = {
            pending = PendingOp.GOOGLE
            info = null
            setActivity(context.findActivity())
            vm.signInGoogle()
        },
        onSignIn = { email, password ->
            pending = PendingOp.SIGN_IN
            info = null
            vm.signInEmail(email, password)
        },
        onSignUp = { name, email, password ->
            pending = PendingOp.SIGN_UP
            signedUpEmail = email
            info = null
            vm.signUpEmail(name, email, password)
        },
        onLinkGoogle = { password -> vm.linkGoogle(password) },
        onReset = { email ->
            pending = PendingOp.RESET
            info = null
            vm.resetPassword(email)
        },
        onSkip = onSkip,
        onDismissError = vm::clearError,
    )
}

/** Tela de login sem dependência de ViewModel: tudo entra por parâmetro, para pré-visualizar e testar. */
@Composable
fun LoginScreen(
    available: Boolean,
    busy: Boolean,
    error: String?,
    errorKind: AuthError?,
    linkEmail: String?,
    info: String?,
    onInfoContinue: (() -> Unit)?,
    onGoogle: () -> Unit,
    onSignIn: (email: String, password: String) -> Unit,
    onSignUp: (name: String, email: String, password: String) -> Unit,
    onLinkGoogle: (password: String) -> Unit,
    onReset: (email: String) -> Unit,
    onSkip: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    // A senha nunca vai para o estado salvo (Bundle): fica só em memória.
    var password by remember { mutableStateOf("") }
    var linkPassword by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    var lastWasGoogle by remember { mutableStateOf(false) }

    val enabled = available && !busy

    fun submit() {
        localError = when {
            creating && name.isBlank() -> "Informe seu nome."
            !email.contains("@") || email.isBlank() -> "Informe um e-mail válido."
            password.isEmpty() -> "Informe a senha."
            creating && password.length < 6 -> "A senha precisa ter pelo menos 6 caracteres."
            else -> null
        }
        if (localError != null) return
        lastWasGoogle = false
        if (creating) onSignUp(name.trim(), email.trim(), password) else onSignIn(email.trim(), password)
    }

    fun retry() {
        onDismissError()
        if (lastWasGoogle) onGoogle() else submit()
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("🏁", fontSize = 48.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        Text(
            if (creating) "Criar conta" else "Entrar no Rumo",
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Text(
            "Crie sua conta para ter amigos e guardar seu perfil.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )

        if (!available) {
            Text(
                "Login indisponível nesta versão",
                color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }

        if (info != null) {
            Text(info, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            if (onInfoContinue != null) PrimaryButton("Continuar", onInfoContinue)
        }

        if (info == null || onInfoContinue == null) {
            OutlinedButton(
                onClick = { lastWasGoogle = true; localError = null; onGoogle() },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("G   Continuar com Google") }

            if (linkEmail != null) {
                Text(
                    "Este e-mail já tem conta. Informe a senha de $linkEmail para vincular o Google.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                PasswordField("Senha", linkPassword, enabled) { linkPassword = it }
                PrimaryButton("Vincular Google", { onLinkGoogle(linkPassword); linkPassword = "" }, enabled = enabled && linkPassword.isNotEmpty())
            } else {
                Text(
                    "ou com e-mail", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
                if (creating) {
                    OutlinedTextField(
                        name, { name = it }, label = { Text("Nome") }, singleLine = true, enabled = enabled,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    email, { email = it }, label = { Text("E-mail") }, singleLine = true, enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                PasswordField("Senha", password, enabled) { password = it }
                PrimaryButton(if (creating) "Criar conta" else "Entrar", ::submit, enabled = enabled)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { creating = !creating; localError = null; onDismissError() }, enabled = enabled) {
                        Text(if (creating) "Já tenho conta" else "Criar conta")
                    }
                    if (!creating) {
                        TextButton(
                            onClick = {
                                if (email.contains("@")) {
                                    localError = null
                                    onReset(email.trim())
                                } else {
                                    localError = "Digite seu e-mail acima para receber o link."
                                }
                            },
                            enabled = enabled,
                        ) { Text("Esqueci a senha") }
                    }
                }
            }
        }

        if (busy) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(10.dp))
                Text("Aguarde…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        val shown = localError ?: error
        if (shown != null) {
            Text(shown, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            if (localError == null && errorKind == AuthError.NETWORK) {
                OutlinedButton(onClick = ::retry, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Tentar de novo")
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Pular") }
        Text(
            "Rotas e viagens ficam só no seu celular.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PasswordField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Preview(showBackground = true, heightDp = 720)
@Composable
private fun LoginPreview() {
    HunterTheme {
        LoginScreen(
            available = true, busy = false, error = "Sem conexão com a internet. Tente de novo.",
            errorKind = AuthError.NETWORK, linkEmail = null, info = null, onInfoContinue = null,
            onGoogle = {}, onSignIn = { _, _ -> }, onSignUp = { _, _, _ -> }, onLinkGoogle = {},
            onReset = {}, onSkip = {}, onDismissError = {},
        )
    }
}

@Preview(showBackground = true, heightDp = 720)
@Composable
private fun LoginUnavailablePreview() {
    HunterTheme {
        LoginScreen(
            available = false, busy = false, error = null, errorKind = null, linkEmail = null, info = null,
            onInfoContinue = null, onGoogle = {}, onSignIn = { _, _ -> }, onSignUp = { _, _, _ -> },
            onLinkGoogle = {}, onReset = {}, onSkip = {}, onDismissError = {},
        )
    }
}
