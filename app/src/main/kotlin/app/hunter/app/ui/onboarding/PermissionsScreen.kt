package app.hunter.app.ui.onboarding

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import app.hunter.app.ui.HunterTheme
import app.hunter.app.ui.PrimaryButton

private fun stepEmoji(id: String) = when (id) {
    "location" -> "📍"
    "bluetooth" -> "🔵"
    "notifications" -> "🔔"
    else -> "🔐"
}

/** Localização aceita precisa OU aproximada; as demais exigem tudo. */
private fun isGranted(context: Context, step: PermStep): Boolean {
    fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    return if (step.id == "location") step.permissions.any(::has) else step.permissions.all(::has)
}

/**
 * Pede as permissões uma por vez, cada uma com "Agora não". Nada bloqueia: negar só leva à próxima.
 * Quando o Android nega sem poder perguntar de novo, aparece um botão que abre as configurações do app.
 * Etapas já concedidas são puladas. [onFinished] roda depois da última.
 */
@Composable
fun PermissionsScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    steps: List<PermStep> = PermissionPlan.steps(Build.VERSION.SDK_INT),
) {
    val context = LocalContext.current
    val finish by rememberUpdatedState(onFinished)
    var index by rememberSaveable { mutableIntStateOf(0) }
    var needsSettings by rememberSaveable { mutableStateOf(false) }
    var resumeTick by remember { mutableIntStateOf(0) }

    // Voltando das configurações do app a permissão pode ter sido concedida.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }

    val step = steps.getOrNull(index)

    // O próximo índice sai da própria etapa (não de um contador), então dois eventos seguidos não pulam etapas.
    fun advanceFrom(s: PermStep) {
        needsSettings = false
        index = steps.indexOf(s) + 1
    }

    LaunchedEffect(index, resumeTick, steps) {
        val s = steps.getOrNull(index)
        if (s == null) {
            finish()
        } else if (isGranted(context, s)) {
            advanceFrom(s)
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val st = step
        if (st == null || result.isEmpty()) return@rememberLauncherForActivityResult // pedido interrompido
        if (isGranted(context, st)) {
            advanceFrom(st)
        } else {
            // Negada. Sem "deveria explicar", o Android não vai mais perguntar: só as configurações resolvem.
            val activity = context.findActivity()
            val canAskAgain = activity != null &&
                st.permissions.any { it in result && ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
            needsSettings = !canAskAgain
        }
    }
    if (step == null) return

    Column(
        modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Permissões", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${index + 1} de ${steps.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Text(stepEmoji(step.id), fontSize = 64.sp)
        Spacer(Modifier.height(12.dp))
        Text(step.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            step.reason, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        if (needsSettings) {
            Text(
                "O Android não vai mais perguntar. Se quiser permitir, abra as configurações do app.",
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            PrimaryButton("Abrir configurações", { openAppSettings(context) })
        } else {
            PrimaryButton("Permitir", { launcher.launch(step.permissions.toTypedArray()) })
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { advanceFrom(step) }, modifier = Modifier.fillMaxWidth()) { Text("Agora não") }
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        // Sem tela de configurações disponível: o usuário segue com "Agora não".
    }
}

@Preview(showBackground = true, heightDp = 640)
@Composable
private fun PermissionsPreview() {
    HunterTheme { PermissionsScreen(onFinished = {}, steps = PermissionPlan.steps(33)) }
}
