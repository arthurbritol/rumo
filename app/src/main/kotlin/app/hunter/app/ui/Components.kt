package app.hunter.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
fun HCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

/** Rótulo pequeno em cima, valor grande embaixo. */
@Composable
fun Metric(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, color = valueColor)
    }
}

@Composable
fun MetricRow(vararg items: Pair<String, String>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        items.forEach { (l, v) -> Metric(l, v, Modifier.weight(1f)) }
    }
}

@Composable
fun Pill(text: String, color: Color) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.background(color, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun NumberField(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
fun RowCentered(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = content)
}

/**
 * Devolve uma ação que pede [all] as permissões que faltam e executa [onGranted] se [required] foram
 * concedidas (as demais são opcionais: a notificação, por exemplo, não impede de gravar).
 */
@Composable
fun rememberPermissionAction(all: List<String>, required: List<String>, onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onGranted)
    fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (required.all(::has)) latest()
    }
    return {
        if (required.all(::has)) latest() else launcher.launch(all.filterNot(::has).toTypedArray())
    }
}
