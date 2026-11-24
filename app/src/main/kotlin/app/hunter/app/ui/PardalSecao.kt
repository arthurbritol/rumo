package app.hunter.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hunter.app.perigos.PardalFormato
import app.hunter.app.perigos.PardalGraph
import app.hunter.app.perigos.PerigoPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Perfil > Avisos na estrada > "Dados de radares" (Pardal): interruptores, estados baixados, atualizar, escolher estados e atribuição. */
@Composable
fun SecaoDadosRadares() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app", Context.MODE_PRIVATE) }
    val status = PardalGraph.status
    val gatilho = PardalGraph.gatilho
    if (status == null || gatilho == null) return
    var ativo by remember { mutableStateOf(PerigoPrefs.pardalAtivo(prefs)) }
    var soWifi by remember { mutableStateOf(PerigoPrefs.pardalSoWifi(prefs)) }
    var escolhendo by remember { mutableStateOf(false) }
    val ufs by status.ufs.collectAsStateWithLifecycle()
    val agora = System.currentTimeMillis()
    val lista = PardalFormato.ordenar(ufs.values)
    val uriHandler = LocalUriHandler.current

    SectionTitle("Dados de radares")
    HCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Usar dados do Pardal", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Mais radares e lombadas, com sentido da via, guardados no aparelho. Sem ele o app usa só o OpenStreetMap.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = ativo, onCheckedChange = { ativo = it; PerigoPrefs.gravarPardalAtivo(prefs, it) })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Baixar só no Wi-Fi", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Com dados móveis o app pergunta antes de baixar.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = soWifi, onCheckedChange = { soWifi = it; PerigoPrefs.gravarPardalSoWifi(prefs, it) })
        }
        if (lista.isEmpty()) {
            Text("Nenhum estado baixado ainda.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            lista.forEach {
                Text(PardalFormato.linhaUf(it, agora), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        val paraAtualizar = PardalFormato.ufsParaAtualizar(lista)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { paraAtualizar.forEach(gatilho::baixarAgora) },
                enabled = paraAtualizar.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text("Atualizar agora") }
            OutlinedButton(onClick = { escolhendo = true }, modifier = Modifier.weight(1f)) { Text("Escolher estados") }
        }
        Text(PardalFormato.ATRIBUICAO, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { uriHandler.openUri(PardalFormato.URL_PARDAL) }) { Text("Pardal") }
            TextButton(onClick = { uriHandler.openUri(PardalFormato.URL_OSM) }) { Text("OpenStreetMap (ODbL)") }
        }
    }
    if (escolhendo) EscolherEstadosDialogo(onFechar = { escolhendo = false })
}

@Composable
private fun EscolherEstadosDialogo(onFechar: () -> Unit) {
    val status = PardalGraph.status ?: return
    val gatilho = PardalGraph.gatilho ?: return
    val atu = PardalGraph.atualizador ?: return
    val ufs by status.ufs.collectAsStateWithLifecycle()
    var catalogo by remember { mutableStateOf(atu.ufsDoCatalogo()) }
    var carregando by remember { mutableStateOf(catalogo.isEmpty()) }
    LaunchedEffect(Unit) {
        if (catalogo.isEmpty()) {
            withContext(Dispatchers.IO) { atu.carregarCatalogo() }
            catalogo = atu.ufsDoCatalogo()
            carregando = false
        }
    }
    // Estados baixados que não estão no catálogo (catálogo indisponível) continuam removíveis.
    val todas = (catalogo + ufs.filterValues { it.baixadaEm != null }.keys).distinct().sorted()
    AlertDialog(
        onDismissRequest = onFechar,
        title = { Text("Escolher estados") },
        text = {
            Column {
                if (carregando) Text("Carregando a lista…", style = MaterialTheme.typography.bodySmall)
                else if (todas.isEmpty()) Text("Lista indisponível agora. Tente de novo com internet.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(todas) { uf ->
                        val s = ufs[uf]
                        val baixado = s?.baixadaEm != null
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = baixado || s?.baixando == true,
                                enabled = s?.baixando != true,
                                onCheckedChange = { marcar -> if (marcar) gatilho.baixarAgora(uf) else PardalGraph.remover(uf) },
                            )
                            Text(
                                if (s?.baixando == true) "$uf (baixando…)" else uf,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onFechar) { Text("Fechar") } },
    )
}

/**
 * "Usar dados móveis para baixar X MB?" quando o gatilho parou por falta de Wi-Fi. Um só lugar na raiz do app (vale para o mapa e
 * o Perfil); nunca aparece navegando ou gravando (o pedido continua guardado e aparece depois).
 */
@Composable
fun PardalPedidoDialogo(navegando: Boolean, gravando: Boolean) {
    val gatilho = PardalGraph.gatilho ?: return
    val pedido by gatilho.pedido.collectAsStateWithLifecycle()
    val p = pedido
    if (p == null || !PardalFormato.mostrarPedido(p, navegando, gravando)) return
    AlertDialog(
        onDismissRequest = gatilho::dispensarPedido,
        title = { Text(PardalFormato.tituloPedido(p)) },
        text = { Text(PardalFormato.textoPedido(p)) },
        confirmButton = { TextButton(onClick = gatilho::confirmarDadosMoveis) { Text("Baixar") } },
        dismissButton = { TextButton(onClick = gatilho::dispensarPedido) { Text("Agora não") } },
    )
}
