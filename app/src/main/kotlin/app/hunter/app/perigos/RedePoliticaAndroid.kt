package app.hunter.app.perigos

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.hunter.nav.perigos.RedePolitica

/** Wi-Fi (ou Ethernet) = rede sem tarifa por dado; qualquer outra (celular) pede confirmação antes de baixar o Pardal. */
class RedePoliticaAndroid(context: Context) : RedePolitica {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun emWifi(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** Modo "Economia de dados" do Android ligado numa rede com tarifa: não baixa nada em segundo plano. */
    fun economiaDeDados(): Boolean =
        cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED && cm.isActiveNetworkMetered
}
