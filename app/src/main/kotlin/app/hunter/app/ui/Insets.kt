package app.hunter.app.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Áreas que todo elemento flutuante do app respeita: barra de status, barras de navegação, laterais e recortes (câmera).
 * Sem o teclado (ele só entra onde a tela usa `safeDrawing`, como a busca e o Perfil).
 */
@Composable
fun insetsSeguros(): WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)

/**
 * Conteúdo das telas de rolagem, do login e da apresentação: dentro das áreas seguras (topo, laterais, recorte, barras e teclado)
 * e com largura máxima, centralizado, em telas largas ou deitadas.
 */
@Composable
fun ConteudoLimitado(content: @Composable () -> Unit) {
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val maxW = larguraMaximaDoConteudoDp(classeDeTela(config.screenWidthDp, config.screenHeightDp)).dp
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        androidx.compose.foundation.layout.Box(Modifier.widthIn(max = maxW).fillMaxWidth().fillMaxHeight()) { content() }
    }
}
