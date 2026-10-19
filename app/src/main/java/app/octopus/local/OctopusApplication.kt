package app.octopus.local

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.room.Room
import app.octopus.local.data.*
import app.octopus.local.domain.*
import app.octopus.local.diagnostics.DiagnosticRecorder
import app.octopus.local.providers.LiveCatalogState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class OctopusApplication : Application() {
    lateinit var graph: AppGraph
        private set
    override fun onCreate() { super.onCreate(); graph = AppGraph(this) }
}

class AppGraph(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settingsStore = SettingsStore(app)
    val settings = settingsStore.flow.stateIn(scope, SharingStarted.Eagerly, UserSettings())
    private val database = Room.databaseBuilder(app, DiagnosticDatabase::class.java, "octopus_diagnostics.db").build()
    private val reports = database.reports()
    val recorder = DiagnosticRecorder(reports)
    private val inspectionDatabase = Room.databaseBuilder(app, InspectionDatabase::class.java, "octopus_screen_inspection.db").build()
    val inspectionDao = inspectionDatabase.inspections()
    val inspections = inspectionDao.observe().stateIn(scope, SharingStarted.Eagerly, emptyList())
    // Separate storage leaves both existing diagnostic databases and their data intact.
    private val orderDatabase = Room.databaseBuilder(app, OrderDatabase::class.java, "octopus_orders.db").build()
    val orders = OrderRepository(orderDatabase.orders())
    val history = reports.observe().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val session = MutableStateFlow(SessionState())
    val serviceConnected = MutableStateFlow(false)
    val catalog = MutableStateFlow(LiveCatalogState())
    val installations = MutableStateFlow<List<ProviderInstallation>>(emptyList())
    init { refreshInstallations() }
    fun refreshInstallations() {
        installations.value = ProviderId.entries.map { provider ->
            val info = runCatching {
                if (Build.VERSION.SDK_INT >= 33) app.packageManager.getPackageInfo(provider.packageName, PackageManager.PackageInfoFlags.of(0))
                else @Suppress("DEPRECATION") app.packageManager.getPackageInfo(provider.packageName, 0)
            }.getOrNull()
            ProviderInstallation(provider, info != null, info?.versionName)
        }
    }
}
