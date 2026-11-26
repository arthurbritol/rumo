package app.hunter.app

import android.app.Application
import androidx.room.Room
import app.hunter.app.data.AppDatabase
import app.hunter.app.account.AccountConfig
import app.hunter.app.data.PlaceDao
import app.hunter.app.data.SyncDao
import app.hunter.app.fuel.FuelPriceRepository
import app.hunter.app.fuel.FuelPriceSyncWorker
import app.hunter.app.perigos.PerigoPrefs
import app.hunter.app.sync.ProfilePrefs
import app.hunter.app.veiculo.RoomVeiculoStore
import app.hunter.app.veiculo.SharedPrefsVeiculoAtivo
import app.hunter.app.veiculo.VeiculoRepository
import app.hunter.maintenance.data.MaintenanceDao
import app.hunter.nav.perigos.PerigoStatus
import app.hunter.trips.data.RoomTripStore
import app.hunter.trips.data.TripDao
import app.hunter.trips.data.TripStore
import app.hunter.trips.data.TripsGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class HunterApp : Application(), TripsGraph {

    private val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "hunter.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
            .build()
    }
    val placeDao: PlaceDao by lazy { database.placeDao() }
    val syncDao: SyncDao by lazy { database.syncDao() }
    val tripDao: TripDao by lazy { database.tripDao() }
    val fuelPriceRepository: FuelPriceRepository by lazy {
        FuelPriceRepository(
            dao = database.anpFuelDao(),
            assetLoader = { name ->
                try {
                    assets.open(name)
                } catch (_: Exception) {
                    null
                }
            },
            seedVersionGet = { getSharedPreferences("anp_seed", MODE_PRIVATE).getInt("version", 0) },
            seedVersionSet = { getSharedPreferences("anp_seed", MODE_PRIVATE).edit().putInt("version", it).apply() },
        )
    }

    /** Conta e sincronização (uma por processo). */
    val account: AccountGraph by lazy { AccountGraph(database, this) }

    override val tripStore: TripStore by lazy { RoomTripStore(database.tripDao()) { veiculos.ativoAgora()?.id } }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Veículos da conta e o veículo ativo (uma instância por processo). */
    val veiculos: VeiculoRepository by lazy {
        VeiculoRepository(
            store = RoomVeiculoStore(maintenanceDao, tripDao),
            ativoStore = SharedPrefsVeiculoAtivo(getSharedPreferences("app", MODE_PRIVATE)),
            scope = appScope,
            requestSync = { account.scheduler.requestSync() },
        )
    }

    override fun onCreate() {
        super.onCreate()
        ReminderWorker.schedule(this)
        // Avisos de radar/lombada: cache em disco, interruptores salvos e (depois) o repositório já usável pelo serviço.
        app.hunter.app.perigos.PardalGraph.iniciar(this, BuildConfig.VERSION_NAME)
        PerigoStatus.definirCategorias(PerigoPrefs.ler(getSharedPreferences("app", MODE_PRIVATE)))
        if (AccountConfig.isAvailable(this)) account.start()
        FuelPriceSyncWorker.scheduleWeeklySync(this)
        // Depois da migração do banco, o veículo "Minha moto" nasce sem marca/modelo: completa pelo perfil local.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                veiculos.completarLegadoDoPerfil(ProfilePrefs.read(getSharedPreferences("app", MODE_PRIVATE)))
            } catch (_: Exception) {
                // não fatal: tenta de novo na próxima abertura
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                fuelPriceRepository.ensureSeedLoaded()
            } catch (_: Exception) {
                // Falha no carregamento inicial da semente é não fatal
            }
        }
    }
    val maintenanceDao: MaintenanceDao by lazy { database.maintenanceDao() }
}
