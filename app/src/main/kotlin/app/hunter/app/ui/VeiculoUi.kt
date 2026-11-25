package app.hunter.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.hunter.app.HunterViewModel
import app.hunter.app.R
import app.hunter.app.ui.onboarding.FormError
import app.hunter.app.ui.onboarding.FormField
import app.hunter.app.veiculo.RegrasVeiculo
import app.hunter.app.veiculo.VeiculoForm
import app.hunter.app.veiculo.catalogoDoTipo
import app.hunter.app.veiculo.detalhe
import app.hunter.app.veiculo.modelosDoTipo
import app.hunter.app.veiculo.nomeExibicao
import app.hunter.model.TipoVeiculo
import app.hunter.model.Veiculo
import androidx.lifecycle.compose.collectAsStateWithLifecycle


private const val OUTRA_MARCA = "Outra"
private const val OUTRO_MODELO = "Outro modelo…"

/** Ícone simples do tipo (moto ou carro); a cor vem do [tint]. */
@Composable
fun VeiculoIcone(tipo: TipoVeiculo, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurface) {
    Icon(
        painter = painterResource(if (tipo == TipoVeiculo.CARRO) R.drawable.fa_car else R.drawable.fa_motorcycle),
        contentDescription = if (tipo == TipoVeiculo.CARRO) "Carro" else "Moto",
        tint = tint,
        modifier = modifier,
    )
}

/** Escolha do tipo: dois cartões lado a lado, o selecionado com borda e fundo destacados. */
@Composable
fun TipoVeiculoSelector(selecionado: TipoVeiculo, onSelecionar: (TipoVeiculo) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TipoVeiculo.entries.forEach { t ->
            val on = t == selecionado
            Surface(
                onClick = { onSelecionar(t) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                color = if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
                border = BorderStroke(if (on) 2.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    VeiculoIcone(t, Modifier.size(22.dp), if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (t == TipoVeiculo.CARRO) "Carro" else "Moto",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** Campo de escolha: botão que mostra o valor e abre um menu com as [options]. */
@Composable
fun EscolhaDeLista(label: String, value: String, options: List<String>, error: String?, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(if (value.isEmpty()) label else "$label: $value", modifier = Modifier.weight(1f))
                Text("▾")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(o) }) }
            }
        }
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp, top = 2.dp))
        }
    }
}

/**
 * Marca e modelo do [tipo]: escolha no catálogo, com texto livre para marca fora da lista ("Outra") e modelo fora da
 * lista ("Outro modelo…"). [onChange] recebe marca e modelo já definidos.
 */
