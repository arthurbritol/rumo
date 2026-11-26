package app.hunter.app

import app.hunter.app.veiculo.LembretesDeManutencao
import app.hunter.app.veiculo.ManutencaoDoVeiculo
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.hunter.maintenance.Odometer
import app.hunter.model.MaintenanceReminders
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Verifica a manutenção a cada 12 h (e ao abrir o app) e avisa por notificação quando um item chega perto de
 * vencer ou vence — uma vez por mudança de estado, sem repetir. Só marca como avisado se a notificação pôde ser
 * mostrada (se a permissão estiver negada, avisa depois que for concedida).
 */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as HunterApp
        val dao = app.maintenanceDao
        // Lembretes de todos os veículos não removidos (sem veículo cadastrado, nada a avisar).
        val veiculos = app.veiculos.veiculos.first()
        if (veiculos.isEmpty()) return Result.success()
        val porVeiculo = veiculos.map { v ->
            val itens = dao.observeItems(v.id).first().map { it.toModel() }
            val offset = dao.getOdometer(v.id)?.offsetKm ?: 0.0
            val tripsM = app.tripStore.observeDistanceM(v.id).first()
            ManutencaoDoVeiculo(v, itens, Odometer.currentKm(offset, tripsM))
        }
        val now = System.currentTimeMillis()

        val prefs = applicationContext.getSharedPreferences("reminders", Context.MODE_PRIVATE)
        val notified = MaintenanceReminders.decode(prefs.getString("state", null))
        val due = LembretesDeManutencao.devidos(porVeiculo, now, notified)

        if (due.isEmpty()) {
            // Itens que voltaram ao normal saem do registro (poderão avisar de novo no futuro).
            prefs.edit().putString("state", MaintenanceReminders.encode(LembretesDeManutencao.registro(porVeiculo, now))).apply()
            return Result.success()
        }
        if (!canNotify()) return Result.success() // tenta de novo na próxima execução

        ensureChannel()
        val lines = due.map { LembretesDeManutencao.linha(it, veiculos.size) }
        val open = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)?.let {
            PendingIntent.getActivity(applicationContext, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(LembretesDeManutencao.titulo(due, veiculos.size))
            .setContentText(lines.first())
            .setStyle(NotificationCompat.InboxStyle().also { s -> lines.forEach(s::addLine) })
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, n)

        prefs.edit().putString("state", MaintenanceReminders.encode(LembretesDeManutencao.registro(porVeiculo, now))).apply()
        return Result.success()
    }

    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Lembretes de manutenção", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    companion object {
        private const val CHANNEL_ID = "maintenance"
        private const val NOTIFICATION_ID = 2001

        /** Agenda a verificação periódica (uma só, mantida entre aberturas do app). */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniquePeriodicWork(
                "maintenance-reminders", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ReminderWorker>(12, TimeUnit.HOURS).build(),
            )
            runNow(context)
        }

        /** Verifica agora (ao abrir o app e depois de conceder a permissão de notificação). */
        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "maintenance-reminders-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<ReminderWorker>().build(),
            )
        }
    }
}
