package app.hunter.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import app.hunter.app.veiculo.LocalTextosDoVeiculo
import app.hunter.model.TipoVeiculo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.hunter.app.nearby.EncontroUiState
import app.hunter.app.nearby.NearbyUiState
import app.hunter.app.nearby.TEXTO_CONFIRMAR_BLOQUEIO
import app.hunter.app.nearby.distEtaTexto
import app.hunter.app.nearby.ocupacao
import app.hunter.app.nearby.textoDoFim
import app.hunter.app.nearby.vagasLivresTexto
import app.hunter.app.nearby.faixaProcurando
import app.hunter.app.nearby.rotulo
import app.hunter.nearby.Distancia
import app.hunter.nearby.MotivoDenuncia
import app.hunter.nearby.MotivoFim
import app.hunter.nearby.Parada
import app.hunter.nearby.Preferencia
import app.hunter.nearby.Proposta
import app.hunter.nearby.Ritmo
import app.hunter.nearby.Vizinho

internal const val RAIO_PADRAO_KM = 5

/** Folha "Procurando rolê": três escolhas, o raio (5 a 20 km) e o botão de ligar/desligar. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NearbySheet(
    estado: NearbyUiState,
    /** A preferência ativa (nula se não está procurando): a folha abre com ela. */
    preferencia: Preferencia?,
    onLigar: (Preferencia) -> Unit,
    onDesligar: () -> Unit,
    onMudarRaio: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var ritmo by rememberSaveable { mutableStateOf((preferencia?.ritmo ?: Ritmo.MODERADO).name) }
    var distancia by rememberSaveable { mutableStateOf((preferencia?.distancia ?: Distancia.ATE50).name) }
    var parada by rememberSaveable { mutableStateOf((preferencia?.parada ?: Parada.SEM_PARADA).name) }
    var raio by remember { mutableFloatStateOf((preferencia?.raioKm ?: RAIO_PADRAO_KM).toFloat()) }
    val ligado = estado !is NearbyUiState.Desligado
    // Procurando, as três escolhas ficam travadas (só o raio muda); para mudar, pare e procure de novo.
    val chipsModifier = if (ligado) Modifier.alpha(0.5f) else Modifier

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Procurando rolê", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Quem procura rolê por perto aparece como ${umaMotoOuUmCarro(LocalTextosDoVeiculo.current.tipo)} no mapa, sem nome nem modelo, com a posição aproximada.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("Ritmo")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Ritmo.entries.forEach { SelectChip(it.rotulo, ritmo == it.name, { if (!ligado) ritmo = it.name }, chipsModifier) }
            }
            SectionTitle("Distância")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Distancia.entries.forEach { SelectChip(it.rotulo, distancia == it.name, { if (!ligado) distancia = it.name }, chipsModifier) }
            }
            SectionTitle("Parada")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Parada.entries.forEach { SelectChip(it.rotulo, parada == it.name, { if (!ligado) parada = it.name }, chipsModifier) }
            }

            SectionTitle("Raio de busca")
            Text("${raio.toInt()} km", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = raio,
                onValueChange = { raio = it },
                onValueChangeFinished = { if (ligado) onMudarRaio(raio.toInt()) },
                valueRange = 5f..20f,
                steps = 14, // de 1 em 1 km
            )

            when (estado) {
                is NearbyUiState.Desligado -> estado.aviso?.let { StatusText(it) }
                is NearbyUiState.Indisponivel -> StatusText(estado.mensagem)
                NearbyUiState.Ligando -> StatusText("Ligando…")
                is NearbyUiState.Procurando -> {
                    StatusText(faixaProcurando(estado.vizinhos.size))
                    Text(
                        "Para propor um encontro, segure o dedo no mapa no ponto escolhido.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (ligado) {
                SecondaryButton("Parar de procurar rolê", onDesligar)
            } else {
                PrimaryButton(
                    "Procurar rolê",
                    {
                        onLigar(
                            Preferencia(
                                Ritmo.valueOf(ritmo), Distancia.valueOf(distancia), Parada.valueOf(parada), raio.toInt().coerceIn(5, 20),
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun StatusText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
}

/** Faixa no topo do mapa: "Procurando rolê · N pessoas por perto" (ou a mensagem de indisponível). */
@Composable
fun NearbyStrip(estado: NearbyUiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val text = when (estado) {
        is NearbyUiState.Desligado -> return
        NearbyUiState.Ligando -> "Procurando rolê · ligando…"
        is NearbyUiState.Procurando -> faixaProcurando(estado.vizinhos.size)
        is NearbyUiState.Indisponivel -> estado.mensagem
    }
    val aviso = estado is NearbyUiState.Indisponivel
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = if (aviso) Warn.copy(alpha = 0.95f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.95f),
        shadowElevation = 3.dp,
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (aviso) androidx.compose.ui.graphics.Color.Black else MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** Cartão ao tocar numa moto: distância arredondada, as três escolhas e "combina com você". Nenhum nome. */
@Composable
fun NearbyRiderCard(
    vizinho: Vizinho,
    distancia: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    enviando: Boolean = false,
    onBloquear: () -> Unit = {},
    onDenunciar: (MotivoDenuncia) -> Unit = {},
) {
    FloatingCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${LocalTextosDoVeiculo.current.nomePadrao} por perto", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (distancia != null) {
                    Text(distancia, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Fechar", Modifier.size(20.dp)) }
        }
        Text(
            "${vizinho.preferencia.ritmo.rotulo} · ${vizinho.preferencia.distancia.rotulo} · ${vizinho.preferencia.parada.rotulo}",
            style = MaterialTheme.typography.bodyLarge,
        )
        if (vizinho.combina) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
            ) {
                Text(
                    "combina com você",
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        ModeracaoBotoes(enviando, onBloquear, onDenunciar)
    }
}

/**
 * "Bloquear" (pede confirmação) e "Denunciar" (lista curta de motivos) de uma pessoa. Desabilitados enquanto há um envio
 * em andamento. Nada aqui diz quem é a pessoa: o app só conhece o id opaco da sessão.
 */
@Composable
fun ModeracaoBotoes(
    enviando: Boolean,
    onBloquear: () -> Unit,
    onDenunciar: (MotivoDenuncia) -> Unit,
    modifier: Modifier = Modifier,
    /** Quem é, para os diálogos ("Motociclista 2"); nulo quando o cartão já é de uma pessoa só. */
    nome: String? = null,
) {
    var confirmando by remember { mutableStateOf(false) }
    var escolhendo by remember { mutableStateOf(false) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { confirmando = true }, enabled = !enviando) { Text("Bloquear") }
        TextButton(onClick = { escolhendo = true }, enabled = !enviando) { Text("Denunciar") }
    }
    if (confirmando) {
        AlertDialog(
            onDismissRequest = { confirmando = false },
            title = { Text(if (nome != null) "Bloquear $nome" else "Bloquear") },
            text = { Text(TEXTO_CONFIRMAR_BLOQUEIO) },
            confirmButton = { TextButton(onClick = { confirmando = false; onBloquear() }) { Text("Bloquear") } },
            dismissButton = { TextButton(onClick = { confirmando = false }) { Text("Cancelar") } },
        )
    }
    if (escolhendo) {
        AlertDialog(
            onDismissRequest = { escolhendo = false },
            title = { Text(if (nome != null) "Denunciar $nome: qual o motivo?" else "Denunciar: qual o motivo?") },
            text = {
                Column {
                    MotivoDenuncia.entries.forEach { m ->
                        TextButton(onClick = { escolhendo = false; onDenunciar(m) }, modifier = Modifier.fillMaxWidth()) { Text(m.rotulo) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { escolhendo = false }) { Text("Cancelar") } },
        )
    }
}

// ---- Encontros ----

@Composable
private fun CardHeader(titulo: String, onClose: (() -> Unit)?, closeDescription: String = "Fechar") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(titulo, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (onClose != null) IconButton(onClick = onClose) { Icon(Icons.Default.Close, closeDescription, Modifier.size(20.dp)) }
    }
}

@Composable
private fun CombinaChip() {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
    ) {
        Text(
            "combina com você",
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Cartão de uma proposta tocada no mapa: as três escolhas, vagas livres e "Entrar". Nenhum nome. */
@Composable
fun NearbyProposalCard(proposta: Proposta, aguardando: Boolean, onEntrar: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    FloatingCard(modifier) {
        CardHeader("Encontro proposto por perto", onClose)
        Text(
            "${proposta.ritmo.rotulo} · ${proposta.distancia.rotulo} · ${proposta.parada.rotulo}",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "${ocupacao(proposta.vagasLivres, proposta.vagasTotal)} · ${vagasLivresTexto(proposta.vagasLivres)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (proposta.combina) CombinaChip()
        PrimaryButton(if (aguardando) "Entrando…" else "Entrar", onEntrar, Modifier.fillMaxWidth(), enabled = !aguardando)
    }
}

/** Ponto marcado com o dedo: escolhe as vagas (2 a 8, com você) e propõe. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NearbyCreateProposalCard(
    aguardando: Boolean,
    onPropor: (vagas: Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Aviso (não bloqueia) quando o ponto fica além do raio de quem propõe; ver `avisoPontoLonge`. */
    aviso: String? = null,
) {
    var vagas by rememberSaveable { mutableStateOf(4) }
    FloatingCard(modifier) {
        CardHeader("Propor encontro neste ponto", onClose)
        Text(
            "Quem está por perto vê o ponto e pode entrar. Vagas (com você):",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (2..8).forEach { n -> SelectChip(n.toString(), vagas == n, { vagas = n }) }
        }
        if (aviso != null) Text(aviso, style = MaterialTheme.typography.bodyMedium, color = Warn)
        PrimaryButton(if (aguardando) "Propondo…" else "Propor encontro", { onPropor(vagas) }, Modifier.fillMaxWidth(), enabled = !aguardando)
    }
}

/**
 * O encontro em andamento: vagas, e por participante "X km · Y min" (a própria pessoa aparece como "Você"; os outros
 * como "Motociclista N", sem nada que identifique). Quem propôs cancela; quem entrou sai.
 */
@Composable
fun NearbyEncounterCard(
    ativo: EncontroUiState.Ativo,
    onSair: () -> Unit,
    onMinimizar: () -> Unit,
    modifier: Modifier = Modifier,
    enviando: Boolean = false,
    onBloquear: (String) -> Unit = {},
    onDenunciar: (String, MotivoDenuncia) -> Unit = { _, _ -> },
    /** Abre a prévia de rota até o ponto do encontro; o cartão só aparece fora da navegação (na navegação vai a pílula). */
    onIrAtePonto: (() -> Unit)? = null,
) {
    val enc = ativo.encontro
    FloatingCard(modifier, scrollable = true) {
        CardHeader("Encontro em andamento", onMinimizar, "Minimizar")
        Text(
            if (enc.vagasLivres > 0) vagasLivresTexto(enc.vagasLivres) else "Encontro cheio",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var outro = 0
        enc.participantes.forEach { p ->
            val nome = if (p.sessao == enc.eu) "Você" else "${LocalTextosDoVeiculo.current.nomePadrao} ${++outro}"
            // key: o estado dos diálogos fica preso à pessoa, não à posição na lista (que muda quando alguém sai).
            key(p.sessao) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(nome, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = if (p.sessao == enc.eu) FontWeight.Bold else FontWeight.Normal)
                Text(
                    if (p.chegou) "Chegou" else distEtaTexto(p.distKm, p.etaMin),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (p.sessao != enc.eu) {
                ModeracaoBotoes(enviando, { onBloquear(p.sessao) }, { m -> onDenunciar(p.sessao, m) }, nome = nome)
            }
            }
        }
        if (onIrAtePonto != null) PrimaryButton("Ir até o ponto", onIrAtePonto, Modifier.fillMaxWidth())
        SecondaryButton(if (ativo.souProponente) "Cancelar encontro" else "Sair do encontro", onSair)
    }
}

/** Resumo do encontro numa pílula ("Encontro · 3,2 km · 12 min"): cabe sobre a navegação; tocar abre o cartão. */
@Composable
fun NearbyEncounterChip(ativo: EncontroUiState.Ativo, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val eu = ativo.encontro.participantes.firstOrNull { it.sessao == ativo.encontro.eu }
    val resumo = if (eu?.chegou == true) "Você chegou" else distEtaTexto(eu?.distKm, eu?.etaMin)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.95f),
        shadowElevation = 3.dp,
    ) {
        Text(
            "Encontro · $resumo",
            Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** Aviso curto de erro (ex.: "Esse encontro já está cheio"), com botão de fechar. */
@Composable
fun NearbyNotice(texto: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = Warn.copy(alpha = 0.95f),
        shadowElevation = 3.dp,
    ) {
        Row(Modifier.padding(start = 14.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(texto, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = androidx.compose.ui.graphics.Color.Black)
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Fechar aviso", Modifier.size(18.dp), tint = androidx.compose.ui.graphics.Color.Black) }
        }
    }
}

/** Como o encontro acabou. */
@Composable
fun NearbyEndCard(
    motivo: MotivoFim,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Sessões de quem estava no encontro, para bloquear ou denunciar depois (o servidor guarda os pares por 24 h). */
    pessoas: List<String> = emptyList(),
    enviando: Boolean = false,
    onBloquear: (String) -> Unit = {},
    onDenunciar: (String, MotivoDenuncia) -> Unit = { _, _ -> },
    /** Sessões já marcadas com "Quero ser amigo": o botão vira "Marcado" e fica desabilitado. */
    marcadas: Set<String> = emptySet(),
    onQueroAmigo: (String) -> Unit = {},
) {
    FloatingCard(modifier, scrollable = true) {
        CardHeader("Encontro encerrado", onClose)
        Text(textoDoFim(motivo), style = MaterialTheme.typography.bodyLarge)
        pessoas.forEachIndexed { i, sessao ->
            key(sessao) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${LocalTextosDoVeiculo.current.nomePadrao} ${i + 1}", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        val marcada = sessao in marcadas
                        TextButton(onClick = { onQueroAmigo(sessao) }, enabled = !marcada && !enviando) {
                            Text(if (marcada) "Marcado" else "Quero ser amigo")
                        }
                    }
                    ModeracaoBotoes(enviando, { onBloquear(sessao) }, { m -> onDenunciar(sessao, m) }, nome = "${LocalTextosDoVeiculo.current.nomePadrao} ${i + 1}")
                }
            }
        }
        if (pessoas.isNotEmpty()) {
            Text(
                "A amizade só acontece se a outra pessoa também marcar. Ela não vê quem você é até lá.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PrimaryButton("Ok", onClose, Modifier.fillMaxWidth())
    }
}

private fun umaMotoOuUmCarro(tipo: TipoVeiculo): String = if (tipo == TipoVeiculo.CARRO) "um carro" else "uma moto"
