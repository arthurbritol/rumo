package app.hunter.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hunter.app.R
import app.hunter.app.social.FriendRow
import app.hunter.app.social.InviteRow
import app.hunter.app.social.OFFLINE_BANNER
import app.hunter.app.social.RoleRow
import app.hunter.app.social.SocialUi
import app.hunter.app.social.SocialViewModel
import app.hunter.app.social.bikeLabel
import app.hunter.app.social.messageToShow
import app.hunter.app.social.roleSubtitle
import app.hunter.social.Member
import app.hunter.social.MemberState
import app.hunter.social.SearchHit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val WEEK_RECALC_MS = 60_000L

/**
 * Aba Amigos: hospeda a lista, a criação de rolê e o detalhe do rolê. [onSubScreen] avisa quando há uma subtela
 * aberta (o "voltar" do sistema fecha a subtela antes de sair da aba).
 */
@Composable
fun FriendsRoute(
    vm: SocialViewModel,
    accountAvailable: Boolean,
    accountSignedIn: Boolean,
    myName: () -> String,
    onSignIn: () -> Unit,
    onSubScreen: (Boolean) -> Unit,
    onFocusFriendOnMap: ((Double, Double) -> Unit)? = null,
    onOpenProfile: () -> Unit = {},
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val detail by vm.roleDetail.collectAsStateWithLifecycle()
    var openRoleId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }

    val sub = openRoleId != null || creating
    LaunchedEffect(sub) { onSubScreen(sub) }
    // Os amigos só são carregados com a aba na tela; ao sair, o rolê aberto deixa de ser observado.
    LaunchedEffect(Unit) { vm.showTab(true) }
    DisposableEffect(Unit) {
        onDispose {
            vm.showTab(false)
            vm.closeRoleDetail()
            onSubScreen(false)
        }
    }
    // A semana pode virar com a tela aberta: recalcula a cada minuto.
    LaunchedEffect(Unit) {
        while (true) {
            delay(WEEK_RECALC_MS)
            vm.recalcWeek()
        }
    }
    // Reabre o rolê depois de recriar a tela (ou do processo).
    LaunchedEffect(openRoleId) {
        openRoleId?.let { vm.openRole(it) } ?: vm.closeRoleDetail()
    }

    val id = openRoleId
    when {
        creating -> {
            BackHandler { creating = false }
            CreateRoleScreen(
                friends = ui.friends,
                busy = ui.busy,
                message = messageToShow(ui.offline, ui.message),
                onCreate = { name, uids, budget ->
                    vm.createRole(name, uids, Member("", myName(), MemberState.ACCEPTED, budget, null)) { creating = false }
                },
                onBack = { creating = false },
            )
        }
        id != null -> {
            BackHandler { openRoleId = null }
            val row = ui.roles.firstOrNull { it.id == id }
            RoleDetailScreen(
                row = row,
                detail = detail,
                busy = ui.busy,
                message = messageToShow(ui.offline, ui.message),
                offline = ui.offline,
                onBack = { openRoleId = null },
                onSaveBudget = { vm.setBudget(id, it) },
                onLeave = { vm.leaveRole(id) { openRoleId = null } },
                onClose = { vm.closeRole(id) { openRoleId = null } },
                onRemove = { vm.removeParticipant(id, it) },
                onRetry = vm::refresh,
            )
        }
        else -> FriendsScreen(
            ui = ui,
            accountAvailable = accountAvailable,
            accountSignedIn = accountSignedIn,
            myName = myName,
            onOpenProfile = onOpenProfile,
            onSignIn = onSignIn,
            onRefresh = vm::refresh,
            onAnswerSearchPrompt = vm::answerSearchPrompt,
            onSearch = vm::search,
            onInvite = vm::invite,
            onAcceptInvite = vm::acceptInvite,
            onDeclineInvite = vm::declineInvite,
            onCancelInvite = vm::cancelInvite,
            onUnfriend = vm::unfriend,
            onBlock = vm::block,
            onRetry = vm::refresh,
            onDismissMessage = vm::clearMessage,
            onCreateRole = { creating = true },
            onOpenRole = { openRoleId = it },
            onAcceptRole = vm::acceptRole,
            onDeclineRole = { vm.leaveRole(it) },
            onToggleLocationSharing = vm::toggleLocationSharing,
            onFocusFriendOnMap = { lat, lng ->
                onFocusFriendOnMap?.invoke(lat, lng)
            },
        )
    }
}

