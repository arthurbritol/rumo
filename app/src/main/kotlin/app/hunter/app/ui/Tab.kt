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
import app.hunter.app.veiculo.podPermitido
import app.hunter.model.TipoVeiculo

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


/** As abas do veículo ativo: o Pod (Bluetooth) só aparece para moto (ou sem veículo). Mapa, Viagens, Garagem e Amigos sempre. */
fun abasVisiveis(tipo: TipoVeiculo?): List<Tab> = Tab.entries.filter { it != Tab.Pod || podPermitido(tipo) }

/** Ícone da aba: a Garagem mostra o carro quando o veículo ativo é carro; as demais não mudam. */
@DrawableRes
fun Tab.iconeParaVeiculo(tipo: TipoVeiculo?): Int =
    if (this == Tab.Garage && tipo == TipoVeiculo.CARRO) R.drawable.fa_car else iconResId
