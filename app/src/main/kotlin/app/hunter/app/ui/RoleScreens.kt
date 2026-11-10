package app.hunter.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import app.hunter.app.social.FriendRow
import app.hunter.app.social.MAX_ROLE_PEOPLE
import app.hunter.app.social.ParticipantRow
import app.hunter.app.social.ROLE_LIMIT_NOTICE
import app.hunter.app.social.RoleDetailUi
import app.hunter.app.social.RoleRow
import app.hunter.app.social.budgetInputText
import app.hunter.app.social.parseBudget
import app.hunter.app.social.toggleSelection
import app.hunter.social.SocialEngine

/** Criar rolê: nome, meu orçamento (opcional) e até 9 amigos. */
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

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text("‹ Voltar") }
        Text("Criar rolê", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            value = name,
            onValueChange = { if (it.length <= SocialEngine.MAX_ROLE_NAME) name = it },
            label = { Text("Nome do rolê") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = budgetText,
            onValueChange = { budgetText = it },
            label = { Text("Quanto você quer gastar (R$)") },
            supportingText = { Text(if (budgetInvalid) "Valor inválido" else "Opcional: dá para informar depois") },
            isError = budgetInvalid,
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        SectionTitle("Quem vai (${selected.size} de ${MAX_ROLE_PEOPLE - 1})")
        Text(
            ROLE_LIMIT_NOTICE,
            style = MaterialTheme.typography.bodySmall,
            color = if (limitReached) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (friends.isEmpty()) Text("Você ainda não tem amigos para chamar.", style = MaterialTheme.typography.bodyMedium)
        friends.forEach { f ->
            val on = f.uid in selected
            val enabled = on || !limitReached
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = on,
                    enabled = enabled,
                    onCheckedChange = { selected = toggleSelection(selected.toSet(), f.uid).toList() },
                )
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = MaterialTheme.typography.titleMedium)
                    Text(f.bikeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        PrimaryButton("Criar rolê", { onCreate(name.trim(), selected, budget) }, enabled = canCreate)
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
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text("‹ Voltar") }
        if (row == null) {
            Text("Este rolê não existe mais.", style = MaterialTheme.typography.titleMedium)
            return@Column
        }
        Text(row.name, style = MaterialTheme.typography.headlineMedium)
        if (offline) {
            HCard {
                Text("Sem conexão. Mostrando o último dado carregado.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Tentar de novo") }
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        if (detail == null) {
            Text("Carregando participantes…", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        HCard {
            MetricRow("Média do rolê" to detail.averageLabel, "Alcance sugerido" to if (detail.hasReach) detail.reachText else "—")
            if (!detail.hasReach) {
                Text(detail.reachText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SectionTitle("Participantes")
        detail.participants.forEach { ParticipantCard(it, busy, onRemove) }

        if (detail.iAccepted) {
            SectionTitle("Seu orçamento")
            BudgetEditor(detail.myBudget, busy, onSaveBudget)
        }

        var confirm by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { confirm = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (detail.isCreator) "Encerrar rolê" else "Sair do rolê", color = MaterialTheme.colorScheme.error)
        }
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
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("Quanto você quer gastar (R$)") },
        supportingText = { if (invalid) Text("Valor inválido") },
        isError = invalid,
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    // Vazio limpa o orçamento (só quando já havia um); valor inválido não salva.
    val canClear = text.isBlank() && current != null
    PrimaryButton("Salvar orçamento", { onSave(parsed) }, enabled = (parsed != null || canClear) && !busy)
}

@Composable
private fun ParticipantCard(p: ParticipantRow, busy: Boolean, onRemove: (String) -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    HCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium)
                Text(p.budgetLabel, style = MaterialTheme.typography.bodyMedium)
                p.warnings.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (p.canRemove) TextButton(onClick = { confirm = true }, enabled = !busy) { Text("Remover") }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Remover ${p.name} do rolê?") },
            confirmButton = { TextButton(onClick = { confirm = false; onRemove(p.uid) }) { Text("Remover", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } },
        )
    }
}
