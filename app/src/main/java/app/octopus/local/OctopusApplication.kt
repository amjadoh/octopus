package app.octopus.local

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.room.Room
import app.octopus.local.data.*
import app.octopus.local.domain.*
import app.octopus.local.diagnostics.DiagnosticRecorder
import app.octopus.local.diagnostics.InspectionRecorder
import app.octopus.local.providers.LiveCatalogState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    val inspectionRecorder = InspectionRecorder(inspectionDao)
    val inspections = inspectionDao.observe().stateIn(scope, SharingStarted.Eagerly, emptyList())
    // Separate storage leaves both existing diagnostic databases and their data intact.
    private val orderDatabase = Room.databaseBuilder(app, OrderDatabase::class.java, "octopus_orders.db").build()
    val orders = OrderRepository(orderDatabase.orders())
    val draft = MutableStateFlow<CanonicalCart?>(null)
    val draftReady = MutableStateFlow(false)
    val draftStorageError = MutableStateFlow<String?>(null)
    private val draftLock = Mutex()
    private val activeOrderId = "active-order"
    val history = reports.observe().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val session = MutableStateFlow(SessionState())
    val serviceConnected = MutableStateFlow(false)
    val catalog = MutableStateFlow(LiveCatalogState())
    val installations = MutableStateFlow<List<ProviderInstallation>>(emptyList())
    init {
        refreshInstallations()
        scope.launch {
            draftLock.withLock {
                try {
                    draft.value = withContext(Dispatchers.IO) { orders.load(activeOrderId) }
                    draftReady.value = true
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { draftStorageError.value = "تعذرت قراءة الطلب المحفوظ. لم يُستبدل أو يُحذف." }
            }
        }
    }
    suspend fun addDraftProduct(details: ConfiguredProduct, selection: ProductSelection, replaceRestaurant: Boolean = false): DraftChange = draftLock.withLock {
        if (!draftReady.value) return@withLock DraftChange.Rejected("جارٍ تحميل الطلب المحفوظ.")
        val selected = MenuSelectionValidator().selectConfigured(details, selection, System.currentTimeMillis())
        if (selected !is SelectionResult.Accepted) return@withLock DraftChange.Rejected("خيارات الوجبة ناقصة أو انتهت صلاحية قراءتها؛ أعد فتح خياراتها.")
        commitDraft(DraftEditor.add(draft.value, details.restaurant, selected.item, replaceRestaurant))
    }
    suspend fun changeDraftQuantity(key: String, quantity: Int): DraftChange = draftLock.withLock {
        if (!draftReady.value) return@withLock DraftChange.Rejected("جارٍ تحميل الطلب المحفوظ.")
        commitDraft(DraftEditor.quantity(draft.value, key, quantity))
    }
    private suspend fun commitDraft(change: DraftChange): DraftChange {
        if (change is DraftChange.Changed) {
            withContext(Dispatchers.IO) { if (change.cart == null) orders.delete(activeOrderId) else orders.save(change.cart, activeOrderId) }
            draft.value = change.cart
        }
        return change
    }
    suspend fun clearOrders() = draftLock.withLock {
        withContext(Dispatchers.IO) { orders.clear() }
        draft.value = null
        draftReady.value = true
        draftStorageError.value = null
    }
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
