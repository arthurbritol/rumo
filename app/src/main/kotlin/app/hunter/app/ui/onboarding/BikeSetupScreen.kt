package app.hunter.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.hunter.app.ui.HunterTheme
import app.hunter.app.ui.NumberField
import app.hunter.app.ui.PrimaryButton
import app.hunter.app.ui.MarcaModeloCampos
import app.hunter.app.ui.TipoVeiculoSelector
import app.hunter.app.ui.VeiculoIcone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Cadastro do veículo (moto ou carro). [short] (sem conta): só km e tanque. [name] é o nome da conta, usado no perfil.
 * Salva por [saver] e então chama [onDone]; "Preencher depois" chama [onDone] sem gravar nada.
 */
@Composable
fun BikeSetupScreen(
    short: Boolean,
    name: String,
    saver: BikeSaver,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val form = remember(short) { BikeForm(short) }
    // BikeForm não é observável pelo Compose: cada mudança incrementa a versão para recompor.
    var version by remember { mutableIntStateOf(0) }
    var errors by remember { mutableStateOf<List<FormError>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    @Suppress("UNUSED_EXPRESSION") version // lê a versão: recompõe quando o formulário muda

    fun changed() {
        version++
        if (errors.isNotEmpty()) errors = form.validate()
    }
    fun errorOf(f: FormField) = errors.firstOrNull { it.field == f }?.message

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VeiculoIcone(form.tipo, Modifier.size(40.dp), MaterialTheme.colorScheme.primary)
        Text("Seu veículo", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            if (short) "Informe os km do painel e o tanque para o hodômetro e a autonomia."
            else "Com o veículo cadastrado, o hodômetro e a autonomia já funcionam.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TipoVeiculoSelector(form.tipo, { form.escolherTipo(it); changed() })

        if (!short) {
            OutlinedTextField(
                form.apelido, { form.apelido = it.take(BikeForm.MAX_TEXT); changed() },
                label = { Text("Apelido (opcional)") }, singleLine = true, isError = errorOf(FormField.NICKNAME) != null,
                supportingText = errorOf(FormField.NICKNAME)?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
            MarcaModeloCampos(
                tipo = form.tipo, marca = form.brand, modelo = form.model,
                erroMarca = errorOf(FormField.BRAND), erroModelo = errorOf(FormField.MODEL),
            ) { m, mo -> form.selectBike(m, mo); changed() }
            OutlinedTextField(
                form.year, { form.year = it.filter(Char::isDigit).take(4); changed() },
                label = { Text("Ano (opcional)") }, singleLine = true, isError = errorOf(FormField.YEAR) != null,
                supportingText = errorOf(FormField.YEAR)?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        FieldWithError(errorOf(FormField.ODOMETER)) {
            NumberField("Km atual (painel)", form.odometerKm) { form.odometerKm = it; changed() }
        }
        FieldWithError(errorOf(FormField.TANK)) {
            NumberField("Tanque (litros)", form.tankLiters) { form.editTank(it); changed() }
        }

        if (saveFailed) {
            Text("Não foi possível salvar. Tente de novo.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(4.dp))
        PrimaryButton(
            text = if (saving) "Salvando…" else "Salvar",
            enabled = !saving,
            onClick = {
                val found = form.validate()
                errors = found
                if (found.isEmpty()) {
                    saving = true
                    saveFailed = false
                    scope.launch {
                        try {
                            saver.save(form, name)
                            onDone()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            saveFailed = true
                        } finally {
                            saving = false
                        }
                    }
                }
            },
        )
        TextButton(onClick = onDone, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("Preencher depois") }
    }
}

@Composable
private fun FieldWithError(error: String?, field: @Composable () -> Unit) {
    Column {
        field()
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp, top = 2.dp))
        }
    }
}

@Preview(showBackground = true, heightDp = 760)
@Composable
private fun BikeSetupPreview() {
    HunterTheme {
        BikeSetupScreen(short = false, name = "Ana", saver = object : BikeSaver {
            override suspend fun save(form: BikeForm, name: String) = Unit
        }, onDone = {})
    }
}

@Preview(showBackground = true, heightDp = 480)
@Composable
private fun BikeSetupShortPreview() {
    HunterTheme {
        BikeSetupScreen(short = true, name = "", saver = object : BikeSaver {
            override suspend fun save(form: BikeForm, name: String) = Unit
        }, onDone = {})
    }
}
