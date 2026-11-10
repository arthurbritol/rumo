package app.hunter.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                vm.focusFriendOnMap(lat, lng)
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
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Amigos", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            if (ui.signedIn) TextButton(onClick = onRefresh) { Text("Atualizar") }
        }

        if (!ui.signedIn && accountSignedIn) {
            // Há conta, mas o social ainda não pode rodar (preparando a sessão ou exclusão em andamento): nada de "Entrar".
            HCard {
                Text("Amigos não está disponível agora", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Estamos preparando sua conta. Se demorar, confira a internet e volte em instantes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        if (!ui.signedIn) {
            HCard {
                Text("Entre para usar amigos", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Com uma conta você acha amigos pelo nome, vê quanto eles rodaram na semana e combina rolês em grupo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (accountAvailable) PrimaryButton("Entrar", onSignIn)
                else Text(
                    "Contas não estão disponíveis nesta versão do app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        if (ui.offline) {
            HCard {
                Text(OFFLINE_BANNER, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Tentar de novo") }
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
                Text("Quer aparecer na busca por nome?", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Se sim, quem digitar seu nome encontra você e pode mandar um convite. Dá para mudar depois no Perfil.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Sim", { onAnswerSearchPrompt(true) }, Modifier.weight(1f))
                    SecondaryButton("Não", { onAnswerSearchPrompt(false) }, Modifier.weight(1f))
                }
            }
        }

        SearchSection(ui, onSearch, onInvite, onBlock)

        if (ui.invitesReceived.isNotEmpty()) {
            SectionTitle("Convites recebidos")
            ui.invitesReceived.forEach { InviteReceivedCard(it, ui.busy, onAcceptInvite, onDeclineInvite, onBlock) }
        }
        if (ui.invitesSent.isNotEmpty()) {
            SectionTitle("Convites enviados")
            ui.invitesSent.forEach { InviteSentCard(it, ui.busy, onCancelInvite) }
        }

        SectionTitle("Amigos")
        if (!ui.friendsLoaded) {
            Text("Carregando…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (ui.friends.isEmpty()) {
            Text(
                "Você ainda não tem amigos por aqui. Busque pelo nome para convidar.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ui.friends.forEach { FriendCard(it, ui.busy, onUnfriend, onBlock, onToggleLocationSharing, onFocusFriendOnMap) }

        SectionTitle("Rolês")
        RolesSection(ui, onCreateRole, onOpenRole, onAcceptRole, onDeclineRole)

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SearchSection(ui: SocialUi, onSearch: (String) -> Unit, onInvite: (String) -> Unit, onBlock: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    SectionTitle("Buscar amigo")
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onSearch(it) },
        label = { Text("Nome") },
        supportingText = { Text("Digite pelo menos 3 letras") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (ui.searching) Text("Buscando…", style = MaterialTheme.typography.bodySmall)
    ui.searchResults.forEach { SearchHitCard(it, ui.busy, onInvite, onBlock) }
}

@Composable
private fun SearchHitCard(hit: SearchHit, busy: Boolean, onInvite: (String) -> Unit, onBlock: (String) -> Unit) {
    var confirmBlock by remember { mutableStateOf(false) }
    HCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(hit.displayName, style = MaterialTheme.typography.titleMedium)
                val bike = bikeLabel(hit.brand, hit.model)
                Text(bike, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { onInvite(hit.uid) }, enabled = !busy) { Text("Convidar") }
        }
        TextButton(onClick = { confirmBlock = true }, enabled = !busy) { Text("Bloquear", color = MaterialTheme.colorScheme.error) }
    }
    if (confirmBlock) BlockDialog(hit.displayName, onConfirm = { confirmBlock = false; onBlock(hit.uid) }, onDismiss = { confirmBlock = false })
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
        Text(row.name, style = MaterialTheme.typography.titleMedium)
        Text("quer ser seu amigo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onAccept(row.uid) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Aceitar") }
            OutlinedButton(onClick = { onDecline(row.uid) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Recusar") }
        }
        TextButton(onClick = { confirmBlock = true }, enabled = !busy) { Text("Bloquear", color = MaterialTheme.colorScheme.error) }
    }
    if (confirmBlock) BlockDialog(row.name, onConfirm = { confirmBlock = false; onBlock(row.uid) }, onDismiss = { confirmBlock = false })
}

@Composable
private fun InviteSentCard(row: InviteRow, busy: Boolean, onCancel: (String) -> Unit) {
    HCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium)
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium)
                Text(row.bikeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${row.weekKmLabel} na semana", style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = { menu = true }, enabled = !busy) { Icon(Icons.Default.MoreVert, "Opções de ${row.name}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Desfazer amizade") }, onClick = { menu = false; confirm = FriendAction.UNFRIEND })
                DropdownMenuItem(text = { Text("Bloquear") }, onClick = { menu = false; confirm = FriendAction.BLOCK })
            }
        }

        if (row.isLiveOnRoad) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(MaterialTheme.colorScheme.primary, shape = CircleShape)
                        )
                        Text(
                            "Na estrada",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    if (row.liveLatitude != null && row.liveLongitude != null) {
                        TextButton(
                            onClick = { onFocusOnMap?.invoke(row.liveLatitude, row.liveLongitude) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text("Ver no mapa", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Compartilhar localização",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        Text(
            "Nenhum rolê por enquanto. Crie um e chame seus amigos para combinar quanto cada um quer gastar.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    ui.roles.forEach { RoleCard(it, ui.busy, onOpen, onAccept, onDecline) }
    OutlinedButton(onClick = onCreate, enabled = ui.friends.isNotEmpty() && !ui.busy, modifier = Modifier.fillMaxWidth()) {
        Text("Criar rolê")
    }
    if (ui.friends.isEmpty()) {
        Text(
            "Adicione amigos para criar um rolê.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RoleCard(row: RoleRow, busy: Boolean, onOpen: (String) -> Unit, onAccept: (String) -> Unit, onDecline: (String) -> Unit) {
    val subtitle = roleSubtitle(row)
    if (row.pending) {
        HCard {
            Text(row.name, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onAccept(row.id) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Aceitar") }
                OutlinedButton(onClick = { onDecline(row.id) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Recusar") }
            }
        }
    } else {
        HCard(onClick = { onOpen(row.id) }) {
            Text(row.name, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
