package app.hunter.podble

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
import app.hunter.pod.CompassDirection
import app.hunter.pod.CompassFilter
import app.hunter.pod.PodPackets
import app.hunter.pod.session.PodSession
import app.hunter.pod.session.SessionState
import app.hunter.trips.recording.AndroidLocationSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * Mantém a conexão com o Pod (foreground, com a tela bloqueada) e mostra a bússola pelo rumo do GPS.
 * Reconecta sozinho quando o link cai — o Pod só anuncia ~30 s após ligar a ignição e depois que ele mesmo
 * derruba o link por watchdog.
 *
 * Fluxo: [connect] → procura `RE_DISP` → (pareado) retoma sem PIN | (novo) espera [submitPin].
 * Nunca foi executado em hardware real neste projeto.
 */
class PodService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    @Volatile private var session: PodSession? = null
    @Volatile private var currentDevice: PodDevice? = null
    private lateinit var prefs: PodPrefs

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = PodPrefs(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> shutdown()
            ACTION_PIN -> intent.getStringExtra(EXTRA_PIN)?.let { submitPin(it) }
            ACTION_FORGET -> prefs.forget().also { PodStatus.update { it.copy(pairedName = null) } }
            else -> connect()
        }
        return START_NOT_STICKY
    }

    private fun connect() {
        if (job?.isActive == true) return
        ensureChannel()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification("Procurando o Pod…"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else 0,
            )
        } catch (e: Exception) {
            Log.w(TAG, "não foi possível entrar em primeiro plano", e)
            PodStatus.update { it.copy(phase = PodPhase.IDLE, message = "Permissões de Bluetooth/localização necessárias.") }
            stopSelf()
            return
        }
        PodStatus.update { it.copy(pairedName = prefs.pairedName() ?: prefs.pairedMac()) }

        job = scope.launch {
            while (isActive) {
                runOneConnection()
                if (isActive) delay(RECONNECT_DELAY_MS)
            }
        }
    }

    private suspend fun runOneConnection() {
        val link = BlePodLink(this)
        var compassJob: Job? = null
        try {
            PodStatus.update { it.copy(phase = PodPhase.SCANNING, message = "Procurando o Pod… (ligue a ignição; ele anuncia por ~30 s)", direction = null) }
            val dropped = CompletableDeferred<Unit>()
            link.onDisconnected = { dropped.complete(Unit) }
            val device = link.connect()
            currentDevice = device
            PodStatus.update { it.copy(phase = PodPhase.CONNECTING, message = null) }

            val s = PodSession(
                transport = link, scope = scope,
                localTime = LocalTime::now, is12h = prefs.is12h,
                onFailure = { dropped.complete(Unit) },
            )
            session = s
            if (device.address == prefs.pairedMac()) {
                s.resume()
            } else {
                s.beginPairing()
            }
            when (s.state.value) {
                SessionState.AWAITING_PIN -> PodStatus.update { it.copy(phase = PodPhase.AWAITING_PIN, message = "Digite o PIN mostrado no Pod") }
                SessionState.CONNECTED -> onConnected()
                else -> dropped.complete(Unit)
            }
            compassJob = scope.launch { feedCompass(s) }
            dropped.await()
            PodStatus.update { it.copy(phase = PodPhase.SCANNING, message = "Pod desconectado. Reconectando…", direction = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "conexão falhou", e)
            PodStatus.update { it.copy(phase = PodPhase.SCANNING, message = e.message ?: "Falha ao conectar. Tentando de novo…", direction = null) }
        } finally {
            compassJob?.cancel()
            session?.close()
            session = null
            link.close()
        }
    }

    private fun onConnected() {
        PodStatus.update { it.copy(phase = PodPhase.CONNECTED, message = null, pairedName = prefs.pairedName() ?: prefs.pairedMac()) }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification("Conectado ao Pod"))
    }

    private fun submitPin(pin: String) {
        val s = session ?: return
        scope.launch {
            if (s.submitPin(pin)) {
                currentDevice?.let { prefs.markPaired(it.address, it.name) }
                onConnected()
            }
        }
    }

    /** Bússola: rumo do GPS (mais firme que o magnetômetro na moto) com histerese; só em movimento. */
    private suspend fun feedCompass(s: PodSession) {
        val filter = CompassFilter()
        var sent: CompassDirection? = null
        try {
            AndroidLocationSource(this).updates().collect { p ->
                if (s.state.value != SessionState.CONNECTED) return@collect
                val bearing = p.bearingDeg ?: return@collect
                if ((p.speedMps ?: 0f) < MIN_COMPASS_SPEED_MPS) return@collect
                val dir = filter.update(bearing)
                if (dir != sent) {
                    sent = dir
                    s.setScreen(PodPackets.compass(dir, night = isNight()))
                    PodStatus.update { it.copy(direction = dir) }
                }
            }
        } catch (e: SecurityException) {
            PodStatus.update { it.copy(message = "Sem permissão de localização: bússola desativada.") }
        }
    }

    private fun isNight(): Boolean = LocalTime.now().hour.let { it < 6 || it >= 18 }

    private fun shutdown() {
        job?.cancel()
        session?.close()
        PodStatus.update { PodUiState(pairedName = prefs.pairedName() ?: prefs.pairedMac()) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(text: String): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PodService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.pod_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.pod_disconnect), stop)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.pod_channel), NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onDestroy() {
        scope.cancel()
        session?.close()
        PodStatus.update { PodUiState(pairedName = prefs.pairedName() ?: prefs.pairedMac()) }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PodService"
        private const val CHANNEL_ID = "pod"
        private const val NOTIFICATION_ID = 1002
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val MIN_COMPASS_SPEED_MPS = 1.5f
        const val ACTION_CONNECT = "app.hunter.podble.CONNECT"
        const val ACTION_STOP = "app.hunter.podble.STOP"
        const val ACTION_PIN = "app.hunter.podble.PIN"
        const val ACTION_FORGET = "app.hunter.podble.FORGET"
        const val EXTRA_PIN = "pin"

        fun connect(context: Context) {
            context.startForegroundService(Intent(context, PodService::class.java).setAction(ACTION_CONNECT))
        }

        fun disconnect(context: Context) {
            context.startService(Intent(context, PodService::class.java).setAction(ACTION_STOP))
        }

        fun submitPin(context: Context, pin: String) {
            context.startService(Intent(context, PodService::class.java).setAction(ACTION_PIN).putExtra(EXTRA_PIN, pin))
        }

        fun forgetPairing(context: Context) {
            context.startService(Intent(context, PodService::class.java).setAction(ACTION_FORGET))
        }
    }
}
