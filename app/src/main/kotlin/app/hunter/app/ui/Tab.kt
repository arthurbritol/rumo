package app.hunter.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import app.hunter.app.R

enum class Tab(
    val label: String,
    val icon: ImageVector,
    @DrawableRes val iconResId: Int,
) {
    Map("Mapa", Icons.Default.Place, R.drawable.fa_map_location_dot),
    Trips("Viagens", Icons.AutoMirrored.Filled.List, R.drawable.fa_route),
    Garage("Garagem", Icons.Default.Build, R.drawable.fa_motorcycle),
    Friends("Amigos", Icons.Default.Person, R.drawable.fa_users),
    Pod("Pod", Icons.Default.Settings, R.drawable.fa_bluetooth),
}

