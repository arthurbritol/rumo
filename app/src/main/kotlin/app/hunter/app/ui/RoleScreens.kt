package app.hunter.app.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hunter.app.R
import app.hunter.app.social.FriendRow
import app.hunter.app.social.MAX_ROLE_PEOPLE
import app.hunter.app.social.ParticipantRow
import app.hunter.app.social.ROLE_LIMIT_NOTICE
import app.hunter.app.social.RoleDetailUi
import app.hunter.app.social.RoleRow
import app.hunter.app.social.budgetInputText
import app.hunter.app.social.parseBudget
import app.hunter.app.social.roleSubtitle
import app.hunter.app.social.toggleSelection
import app.hunter.social.SocialEngine

/** Criar rolê: nome, meu orçamento (opcional) e até 9 amigos com campos arredondados e squircles Novare. */
@Composable
fun CreateRoleScreen(
    friends: List<FriendRow>,
    busy: Boolean,
    message: String?,
    onCreate: (name: String, friendUids: List<String>, budget: Double?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var budgetText by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val budget = parseBudget(budgetText)
    val budgetInvalid = budgetText.isNotBlank() && budget == null
    val limitReached = selected.size >= MAX_ROLE_PEOPLE - 1
    val canCreate = name.trim().isNotEmpty() && selected.isNotEmpty() && !budgetInvalid && !busy

    val inputShape = RoundedCornerShape(14.dp)
    val inputColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
    )

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Voltar",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column {
                Text(
                    "Novo Rolê",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.sp,
                )
                Text(
                    "Criar rolê em grupo",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        OutlinedTextField(
            value = name,
            onValueChange = { if (it.length <= SocialEngine.MAX_ROLE_NAME) name = it },
            label = { Text("Nome do rolê") },
            placeholder = { Text("Ex: Serra da Canastra, Rolê de Domingo") },
            singleLine = true,
            shape = inputShape,
            colors = inputColors,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = budgetText,
            onValueChange = { budgetText = it },
            label = { Text("Quanto você quer gastar (R$)") },
            placeholder = { Text("Ex: 150,00") },
            supportingText = { Text(if (budgetInvalid) "Valor inválido" else "Opcional: dá para informar depois") },
            isError = budgetInvalid,
            singleLine = true,
            shape = inputShape,
            colors = inputColors,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )

        SectionTitle("Quem vai (${selected.size} de ${MAX_ROLE_PEOPLE - 1})")
        Text(
            ROLE_LIMIT_NOTICE,
            style = MaterialTheme.typography.bodySmall,
            color = if (limitReached) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (friends.isEmpty()) {
            HCard {
                Text(
                    "Você ainda não tem amigos para chamar. Busque e adicione amigos antes de criar um rolê.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        friends.forEach { f ->
            val on = f.uid in selected
            val enabled = on || !limitReached
            HCard(
                onClick = if (enabled) { { selected = toggleSelection(selected.toSet(), f.uid).toList() } } else null,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SquircleIcon(
                        iconRes = R.drawable.fa_user,
                        backgroundColor = if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                        tintColor = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = f.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = f.bikeLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Checkbox(
                        checked = on,
                        enabled = enabled,
                        onCheckedChange = { selected = toggleSelection(selected.toSet(), f.uid).toList() },
                    )
                }
            }
        }

        message?.let {
            HCard {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }

        RumoPrimaryButton(
            text = "Criar rolê",
            onClick = { onCreate(name.trim(), selected, budget) },
            enabled = canCreate,
            leadingIconRes = R.drawable.fa_users,
        )

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Detalhe do rolê. [row] é a linha da lista (nula se o rolê sumiu) e [detail] os participantes (nulo enquanto carrega).
 */
@Composable
fun RoleDetailScreen(
    row: RoleRow?,
    detail: RoleDetailUi?,
    busy: Boolean,
    message: String?,
    offline: Boolean,
    onBack: () -> Unit,
    onSaveBudget: (Double?) -> Unit,
    onLeave: () -> Unit,
    onClose: () -> Unit,
    onRemove: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Voltar",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = row?.name ?: "Detalhe do rolê",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (row != null) {
                    Text(
                        text = roleSubtitle(row),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (row == null) {
            HCard {
                Text(
                    "Este rolê não existe mais.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        if (offline) {
            HCard {
                Text("Sem conexão. Mostrando o último dado carregado.", style = MaterialTheme.typography.bodyMedium)
                RumoSecondaryButton(text = "Tentar de novo", onClick = onRetry)
            }
        }

        message?.let {
            HCard {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (detail == null) {
            HCard {
                Text(
                    "Carregando participantes…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        // Resumo tabular financeiro: Média do Rolê e Alcance Sugerido em mini-cards lado a lado
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SquircleIcon(
                        iconRes = R.drawable.fa_gas_pump,
                        backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        tintColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp),
                    )
                    Text(
                        text = "MÉDIA DO ROLÊ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = detail.averageLabel,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SquircleIcon(
                        iconRes = R.drawable.fa_route,
                        backgroundColor = NovareSuccess.copy(alpha = 0.15f),
                        tintColor = NovareSuccess,
                        modifier = Modifier.size(36.dp),
                    )
                    Text(
                        text = "ALCANCE SUGERIDO",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (detail.hasReach) detail.reachText else "—",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = if (detail.hasReach) NovareSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (!detail.hasReach) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = detail.reachText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        SectionTitle("Participantes (${detail.participants.size})")
        detail.participants.forEach { p ->
            ParticipantCard(p, busy, onRemove)
        }

        if (detail.iAccepted) {
            SectionTitle("Seu orçamento")
            BudgetEditor(detail.myBudget, busy, onSaveBudget)
        }

        var confirm by remember { mutableStateOf(false) }
        RumoSecondaryButton(
            text = if (detail.isCreator) "Encerrar rolê" else "Sair do rolê",
            onClick = { confirm = true },
            enabled = !busy,
        )

        if (confirm) {
            AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text(if (detail.isCreator) "Encerrar o rolê?" else "Sair do rolê?") },
                text = {
                    Text(
                        if (detail.isCreator) "O rolê acaba para todos os participantes. Isso não pode ser desfeito."
                        else "Você deixa de ver o rolê e seu orçamento some dele.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { confirm = false; if (detail.isCreator) onClose() else onLeave() }) {
                        Text(if (detail.isCreator) "Encerrar" else "Sair", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } },
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BudgetEditor(current: Double?, busy: Boolean, onSave: (Double?) -> Unit) {
    var text by rememberSaveable(current) { mutableStateOf(budgetInputText(current)) }
    val parsed = parseBudget(text)
    val invalid = text.isNotBlank() && parsed == null

    val inputShape = RoundedCornerShape(14.dp)
    val inputColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Quanto você quer gastar (R$)") },
            placeholder = { Text("Ex: 100,00") },
            supportingText = { if (invalid) Text("Valor inválido") },
            isError = invalid,
            singleLine = true,
            shape = inputShape,
            colors = inputColors,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        // Vazio limpa o orçamento (só quando já havia um); valor inválido não salva.
        val canClear = text.isBlank() && current != null
        RumoPrimaryButton(
            text = "Salvar orçamento",
            onClick = { onSave(parsed) },
            enabled = (parsed != null || canClear) && !busy,
        )
    }
}

@Composable
private fun ParticipantCard(p: ParticipantRow, busy: Boolean, onRemove: (String) -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    HCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SquircleIcon(
                iconRes = R.drawable.fa_user,
                backgroundColor = if (p.isMe) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                tintColor = if (p.isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = p.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (p.isMe) {
                        RumoBadge(text = "Você", color = MaterialTheme.colorScheme.primary)
                    } else if (p.invited) {
                        RumoBadge(text = "Aguardando", color = NovareWarning)
                    }
                }
                Text(
                    text = p.budgetLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (p.invited) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                )
                p.warnings.forEach { warning ->
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = NovareError,
                    )
                }
            }
            if (p.canRemove) {
                TextButton(onClick = { confirm = true }, enabled = !busy) {
                    Text("Remover", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Remover ${p.name} do rolê?") },
            text = { Text("Essa pessoa deixará de fazer parte deste rolê.") },
            confirmButton = {
                TextButton(onClick = { confirm = false; onRemove(p.uid) }) {
                    Text("Remover", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } },
        )
    }
}