/** Lista da aba Amigos, sem ViewModel: tudo entra por parâmetro. */
@Composable
fun FriendsScreen(
    ui: SocialUi,
    accountAvailable: Boolean,
    accountSignedIn: Boolean,
    onSignIn: () -> Unit,
    onRefresh: () -> Unit,
    onAnswerSearchPrompt: (Boolean) -> Unit,
    onSearch: (String) -> Unit,
    onInvite: (String) -> Unit,
    onAcceptInvite: (String) -> Unit,
    onDeclineInvite: (String) -> Unit,
    onCancelInvite: (String) -> Unit,
    onUnfriend: (String) -> Unit,
    onBlock: (String) -> Unit,
    onRetry: () -> Unit,
    onDismissMessage: () -> Unit,
    onCreateRole: () -> Unit,
    onOpenRole: (String) -> Unit,
    onAcceptRole: (String) -> Unit,
    onDeclineRole: (String) -> Unit,
    onToggleLocationSharing: (String) -> Unit = {},
    onFocusFriendOnMap: ((Double, Double) -> Unit)? = null,
    myName: () -> String = { "" },
    onOpenProfile: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    var invitesSectionY by remember { mutableIntStateOf(0) }

    val pendingInvitesCount = ui.invitesReceived.size
    val pendingRolesCount = ui.roles.count { it.pending }
    val totalPending = pendingInvitesCount + pendingRolesCount

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. Header refinado Novare: Cumprimento + Avatar do Perfil + Sino de Notificações com badge
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val name = myName().trim()
            val greeting = if (name.isNotEmpty()) "Fala, $name" else "Amigos"
            Column(Modifier.weight(1f)) {
                Text(
                    text = "COMUNIDADE",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.2.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = greeting,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (ui.signedIn) {
                TextButton(
                    onClick = onRefresh,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text("Atualizar", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
            }

            // Sino de notificações com Font Awesome fa_bell
            IconButton(
                onClick = {
                    if (totalPending > 0) {
                        coroutineScope.launch {
                            scrollState.animateScrollTo(maxOf(0, invitesSectionY - 24))
                        }
                    }
                },
            ) {
                if (totalPending > 0) {
                    BadgedBox(
                        badge = {
                            Badge(
                                containerColor = NovareError,
                                contentColor = Color.White,
                            ) {
                                Text(
                                    text = if (totalPending > 99) "99+" else totalPending.toString(),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.fa_bell),
                            contentDescription = "Notificações",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                } else {
                    Icon(
                        painter = painterResource(R.drawable.fa_bell),
                        contentDescription = "Notificações",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }

            // Avatar clicável do piloto com borda sutil
            IconButton(onClick = onOpenProfile) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.fa_user),
                        contentDescription = "Perfil e conta",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        if (!ui.signedIn && accountSignedIn) {
            // Há conta, mas o social ainda não pode rodar: nada de "Entrar"
            HCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SquircleIcon(
                        iconRes = R.drawable.fa_user,
                        backgroundColor = NovareWarning.copy(alpha = 0.15f),
                        tintColor = NovareWarning,
                    )
                    Column(Modifier.weight(1f)) {
                        Text("Amigos não está disponível agora", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Estamos preparando sua conta. Se demorar, confira a internet e volte em instantes.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            return@Column
        }

        if (!ui.signedIn) {
            HCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SquircleIcon(
                        iconRes = R.drawable.fa_users,
                        backgroundColor = NovareAccent.copy(alpha = 0.15f),
                        tintColor = NovareAccent,
                    )
                    Column(Modifier.weight(1f)) {
                        Text("Entre para usar amigos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Com uma conta você acha amigos pelo nome, vê quanto eles rodaram na semana e combina rolês em grupo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (accountAvailable) {
                    RumoPrimaryButton("Entrar", onClick = onSignIn, leadingIconRes = R.drawable.fa_user)
                } else {
                    Text(
                        "Contas não estão disponíveis nesta versão do app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Column
        }

        if (ui.offline) {
            HCard {
                Text(OFFLINE_BANNER, style = MaterialTheme.typography.bodyMedium)
                RumoSecondaryButton("Tentar de novo", onClick = onRetry)
            }
        }

        messageToShow(ui.offline, ui.message)?.let {
            HCard {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onDismissMessage) { Text("Fechar") }
            }
        }

        if (ui.searchPromptNeeded) {
            HCard {
                Text("Quer aparecer na busca por nome?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Se sim, quem digitar seu nome encontra você e pode mandar um convite. Dá para mudar depois no Perfil.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RumoPrimaryButton("Sim", { onAnswerSearchPrompt(true) }, Modifier.weight(1f))
                    RumoSecondaryButton("Não", { onAnswerSearchPrompt(false) }, Modifier.weight(1f))
                }
            }
        }

        // 2. Busca com RumoSearchField compacto e Font Awesome
        SearchSection(ui, onSearch, onInvite, onBlock)

        // 3. Convites recebidos
        if (ui.invitesReceived.isNotEmpty()) {
            Box(
                Modifier.onGloballyPositioned { coords ->
                    invitesSectionY = coords.positionInParent().y.toInt() + scrollState.value
                }
            ) {
                SectionTitle("Convites recebidos (${ui.invitesReceived.size})")
            }
            ui.invitesReceived.forEach { InviteReceivedCard(it, ui.busy, onAcceptInvite, onDeclineInvite, onBlock) }
        }

        // 4. Convites enviados
        if (ui.invitesSent.isNotEmpty()) {
            SectionTitle("Convites enviados (${ui.invitesSent.size})")
            ui.invitesSent.forEach { InviteSentCard(it, ui.busy, onCancelInvite) }
        }

        // 5. Lista de Amigos (Ao vivo / Offline)
        SectionTitle("Amigos (${ui.friends.size})")
        if (!ui.friendsLoaded) {
            Text("Carregando…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (ui.friends.isEmpty()) {
            HCard {
                Text(
                    "Você ainda não tem amigos por aqui. Digite um nome acima para buscar e convidar parceiros de estrada.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ui.friends.forEach { FriendCard(it, ui.busy, onUnfriend, onBlock, onToggleLocationSharing, onFocusFriendOnMap) }

        // 6. Lista de Rolês em Grupo
        SectionTitle("Rolês (${ui.roles.size})")
        RolesSection(ui, onCreateRole, onOpenRole, onAcceptRole, onDeclineRole)

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SearchSection(
    ui: SocialUi,
    onSearch: (String) -> Unit,
    onInvite: (String) -> Unit,
    onBlock: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    SectionTitle("Buscar amigo")
    RumoSearchField(
        value = text,
        onValueChange = {
            text = it
            onSearch(it)
        },
        placeholder = "Buscar amigo por nome...",
        modifier = Modifier.fillMaxWidth(),
    )
    if (text.isNotEmpty() && text.length < 3) {
        Text(
            "Digite pelo menos 3 letras",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
    }
    if (ui.searching) {
        Text(
            "Buscando…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
    }
    ui.searchResults.forEach { SearchHitCard(it, ui.busy, onInvite, onBlock) }
}

@Composable
private fun SearchHitCard(
    hit: SearchHit,
    busy: Boolean,
    onInvite: (String) -> Unit,
    onBlock: (String) -> Unit,
) {
    var confirmBlock by remember { mutableStateOf(false) }
    HCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SquircleIcon(
                iconRes = R.drawable.fa_user,
                backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                tintColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f)) {
                Text(hit.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val bike = bikeLabel(hit.brand, hit.model)
                Text(bike, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RumoSecondaryButton(
                text = "Convidar",
                onClick = { onInvite(hit.uid) },
                enabled = !busy,
                modifier = Modifier.width(112.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { confirmBlock = true }, enabled = !busy) {
                Text("Bloquear", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (confirmBlock) {
        BlockDialog(hit.displayName, onConfirm = { confirmBlock = false; onBlock(hit.uid) }, onDismiss = { confirmBlock = false })
    }
}

/** Confirmação de bloqueio (o mesmo texto para amigo, convite recebido e resultado de busca). */
@Composable
private fun BlockDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bloquear $name?") },
        text = { Text("Vocês deixam de ser amigos, os convites entre vocês somem e $name não consegue convidar você de novo.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Bloquear", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun InviteReceivedCard(
    row: InviteRow,
    busy: Boolean,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit,
    onBlock: (String) -> Unit,
) {
    var confirmBlock by remember { mutableStateOf(false) }
    HCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SquircleIcon(
                iconRes = R.drawable.fa_user,
                backgroundColor = NovareAccent.copy(alpha = 0.15f),
                tintColor = NovareAccent,
            )
            Column(Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("quer ser seu amigo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RumoPrimaryButton(
                text = "Aceitar",
                onClick = { onAccept(row.uid) },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            )
            RumoSecondaryButton(
                text = "Recusar",
                onClick = { onDecline(row.uid) },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { confirmBlock = true }, enabled = !busy) {
                Text("Bloquear", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (confirmBlock) {
        BlockDialog(row.name, onConfirm = { confirmBlock = false; onBlock(row.uid) }, onDismiss = { confirmBlock = false })
    }
}

@Composable
private fun InviteSentCard(row: InviteRow, busy: Boolean, onCancel: (String) -> Unit) {
    HCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SquircleIcon(
                iconRes = R.drawable.fa_user,
                backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                tintColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("convite enviado", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { onCancel(row.uid) }, enabled = !busy) { Text("Cancelar") }
        }
    }
}

private enum class FriendAction { UNFRIEND, BLOCK }

@Composable
private fun FriendCard(
    row: FriendRow,
    busy: Boolean,
    onUnfriend: (String) -> Unit,
    onBlock: (String) -> Unit,
    onToggleLocation: (String) -> Unit = {},
    onFocusOnMap: ((Double, Double) -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<FriendAction?>(null) }

    HCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (row.isLiveOnRoad) {
                SquircleIcon(
                    iconRes = R.drawable.fa_motorcycle,
                    backgroundColor = NovareSuccess.copy(alpha = 0.15f),
                    tintColor = NovareSuccess,
                )
            } else {
                SquircleIcon(
                    iconRes = R.drawable.fa_user,
                    backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                    tintColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (row.isLiveOnRoad) {
                        StatusPill(text = "Na estrada", active = true)
                    }
                }
                Text(
                    text = row.bikeLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box {
                IconButton(onClick = { menu = true }, enabled = !busy) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Opções de ${row.name}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Desfazer amizade") },
                        onClick = { menu = false; confirm = FriendAction.UNFRIEND },
                    )
                    DropdownMenuItem(
                        text = { Text("Bloquear") },
                        onClick = { menu = false; confirm = FriendAction.BLOCK },
                    )
                }
            }
        }

        if (row.isLiveOnRoad) {
            // Card de status ao vivo e métricas táticas (velocidade, tempo decorrido, botão ver no mapa)
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = NovareSuccess.copy(alpha = 0.08f),
                border = BorderStroke(1.dp, NovareSuccess.copy(alpha = 0.25f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val elapsed = row.liveUpdatedAtMs?.let { formatFriendElapsed(it) } ?: "agora"
                        val speedText = row.liveSpeedKmh?.let {
                            if (it > 0f) "${it.toInt()} km/h · " else ""
                        }.orEmpty()
                        Text(
                            text = "${speedText}$elapsed",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = NovareSuccess,
                        )
                        Text(
                            text = "${row.weekKmLabel} na semana",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (row.liveLatitude != null && row.liveLongitude != null) {
                        Surface(
                            onClick = { onFocusOnMap?.invoke(row.liveLatitude, row.liveLongitude) },
                            shape = RoundedCornerShape(10.dp),
                            color = NovareSuccess,
                            contentColor = Color.White,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.fa_map_location_dot),
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    text = "Ver no mapa",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Amigo offline: km da semana em destaque limpo
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${row.weekKmLabel} rodados na semana",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Switch estilizado para compartilhamento de localização
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = "Compartilhar localização",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (row.locationSharedWithHim) "Visível para ${row.name}" else "Não compartilhado",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = row.locationSharedWithHim,
                onCheckedChange = { onToggleLocation(row.uid) },
                enabled = !busy,
            )
        }
    }

    confirm?.let { action ->
        val block = action == FriendAction.BLOCK
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (block) "Bloquear ${row.name}?" else "Desfazer amizade com ${row.name}?") },
            text = {
                Text(
                    if (block) "Vocês deixam de ser amigos, os convites entre vocês somem e ${row.name} não consegue convidar você de novo."
                    else "Vocês deixam de ver a atividade um do outro. Dá para convidar de novo depois.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    if (block) onBlock(row.uid) else onUnfriend(row.uid)
                }) { Text(if (block) "Bloquear" else "Desfazer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun RolesSection(
    ui: SocialUi,
    onCreate: () -> Unit,
    onOpen: (String) -> Unit,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit,
) {
    if (!ui.rolesLoaded) {
        Text("Carregando…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (ui.roles.isEmpty()) {
        HCard {
            Text(
                "Nenhum rolê por enquanto. Crie um e chame seus amigos para combinar quanto cada um quer gastar.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    ui.roles.forEach { RoleCard(it, ui.busy, onOpen, onAccept, onDecline) }

    RumoPrimaryButton(
        text = "Criar rolê",
        onClick = onCreate,
        enabled = ui.friends.isNotEmpty() && !ui.busy,
        leadingIconRes = R.drawable.fa_plus,
        modifier = Modifier.padding(top = 4.dp),
    )
    if (ui.friends.isEmpty()) {
        Text(
            "Adicione amigos para criar um rolê.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun RoleCard(
    row: RoleRow,
    busy: Boolean,
    onOpen: (String) -> Unit,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit,
) {
    val subtitle = roleSubtitle(row)
    val cardClick = if (!row.pending) { { onOpen(row.id) } } else null

    HCard(onClick = cardClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SquircleIcon(
                iconRes = R.drawable.fa_users,
                backgroundColor = NovareAccent.copy(alpha = 0.15f),
                tintColor = NovareAccent,
            )
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (row.pending) {
                        RumoBadge(text = "Pendente", color = NovareWarning)
                    } else if (row.expired) {
                        RumoBadge(text = "Expirado", color = NovareError)
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Mini-cards de resumo do rolê: Pilotos e Orçamento & Alcance
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                modifier = Modifier.weight(1f),
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(
                        text = "PILOTOS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${row.participantCount} ${if (row.participantCount == 1) "pessoa" else "pessoas"}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                modifier = Modifier.weight(1f),
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(
                        text = "ORÇAMENTO & ALCANCE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (row.pending) "Aceite p/ ver" else "Ver detalhes",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = NovareAccent,
                    )
                }
            }
        }

        if (row.pending) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RumoPrimaryButton(
                    text = "Aceitar",
                    onClick = { onAccept(row.id) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
                RumoSecondaryButton(
                    text = "Recusar",
                    onClick = { onDecline(row.id) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
