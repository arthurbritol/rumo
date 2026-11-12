package app.hunter.app.nearby

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Serviço em primeiro plano do "Procurando rolê". Ele não coleta posição nem abre conexão: a notificação
 * contínua é o que mantém o app vivo (e autorizado a ler a localização) com a tela apagada ou em outro app,
 * sem a permissão de localização em segundo plano. Quem procura é o [NearbyViewModel]; ele liga e desliga este
 * serviço e recebe o "Parar" da notificação por [stopRequests].
 *
 * Não é reiniciado pelo sistema (START_NOT_STICKY): se o app morrer, a procura acaba e a pessoa some do mapa
 * dos outros em 2 minutos.
 */
class NearbyService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            _stopRequests.tryEmit(Unit)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: Exception) {
            // Sem permissão de localização, ou partida em segundo plano não permitida (Android 12+).
            Log.w(TAG, "não foi possível entrar em primeiro plano", e)
            _startFailures.tryEmit(Unit)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val stop = PendingIntent.getService(
            this, 2, Intent(this, NearbyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Procurando rolê")
            .setContentText("Outros motociclistas por perto veem uma moto no mapa, sem seu nome.")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Parar", stop)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Procurando rolê", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        private const val TAG = "NearbyService"
        private const val CHANNEL_ID = "nearby"
        private const val NOTIFICATION_ID = 1002
        const val ACTION_STOP = "app.hunter.app.nearby.STOP"

        private val _stopRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        /** Pedidos de parada vindos da notificação (o botão "Parar"). */
        val stopRequests: SharedFlow<Unit> = _stopRequests

        private val _startFailures = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        /** O serviço não conseguiu entrar em primeiro plano (ou nem iniciou): o ViewModel desliga a busca. */
        val startFailures: SharedFlow<Unit> = _startFailures

        /** Chamar com o app em primeiro plano e a localização já permitida. */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, NearbyService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "não foi possível iniciar o serviço", e)
                _startFailures.tryEmit(Unit)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NearbyService::class.java))
        }
    }
}
