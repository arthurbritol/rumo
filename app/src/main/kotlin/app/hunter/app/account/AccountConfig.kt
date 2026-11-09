package app.hunter.app.account

import android.content.Context
import com.google.firebase.FirebaseApp

/** Conta e sincronização só existem quando o Firebase foi configurado (app/google-services.json presente no build). */
object AccountConfig {
    fun isAvailable(context: Context): Boolean =
        FirebaseApp.getApps(context).isNotEmpty()
}
