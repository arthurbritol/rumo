package app.hunter.app.perigos

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.hunter.nav.perigos.RedePolitica

/**
 * Wi-Fi/Ethernet E sem tarifa (`NET_CAPABILITY_NOT_METERED`) = pode baixar sem perguntar. Celular, e também Wi-Fi marcado como medido
 * (hotspot do celular, roteador 4G), pedem confirmação antes de baixar o Pardal.
 */
internal fun redeSemTarifa(wifi: Boolean, ethernet: Boolean, naoMedida: Boolean): Boolean = (wifi || ethernet) && naoMedida

class RedePoliticaAndroid(context: Context) : RedePolitica {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun emWifi(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return redeSemTarifa(
            wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            naoMedida = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )
    }

    /** Modo "Economia de dados" do Android ligado numa rede com tarifa: não baixa nada em segundo plano. */
    fun economiaDeDados(): Boolean =
        cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED && cm.isActiveNetworkMetered
}
