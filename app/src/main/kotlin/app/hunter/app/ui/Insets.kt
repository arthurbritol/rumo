package app.hunter.app.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable

/**
 * Áreas que todo elemento flutuante do app respeita: barra de status, barras de navegação, laterais e recortes (câmera).
 * Sem o teclado (ele só entra onde a tela usa `safeDrawing`, como a busca e o Perfil).
 */
@Composable
fun insetsSeguros(): WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
