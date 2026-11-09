package app.hunter.app.ui

import android.app.Activity
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hunter.app.account.AccountViewModel
import app.hunter.app.ui.onboarding.findActivity
import kotlinx.coroutines.delay

/** Intervalo com que a tela confere se ainda há algo por enviar / exclusão por concluir. */
private const val POLL_MS = 2_500L

/**
 * Perfil: conta, sair, apagar conta e "Rever apresentação". As ações passam pelo [AccountViewModel]
 * (que usa o AuthRepository). [hasUnsynced], [deletionPending] e [needsPassword] vêm da camada do app.
 * [setActivity] é o `activity` do gateway: o apagar conta de quem entrou pelo Google reautentica com a
 * folha de contas, que precisa de uma Activity; é limpo quando a operação termina.
 */
@Composable
fun ProfileRoute(
    vm: AccountViewModel,
    authAvailable: Boolean,
    hasUnsynced: suspend () -> Boolean,
    deletionPending: () -> Boolean,
    needsPassword: () -> Boolean,
    setActivity: (Activity?) -> Unit,
    onSignIn: () -> Unit,
    onReopenIntro: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()

    // Reconfere a cada poucos segundos e sempre que a conta ou a operação em curso mudam.
    val pending by produceState(false, state, busy) {
        while (true) {
            value = try { hasUnsynced() } catch (e: Exception) { value }
            delay(POLL_MS)
        }
    }
    val deleting by produceState(false, state, busy) {
        while (true) {
            value = deletionPending()
            delay(POLL_MS)
        }
    }

    DisposableEffect(Unit) { onDispose { setActivity(null) } }
    LaunchedEffect(busy) { if (!busy) setActivity(null) }

    ProfileScreen(
        ui = profileUi(state, pendingSync = pending, deletionPending = deleting, authAvailable = authAvailable),
        busy = busy,
        error = error,
        notice = notice,
        needsPassword = needsPassword(),
        onSignIn = onSignIn,
        onResend = { vm.sendVerification() },
        onSignOut = { vm.signOut() },
        onDelete = { password ->
            if (password == null) setActivity(context.findActivity()) // reautenticação pelo Google
            vm.deleteAccount(password)
        },
        onRetryDeletion = { vm.resumeDeletionIfNeeded() },
        onReopenIntro = onReopenIntro,
        onBack = onBack,
        onDismissMessage = { vm.clearError(); vm.consumeNotice() },
    )
}

/** Tela sem ViewModel: tudo entra por parâmetro. */
@Composable
fun ProfileScreen(
    ui: ProfileUi,
    busy: Boolean,
    error: String?,
    notice: String?,
    needsPassword: Boolean,
    onSignIn: () -> Unit,
    onResend: () -> Unit,
    onSignOut: () -> Unit,
    onDelete: (password: String?) -> Unit,
    onRetryDeletion: () -> Unit,
    onReopenIntro: () -> Unit,
    onBack: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Voltar") }
        }
        Text("Perfil", style = MaterialTheme.typography.headlineMedium)

        HCard {
            Text(ui.title, style = MaterialTheme.typography.titleLarge)
            ui.subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            ui.notice?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (ui.showSignIn) PrimaryButton("Entrar", onSignIn, enabled = ui.signInEnabled && !busy)
            if (ui.showResendVerification) {
                OutlinedButton(onClick = onResend, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Reenviar e-mail de confirmação")
                }
            }
            if (ui.deletionResuming) {
                OutlinedButton(onClick = onRetryDeletion, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Tentar concluir agora")
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
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismissMessage) { Text("Fechar") }
        }
        notice?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismissMessage) { Text("Ok") }
        }

        SectionTitle("Apresentação")
        OutlinedButton(onClick = onReopenIntro, modifier = Modifier.fillMaxWidth()) { Text("Rever apresentação") }

        if (ui.showSignOut || ui.showDelete) {
            SectionTitle("Conta")
            if (ui.showSignOut) {
                OutlinedButton(
                    onClick = { confirmSignOut = true },
                    enabled = ui.signOutEnabled && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Sair") }
                ui.signOutHint?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (ui.showDelete) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    enabled = ui.deleteEnabled && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Apagar meus dados e minha conta", color = MaterialTheme.colorScheme.error) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmSignOut) {
        // "Cancelar" é o botão principal: sair é a ação que exige intenção clara.
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sair da conta?") },
            text = {
                Text(
                    "Seus dados continuam salvos na conta e voltam quando você entrar de novo. " +
                        "Os trajetos completos (os pontos do mapa) ficam só neste celular e serão perdidos.",
                )
            },
            confirmButton = { Button(onClick = { confirmSignOut = false }) { Text("Cancelar") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false; onSignOut() }) { Text("Sair") } },
        )
    }

    if (confirmDelete) {
        var typed by rememberSaveable { mutableStateOf("") }
        var password by remember { mutableStateOf("") } // a senha nunca vai para o estado salvo
        val ok = deleteConfirmed(typed) && (!needsPassword || password.isNotEmpty())
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Apagar tudo?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Isto apaga seus dados da nuvem (moto, abastecimentos, manutenção, locais e resumo das viagens) " +
                            "e a sua conta. Não dá para desfazer. Os dados deste celular ficam, como no uso sem conta.",
                    )
                    Text("Digite $DELETE_WORD para confirmar.", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(typed, { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (needsPassword) {
                        OutlinedTextField(
                            password, { password = it }, label = { Text("Senha") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            "Você confirmará sua identidade com a conta Google.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = ok,
                    onClick = { confirmDelete = false; onDelete(if (needsPassword) password else null) },
                ) { Text("Apagar tudo", color = if (ok) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            },
            dismissButton = { Button(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}
