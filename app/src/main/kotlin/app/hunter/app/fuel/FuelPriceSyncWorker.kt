package app.hunter.app.fuel

import android.content.Context
import androidx.room.Room
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.hunter.app.HunterApp
import app.hunter.app.data.AppDatabase
import app.hunter.nav.AnpFuelPayload
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Worker do WorkManager para download e sincronização periódica da base consolidada da ANP.
 *
 * Review Focus 4:
 * Se o download falhar, retornar código HTTP de erro, ou o payload estiver corrompido,
 * aborta com Result.retry() ou Result.failure() sem alterar nem corromper as tabelas existentes no Room.
 */
class FuelPriceSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val urlString = inputData.getString(KEY_DOWNLOAD_URL) ?: DEFAULT_DOWNLOAD_URL

        val repository = getRepository()

        return try {
            val payload = downloadAndParsePayload(urlString)
            if (payload.estados.isEmpty() && payload.cidades.isEmpty() && payload.postos.isEmpty()) {
                // Payload vazio ou inválido: aborta sem tocar na base existente
                Result.failure()
            } else {
                repository.ingestPayload(payload)
                Result.success()
            }
        } catch (e: Exception) {
            // Review Focus 4: Aborta com segurança sem limpar nem corromper o banco Room
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun getRepository(): FuelPriceRepository {
        customRepositoryProvider?.let { return it(applicationContext) }
        val db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "hunter.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .build()
        return FuelPriceRepository(applicationContext, db.anpFuelDao())
    }

    companion object {
        const val DEFAULT_DOWNLOAD_URL = "https://github.com/ArthurLucio/rumo-app/releases/download/anp-data-latest/anp-fuel-latest.json.gz"
        const val KEY_DOWNLOAD_URL = "download_url"
        const val UNIQUE_WORK_NAME = "fuel_price_sync_weekly"

        @Volatile
        internal var customRepositoryProvider: ((Context) -> FuelPriceRepository)? = null

        /**
         * Descomprime e faz o parse de um payload a partir de um InputStream GZIP.
         * Lança exceção se o stream for corrompido ou o JSON for inválido.
         */
        fun parseCompressedStream(stream: InputStream): AnpFuelPayload {
            val json = GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use { it.readText() }
            return AnpFuelPayload.fromJson(json)
        }

        fun downloadAndParsePayload(urlString: String): AnpFuelPayload {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw java.io.IOException("HTTP error $responseCode: ${connection.responseMessage}")
            }

            val bytes = connection.inputStream.use { it.readBytes() }
            return parseCompressedStream(ByteArrayInputStream(bytes))
        }

        /**
         * Agenda a sincronização periódica semanal via WorkManager com restrição de conexão de rede.
         */
        fun scheduleWeeklySync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<FuelPriceSyncWorker>(7, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Agenda sincronização imediata pontual (para testes ou atualização sob demanda).
         */
        fun syncNow(context: Context, url: String? = null) {
            val data = if (url != null) workDataOf(KEY_DOWNLOAD_URL to url) else androidx.work.Data.EMPTY
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<FuelPriceSyncWorker>()
                .setConstraints(constraints)
                .setInputData(data)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$UNIQUE_WORK_NAME-now",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
