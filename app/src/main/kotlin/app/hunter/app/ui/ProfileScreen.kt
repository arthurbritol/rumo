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
import androidx.compose.runtime.mutableIntStateOf
import app.hunter.app.UpdateCheck
import app.hunter.app.account.AccountViewModel
import app.hunter.app.account.AuthState
import app.hunter.app.ui.onboarding.BikeForm
import app.hunter.app.ui.onboarding.FormError
import app.hunter.app.ui.onboarding.FormField
import app.hunter.app.ui.onboarding.findActivity
import app.hunter.sync.UserProfile
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
    readProfile: () -> UserProfile?,
    onSaveProfile: (ProfileEditForm) -> Unit,
    onSignIn: () -> Unit,
    onReopenIntro: () -> Unit,
    onBack: () -> Unit,
    updateCheck: UpdateCheck = UpdateCheck.IDLE,
    onCheckUpdate: () -> Unit = {},
) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var saves by remember { mutableIntStateOf(0) }
    // Relido quando a conta/operação muda e depois de salvar (o perfil vindo da nuvem chega pela sincronização).
    val profile = remember(state, busy, saves) { readProfile() }

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
        editProfile = (state as? AuthState.SignedIn)?.let { s -> { ProfileEditForm.from(profile, s.name) } },
        profileSummary = profile?.let(::profileSummary),
        onSaveProfile = { onSaveProfile(it); saves++ },
        versionName = versionName,
        updateCheck = updateCheck,
        onCheckUpdate = onCheckUpdate,
    )
}

/** Texto do resultado da busca de atualização; `null` enquanto ninguém buscou. */
internal fun updateStatusText(s: UpdateCheck): String? = when (s) {
    UpdateCheck.IDLE -> null
    UpdateCheck.CHECKING -> "Buscando…"
    UpdateCheck.UP_TO_DATE -> "Você está na versão mais recente."
    UpdateCheck.FAILED -> "Não foi possível buscar agora. Verifique a internet."
    UpdateCheck.FOUND -> "Há uma versão nova: veja o aviso no topo do mapa."
}

/** "Ana · Honda CG 160 (2020)" ou "Ana · moto não informada". */
private fun profileSummary(p: UserProfile): String {
    val bike = listOfNotNull(p.brand, p.model).joinToString(" ").ifBlank { null }
        ?.let { b -> p.year?.let { "$b ($it)" } ?: b }
    return "${p.displayName} · ${bike ?: "moto não informada"}"
}

/**
 * "Seu perfil": resumo e "Editar" (nome, marca, modelo, ano). Validação de [ProfileEditForm]; o km fica na
 * Garagem. Salvar grava o perfil como alterado e pede sincronização (por [onSave]).
 */
@Composable
private fun ProfileEditSection(summary: String?, busy: Boolean, start: () -> ProfileEditForm, onSave: (ProfileEditForm) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var brand by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var year by rememberSaveable { mutableStateOf("") }
    var errors by remember { mutableStateOf<List<FormError>>(emptyList()) }
    fun errorOf(f: FormField) = errors.firstOrNull { it.field == f }?.message

    SectionTitle("Seu perfil")
    if (!editing) {
        Text(
            summary ?: "Perfil ainda não preenchido.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = {
                val f = start()
                name = f.name; brand = f.brand; model = f.model; year = f.year
                errors = emptyList()
                editing = true
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Editar") }
        return
    }
    @Composable
    fun field(label: String, value: String, f: FormField, number: Boolean = false, onChange: (String) -> Unit) {
        OutlinedTextField(
            value, onChange, label = { Text(label) }, singleLine = true, isError = errorOf(f) != null,
            supportingText = errorOf(f)?.let { { Text(it) } },
            keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    field("Nome", name, FormField.NAME) { name = it.take(BikeForm.MAX_TEXT) }
    field("Marca (opcional)", brand, FormField.BRAND) { brand = it.take(BikeForm.MAX_TEXT) }
    field("Modelo (opcional)", model, FormField.MODEL) { model = it.take(BikeForm.MAX_TEXT) }
    field("Ano (opcional)", year, FormField.YEAR, number = true) { year = it.filter(Char::isDigit).take(4) }
    Text(
        "O km da moto se ajusta na Garagem.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { editing = false }, modifier = Modifier.weight(1f)) { Text("Cancelar") }
        Button(
            onClick = {
                val form = ProfileEditForm(name, brand, model, year)
                errors = form.validate()
                if (errors.isEmpty()) {
                    onSave(form)
                    editing = false
                }
            },
            enabled = !busy,
            modifier = Modifier.weight(1f),
        ) { Text("Salvar") }
    }
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
    /** Logado: cria o formulário a partir do perfil atual. Null para visitante (perfil é dado da conta). */
    editProfile: (() -> ProfileEditForm)? = null,
    profileSummary: String? = null,
    onSaveProfile: (ProfileEditForm) -> Unit = {},
    versionName: String = "",
    updateCheck: UpdateCheck = UpdateCheck.IDLE,
    onCheckUpdate: () -> Unit = {},
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

        if (editProfile != null) {
            ProfileEditSection(summary = profileSummary, busy = busy, start = editProfile, onSave = onSaveProfile)
        }

        SectionTitle("Apresentação")
        OutlinedButton(onClick = onReopenIntro, modifier = Modifier.fillMaxWidth()) { Text("Rever apresentação") }

        SectionTitle("Atualização")
        Text(
            if (versionName.isBlank()) "Versão desconhecida" else "Versão instalada: $versionName",
            style = MaterialTheme.typography.bodyMedium,
        )
        updateStatusText(updateCheck)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(
            onClick = onCheckUpdate,
            enabled = updateCheck != UpdateCheck.CHECKING,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Buscar atualização") }

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
