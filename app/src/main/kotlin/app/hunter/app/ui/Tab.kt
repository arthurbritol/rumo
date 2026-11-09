package app.hunter.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class Tab(val label: String, val icon: ImageVector) {
    Map("Mapa", Icons.Default.Place),
    Trips("Viagens", Icons.AutoMirrored.Filled.List),
    Garage("Garagem", Icons.Default.Build),
    Pod("Pod", Icons.Default.Settings),
}