@Composable
fun MarcaModeloCampos(
    tipo: TipoVeiculo,
    marca: String,
    modelo: String,
    erroMarca: String?,
    erroModelo: String?,
    onChange: (marca: String, modelo: String) -> Unit,
) {
    val nomes = catalogoDoTipo(tipo).map { it.name }
    var marcaLivre by remember(tipo) { mutableStateOf(marca.isNotEmpty() && (marca == OUTRA_MARCA || marca !in nomes)) }
    var modeloLivre by remember(tipo) {
        mutableStateOf(marca.isNotEmpty() && modelo.isNotEmpty() && modelo !in modelosDoTipo(tipo, marca))
    }
    EscolhaDeLista(
        label = "Marca", value = if (marcaLivre) OUTRA_MARCA else marca, options = nomes,
        error = if (marcaLivre) null else erroMarca,
    ) { picked ->
        marcaLivre = picked == OUTRA_MARCA
        modeloLivre = marcaLivre
        onChange(if (marcaLivre) "" else picked, "")
    }
    if (marcaLivre) {
        OutlinedTextField(
            marca, { onChange(it.take(RegrasVeiculo.TEXTO_MAX), modelo) },
            label = { Text("Marca (digite)") }, singleLine = true, isError = erroMarca != null,
            supportingText = erroMarca?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth(),
        )
    }
    val modelos = modelosDoTipo(tipo, marca)
    if (marca.isNotEmpty() || marcaLivre) {
        if (modelos.isEmpty() || modeloLivre) {
            OutlinedTextField(
                modelo, { onChange(marca, it.take(RegrasVeiculo.TEXTO_MAX)) },
                label = { Text("Modelo") }, singleLine = true, isError = erroModelo != null,
                supportingText = erroModelo?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth(),
            )
        } else {
            EscolhaDeLista(label = "Modelo", value = modelo, options = modelos + OUTRO_MODELO, error = erroModelo) { picked ->
                if (picked == OUTRO_MODELO) {
                    modeloLivre = true
                    onChange(marca, "")
                } else {
                    onChange(marca, picked)
                }
            }
        }
    } else if (erroModelo != null) {
        Text(erroModelo, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

/** Formulário de adicionar/editar veículo (Garagem). [permitirTrocarTipo] falso na edição: o tipo fica fixo. */
@Composable
fun VeiculoFormDialog(
    titulo: String,
    form: VeiculoForm,
    permitirTrocarTipo: Boolean,
    onDismiss: () -> Unit,
    onSalvar: (VeiculoForm) -> Unit,
) {
    var versao by remember { mutableIntStateOf(0) }
    var erros by remember { mutableStateOf<List<FormError>>(emptyList()) }
    @Suppress("UNUSED_EXPRESSION") versao // o formulário não é observável: recompõe quando a versão muda
    fun mudou() {
        versao++
        if (erros.isNotEmpty()) erros = form.validar()
    }
    fun erro(f: FormField) = erros.firstOrNull { it.field == f }?.message

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(titulo) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (permitirTrocarTipo) {
                    TipoVeiculoSelector(form.tipo, { form.escolherTipo(it); mudou() })
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VeiculoIcone(form.tipo, Modifier.size(20.dp))
                        Text(if (form.tipo == TipoVeiculo.CARRO) "Carro" else "Moto", style = MaterialTheme.typography.titleSmall)
                    }
                }
                OutlinedTextField(
                    form.apelido, { form.apelido = it.take(RegrasVeiculo.TEXTO_MAX); mudou() },
                    label = { Text("Apelido (opcional)") }, singleLine = true, isError = erro(FormField.NICKNAME) != null,
                    supportingText = erro(FormField.NICKNAME)?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth(),
                )
                MarcaModeloCampos(
                    tipo = form.tipo, marca = form.marca, modelo = form.modelo,
                    erroMarca = erro(FormField.BRAND), erroModelo = erro(FormField.MODEL),
                ) { m, mo -> form.selecionar(m, mo); mudou() }
                OutlinedTextField(
                    form.ano, { form.ano = it.filter(Char::isDigit).take(4); mudou() },
                    label = { Text("Ano (opcional)") }, singleLine = true, isError = erro(FormField.YEAR) != null,
                    supportingText = erro(FormField.YEAR)?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    form.tanque, { form.editarTanque(it); mudou() },
                    label = { Text("Tanque (litros)") }, singleLine = true, isError = erro(FormField.TANK) != null,
                    supportingText = erro(FormField.TANK)?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val achados = form.validar()
                erros = achados
                if (achados.isEmpty()) onSalvar(form)
            }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/**
 * Lista de veículos: um toque escolhe o ativo. Com [onAdicionar]/[onEditar]/[onRemover] (Garagem) mostra também as ações;
 * sem eles (Perfil) só troca o ativo.
 */
@Composable
fun VeiculosListaDialog(
    veiculos: List<Veiculo>,
    ativoId: String?,
    onEscolher: (String) -> Unit,
    onDismiss: () -> Unit,
    onAdicionar: (() -> Unit)? = null,
    onEditar: ((Veiculo) -> Unit)? = null,
    onRemover: ((Veiculo) -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Seus veículos") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (veiculos.isEmpty()) {
                    Text("Nenhum veículo cadastrado.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                veiculos.forEach { v ->
                    val ativo = v.id == ativoId
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (ativo) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(if (ativo) 2.dp else 1.dp, if (ativo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Row(
                                Modifier.fillMaxWidth().clickable { onEscolher(v.id); if (onAdicionar == null) onDismiss() },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                VeiculoIcone(v.tipo, Modifier.size(24.dp), if (ativo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(v.nomeExibicao(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    v.detalhe().takeIf { it.isNotEmpty() }?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (ativo) Text("Ativo", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                            if (onEditar != null || onRemover != null) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    onEditar?.let { TextButton(onClick = { it(v) }) { Text("Editar") } }
                                    onRemover?.let { TextButton(onClick = { it(v) }) { Text("Remover", color = MaterialTheme.colorScheme.error) } }
                                }
                            }
                        }
                    }
                }
                onAdicionar?.let {
                    OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text("Adicionar veículo") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}

/**
 * Cartão compacto do veículo ativo (topo da Garagem) e os diálogos de trocar, adicionar, editar e remover.
 * O conteúdo da Garagem é sempre do veículo ativo.
 */
@Composable
fun SeletorDeVeiculoDaGaragem(vm: HunterViewModel, modifier: Modifier = Modifier) {
    val veiculos by vm.veiculos.collectAsStateWithLifecycle()
    val ativo by vm.veiculoAtivo.collectAsStateWithLifecycle()
    var lista by remember { mutableStateOf(false) }
    var adicionando by remember { mutableStateOf<VeiculoForm?>(null) }
    var editando by remember { mutableStateOf<Pair<Veiculo, VeiculoForm>?>(null) }
    var removendo by remember { mutableStateOf<Veiculo?>(null) }

    HCard(modifier = modifier, onClick = { lista = true }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VeiculoIcone(ativo?.tipo ?: TipoVeiculo.MOTO, Modifier.size(24.dp), MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    ativo?.let { "Veículo ativo: ${it.nomeExibicao()}" } ?: "Nenhum veículo cadastrado",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                )
                Text(
                    if (ativo == null) "Toque para adicionar" else listOfNotNull(ativo?.detalhe()?.takeIf { it.isNotEmpty() }, "Toque para trocar").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (lista) {
        VeiculosListaDialog(
            veiculos = veiculos, ativoId = ativo?.id,
            onEscolher = { vm.definirVeiculoAtivo(it) },
            onDismiss = { lista = false },
            onAdicionar = { adicionando = VeiculoForm(); lista = false },
            onEditar = { v -> editando = v to VeiculoForm.de(v); lista = false },
            onRemover = { v -> removendo = v; lista = false },
        )
    }
    adicionando?.let { f ->
        VeiculoFormDialog(
            titulo = "Novo veículo", form = f, permitirTrocarTipo = true,
            onDismiss = { adicionando = null },
            onSalvar = { vm.adicionarVeiculo(it); adicionando = null },
        )
    }
    editando?.let { (base, f) ->
        VeiculoFormDialog(
            titulo = "Editar veículo", form = f, permitirTrocarTipo = false,
            onDismiss = { editando = null },
            onSalvar = { vm.editarVeiculo(base, it); editando = null },
        )
    }
    removendo?.let { v ->
        AlertDialog(
            onDismissRequest = { removendo = null },
            title = { Text("Remover ${v.nomeExibicao()}?") },
            text = { Text("O veículo some da lista, mas os registros dele (abastecimentos, manutenção e hodômetro) ficam guardados, só escondidos.") },
            confirmButton = { TextButton(onClick = { vm.removerVeiculo(v.id); removendo = null }) { Text("Remover", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { removendo = null }) { Text("Cancelar") } },
        )
    }
}

/** Linha "Veículo ativo: X" do Perfil; abre a lista para trocar o ativo (sem gerenciar veículos). */
@Composable
fun VeiculoAtivoLinha(veiculos: List<Veiculo>, ativo: Veiculo?, onEscolher: (String) -> Unit, modifier: Modifier = Modifier) {
    var aberta by remember { mutableStateOf(false) }
    HCard(modifier = modifier, onClick = { aberta = true }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VeiculoIcone(ativo?.tipo ?: TipoVeiculo.MOTO, Modifier.size(22.dp), MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(
                "Veículo ativo: ${ativo?.nomeExibicao() ?: "nenhum"}",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
            )
            Text("Trocar ▾", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (aberta) {
        VeiculosListaDialog(veiculos = veiculos, ativoId = ativo?.id, onEscolher = onEscolher, onDismiss = { aberta = false })
    }
}
