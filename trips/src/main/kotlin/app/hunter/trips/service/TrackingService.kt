package app.hunter.trips.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.hunter.nav.NavServices
import app.hunter.nav.NavSession
import app.hunter.nav.NavStatus
import app.hunter.trips.R
import app.hunter.trips.data.TripsGraph
import app.hunter.trips.recording.AndroidLocationSource
import app.hunter.trips.recording.LocationSource
import app.hunter.trips.recording.RecordingState
import app.hunter.trips.recording.TrackingStatus
import app.hunter.trips.recording.TripRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Serviço em primeiro plano da viagem: grava o trajeto e, se houver uma rota em [NavStatus], conduz a
 * navegação (progresso, recálculo, voz) — tudo com a tela bloqueada.
 *
 * - [start] inicia/retoma; [stop] encerra e salva. Se o sistema matar o processo no meio, o trajeto fica
 *   aberto no banco e é retomado (START_STICKY → intent nulo → retoma). A navegação não é retomada.
 * - Exige permissão de localização concedida *antes* de [start] (a UI pede).
 * - A Application precisa implementar [TripsGraph].
 */
class TrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var lastNotifiedMs = 0L

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    @Volatile
    private var destroyed = false

    /** Trocável em testes instrumentados. */
    internal var locationSourceFactory: (Context) -> LocationSource = { AndroidLocationSource(it) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale.forLanguageTag("pt-BR"))
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            else -> startRecording() // ACTION_START ou reinício pelo sistema (intent nulo)
        }
        return START_STICKY
    }

    private fun startRecording() {
        if (job?.isActive == true) return
        ensureChannel()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(RecordingState()),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: Exception) {
            // Sem permissão de localização, ou reinício em segundo plano não permitido (Android 12+).
            Log.w(TAG, "não foi possível entrar em primeiro plano", e)
            stopSelf()
            return
        }

        val store = (application as TripsGraph).tripStore
        val recorder = TripRecorder(store, onState = { state ->
            TrackingStatus.publish(state)
            notifyThrottled(state)
        })
        val nav = NavSession(
            scope = scope,
            reroute = { from, to, profile, via -> NavServices.router.route(from, to.latLon, profile, via = via).firstOrNull() },
        )
        val source = locationSourceFactory(this)

        job = scope.launch {
            try {
                recorder.startOrResume(NavStatus.state.value.destination?.let { "Para ${it.name}" })
                source.updates().collect { p ->
                    recorder.onPoint(p)
                    nav.onPoint(p)?.forEach(::speak)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                Log.w(TAG, "permissão de localização revogada", e)
            } finally {
                // Encerra e salva se o usuário parou ou o GPS falhou. Se o serviço está sendo destruído pelo
                // sistema, deixa o trajeto aberto para ser retomado depois.
                if (!destroyed) {
                    withContext(NonCancellable) {
                        recorder.finish()
                        NavStatus.stop()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun stopRecording() {
        val j = job
        if (j == null) {
            stopSelf()
            return
        }
        j.cancel() // o finally do job salva o trajeto e chama stopSelf()
    }

    private fun speak(text: String) {
        if (!ttsReady || !NavStatus.voice.value) return
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "nav-${System.nanoTime()}")
    }

    private fun notifyThrottled(state: RecordingState) {
        val now = System.currentTimeMillis()
        if (now - lastNotifiedMs < 3_000) return
        lastNotifiedMs = now
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: RecordingState): Notification {
        val stats = state.stats
        val nav = NavStatus.state.value
        val text = when {
            nav.active && nav.progress != null -> {
                val p = nav.progress!!
                "${p.upcoming.instruction} · ${formatDistance(p.distanceToUpcomingM)}"
            }
            state.isRecording && stats.elapsedMs > 0 ->
                String.format(Locale.getDefault(), "%.1f km · %s", stats.distanceKm, formatDuration(stats.elapsedMs))
            else -> getString(R.string.tracking_waiting_gps)
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(if (nav.active) R.string.tracking_title_nav else R.string.tracking_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .addAction(0, getString(R.string.tracking_stop), stop)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.tracking_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    override fun onDestroy() {
        // Não finaliza o trajeto aqui: se foi o sistema que destruiu, ele é retomado na próxima partida.
        destroyed = true
        scope.cancel()
        tts?.stop()
        tts?.shutdown()
        TrackingStatus.publish(RecordingState())
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TrackingService"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "app.hunter.trips.START"
        const val ACTION_STOP = "app.hunter.trips.STOP"

        /** Inicia (ou retoma) a viagem. Chamar com o app em primeiro plano e a localização já permitida. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, TrackingService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_STOP))
        }

        internal fun formatDuration(ms: Long): String {
            val s = ms / 1000
            return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        }

        internal fun formatDistance(m: Double): String =
            if (m >= 1000) String.format(Locale.getDefault(), "%.1f km", m / 1000) else String.format(Locale.getDefault(), "%.0f m", m)
    }
}
