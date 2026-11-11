package app.hunter.app.ui

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hunter.app.R
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
    anpSyncStatus: String? = null,
    onSyncAnpPrices: () -> Unit = {},
    /** Logado: se a pessoa aparece na busca por nome; nulo esconde a opção. */
    searchable: Boolean? = null,
    onSearchableChange: (Boolean) -> Unit = {},
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
        profile = profile,
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
        anpSyncStatus = anpSyncStatus,
        onSyncAnpPrices = onSyncAnpPrices,
        searchable = searchable.takeIf { state is AuthState.SignedIn },
        onSearchableChange = onSearchableChange,
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

/** Linha chave-valor limpa para exibição de dados tabulares do perfil. */
@Composable
private fun ProfileDataRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Tela de perfil redesenhada com seções limpas HCard e identidade Novare. */
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
    anpSyncStatus: String? = null,
    onSyncAnpPrices: () -> Unit = {},
    searchable: Boolean? = null,
    onSearchableChange: (Boolean) -> Unit = {},
    profile: UserProfile? = null,
) {
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    // Estado do formulário de edição da moto e do piloto
    var isEditingBike by rememberSaveable { mutableStateOf(false) }
    var editName by rememberSaveable { mutableStateOf("") }
    var editBrand by rememberSaveable { mutableStateOf("") }
    var editModel by rememberSaveable { mutableStateOf("") }
    var editYear by rememberSaveable { mutableStateOf("") }
    var formErrors by remember { mutableStateOf<List<FormError>>(emptyList()) }
    fun errorOf(f: FormField) = formErrors.firstOrNull { it.field == f }?.message

    val pilotDisplayName = profile?.displayName?.ifBlank { null } ?: ui.title

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. Cabeçalho Superior com Navegação e Título
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Voltar",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Perfil",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = pilotDisplayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Feedback / Progresso em operação assíncrona
        if (busy) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(10.dp))
                    Text("Aguarde…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // Mensagens de erro e avisos com botão para fechar
        error?.let {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = NovareError.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, NovareError.copy(alpha = 0.4f)),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = it,
                        color = NovareError,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismissMessage) {
                        Text("Fechar", color = NovareError, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        notice?.let {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismissMessage) {
                        Text("Ok", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 2. Seção: Piloto & Conta
        HCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SquircleIcon(
                    iconRes = R.drawable.fa_user,
                    backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    tintColor = MaterialTheme.colorScheme.primary,
                    contentDescription = "Piloto",
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Piloto & Conta",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = ui.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(
                    text = if (ui.signedIn) "Conectado" else "Visitante",
                    active = ui.signedIn,
                )
            }

            ui.subtitle?.let { email ->
                ProfileDataRow(label = "E-mail", value = email)
            }

            ui.notice?.let { n ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = (if (ui.signedIn) NovareWarning else MaterialTheme.colorScheme.secondary).copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, (if (ui.signedIn) NovareWarning else MaterialTheme.colorScheme.secondary).copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    Text(
                        text = n,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            if (ui.showSignIn) {
                Spacer(Modifier.height(4.dp))
                RumoPrimaryButton(
                    text = "Entrar na conta",
                    onClick = onSignIn,
                    enabled = ui.signInEnabled && !busy,
                    leadingIconRes = R.drawable.fa_user,
                )
            }
            if (ui.showResendVerification) {
                Spacer(Modifier.height(4.dp))
                RumoSecondaryButton(
                    text = "Reenviar e-mail de confirmação",
                    onClick = onResend,
                    enabled = !busy,
                )
            }
            if (ui.deletionResuming) {
                Spacer(Modifier.height(4.dp))
                RumoSecondaryButton(
                    text = "Tentar concluir exclusão agora",
                    onClick = onRetryDeletion,
                    enabled = !busy,
                )
            }
        }

        // 3. Seção: Dados da Moto
        HCard {
            val currentBrand = profile?.brand?.ifBlank { null } ?: editProfile?.invoke()?.brand?.ifBlank { null }
            val currentModel = profile?.model?.ifBlank { null } ?: editProfile?.invoke()?.model?.ifBlank { null }
            val currentYear = profile?.year?.toString() ?: editProfile?.invoke()?.year?.ifBlank { null }
            val bikeSummary = listOfNotNull(currentBrand, currentModel).joinToString(" ").ifEmpty { "Moto não cadastrada" }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SquircleIcon(
                    iconRes = R.drawable.fa_motorcycle,
                    backgroundColor = NovareSuccess.copy(alpha = 0.15f),
                    tintColor = NovareSuccess,
                    contentDescription = "Moto",
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Dados da Moto",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = bikeSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!isEditingBike) {
                ProfileDataRow(label = "Marca", value = currentBrand ?: "Não informada")
                ProfileDataRow(label = "Modelo", value = currentModel ?: "Não informado")
                ProfileDataRow(label = "Ano", value = currentYear ?: "Não informado")

                Text(
                    text = "O hodômetro e revisões da moto são ajustados na Garagem.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )

                if (editProfile != null) {
                    Spacer(Modifier.height(4.dp))
                    RumoSecondaryButton(
                        text = "Editar dados da moto",
                        onClick = {
                            val f = editProfile()
                            editName = f.name
                            editBrand = f.brand
                            editModel = f.model
                            editYear = f.year
                            formErrors = emptyList()
                            isEditingBike = true
                        },
                        enabled = !busy,
                        leadingIconRes = R.drawable.fa_wrench,
                    )
                }
            } else {
                OutlinedTextField(
                    value = editName,
                    onValueChange = { editName = it.take(BikeForm.MAX_TEXT) },
                    label = { Text("Nome do piloto") },
                    singleLine = true,
                    isError = errorOf(FormField.NAME) != null,
                    supportingText = errorOf(FormField.NAME)?.let { { Text(it) } },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = editBrand,
                    onValueChange = { editBrand = it.take(BikeForm.MAX_TEXT) },
                    label = { Text("Marca (opcional)") },
                    singleLine = true,
                    isError = errorOf(FormField.BRAND) != null,
                    supportingText = errorOf(FormField.BRAND)?.let { { Text(it) } },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = editModel,
                    onValueChange = { editModel = it.take(BikeForm.MAX_TEXT) },
                    label = { Text("Modelo (opcional)") },
                    singleLine = true,
                    isError = errorOf(FormField.MODEL) != null,
                    supportingText = errorOf(FormField.MODEL)?.let { { Text(it) } },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = editYear,
                    onValueChange = { editYear = it.filter(Char::isDigit).take(4) },
                    label = { Text("Ano (opcional)") },
                    singleLine = true,
                    isError = errorOf(FormField.YEAR) != null,
                    supportingText = errorOf(FormField.YEAR)?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = "O km da moto se ajusta na Garagem.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    RumoSecondaryButton(
                        text = "Cancelar",
                        onClick = { isEditingBike = false },
                        modifier = Modifier.weight(1f),
                    )
                    RumoPrimaryButton(
                        text = "Salvar",
                        onClick = {
                            val form = ProfileEditForm(editName, editBrand, editModel, editYear)
                            formErrors = form.validate()
                            if (formErrors.isEmpty()) {
                                onSaveProfile(form)
                                isEditingBike = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // 4. Seção: Privacidade & Social
        if (searchable != null) {
            HCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SquircleIcon(
                        iconRes = R.drawable.fa_users,
                        backgroundColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                        tintColor = MaterialTheme.colorScheme.secondary,
                        contentDescription = "Privacidade",
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Privacidade & Social",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Aparecer na busca por nome",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = searchable, onCheckedChange = onSearchableChange)
                }
                Text(
                    text = "Permite que outros motociclistas encontrem você pelo nome de exibição para mandar convites de amizade e rolês em grupo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 5. Seção: Aplicativo & Atualizações
        SectionTitle("Aplicativo")
        HCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SquircleIcon(
                    iconRes = R.drawable.fa_gauge_high,
                    backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                    tintColor = MaterialTheme.colorScheme.primary,
                    contentDescription = "Versão",
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Rumo Companion",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (versionName.isBlank()) "Versão instalada" else "Versão $versionName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            updateStatusText(updateCheck)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (updateCheck == UpdateCheck.FOUND) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RumoSecondaryButton(
                    text = "Atualização",
                    onClick = onCheckUpdate,
                    enabled = updateCheck != UpdateCheck.CHECKING,
                    modifier = Modifier.weight(1f),
                )
                RumoSecondaryButton(
                    text = "Apresentação",
                    onClick = onReopenIntro,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 5b. Seção: Preços de Combustíveis (ANP)
        HCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SquircleIcon(
                    iconRes = R.drawable.fa_gas_pump,
                    backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    tintColor = MaterialTheme.colorScheme.primary,
                    contentDescription = "Preços ANP",
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "Preços de Combustíveis (ANP)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = anpSyncStatus ?: "Base de referência com 27 UFs e cidades polo integradas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (anpSyncStatus != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            RumoSecondaryButton(
                text = "Sincronizar preços agora",
                onClick = onSyncAnpPrices,
                leadingIconRes = R.drawable.fa_gas_pump,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 6. Seção: Ações de Conta
        if (ui.showSignOut || ui.showDelete) {
            SectionTitle("Sessão & Conta")
            if (ui.showSignOut) {
                RumoSecondaryButton(
                    text = "Sair da conta",
                    onClick = { confirmSignOut = true },
                    enabled = ui.signOutEnabled && !busy,
                )
                ui.signOutHint?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            if (ui.showDelete) {
                Surface(
                    onClick = { confirmDelete = true },
                    enabled = ui.deleteEnabled && !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(RUMO_SECONDARY_BUTTON_HEIGHT_DP.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, NovareError.copy(alpha = 0.5f)),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Apagar meus dados e minha conta",
                            color = if (ui.deleteEnabled && !busy) NovareError else NovareError.copy(alpha = 0.4f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    // Diálogos de confirmação preservados 100%
    if (confirmSignOut) {
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
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (needsPassword) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Senha") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            "Você confirmará sua identidade com a conta Google.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = ok,
                    onClick = { confirmDelete = false; onDelete(if (needsPassword) password else null) },
                ) {
                    Text(
                        "Apagar tudo",
                        color = if (ok) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            dismissButton = { Button(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}