package app.hunter.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hunter.app.ui.HunterTheme
import app.hunter.app.ui.PrimaryButton
import kotlinx.coroutines.launch

private data class IntroPage(
    val emoji: String,
    val title: String,
    val text: String,
    val example: List<String> = emptyList(),
)

private val pages = listOf(
    IntroPage(
        "🧭", "Navegue de moto",
        "Mapa em tela cheia, rotas para moto (rápida, curvas, sem pedágio) e curva a curva com voz.",
    ),
    IntroPage(
        "📈", "Viagens e garagem",
        "Grava cada viagem, mostra km, velocidade e altimetria. Controla abastecimento e manutenção da moto.",
    ),
    IntroPage(
        "👥", "Rolê com os amigos",
        "Veja quanto cada um rodou na semana e até onde o grupo vai com o que cada um quer gastar.",
        listOf("🏍 Ana · R$ 80 → 210 km", "🏍 Bruno · R$ 60 → 160 km"),
    ),
    IntroPage(
        "📟", "Conecte ao painel",
        "Royal Enfield (Tripper Pod e Dash), Yamaha Y-Connect e outras. Mostra a manobra no painel da moto.",
    ),
)

/** As quatro páginas da apresentação. [onFinish] vale tanto para "Pular" quanto para "Começar". */
@Composable
fun IntroPages(onFinish: () -> Unit, modifier: Modifier = Modifier) {
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onFinish) { Text("Pular") }
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { i -> IntroPageContent(pages[i]) }
        PageDots(pages.size, pager.currentPage, Modifier.align(Alignment.CenterHorizontally).padding(vertical = 14.dp))
        PrimaryButton(
            text = if (last) "Começar" else "Continuar",
            onClick = {
                if (last) onFinish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            },
        )
    }
}

@Composable
private fun IntroPageContent(p: IntroPage) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().height(160.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(p.emoji, fontSize = 72.sp) }
        Spacer(Modifier.height(24.dp))
        Text(p.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            p.text, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        p.example.forEach { line ->
            Spacer(Modifier.height(8.dp))
            Text(
                line,
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val on = i == current
            Box(
                Modifier.size(width = if (on) 20.dp else 8.dp, height = 8.dp)
                    .background(
                        if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        RoundedCornerShape(4.dp),
                    ),
            )
        }
    }
}

@Preview(showBackground = true, heightDp = 640)
@Composable
private fun IntroPagesPreview() {
    HunterTheme { IntroPages(onFinish = {}) }
}
