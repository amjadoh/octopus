package app.octopus.local

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.room.Room
import app.octopus.local.data.*
import app.octopus.local.domain.*
import app.octopus.local.diagnostics.DiagnosticRecorder
import app.octopus.local.diagnostics.InspectionRecorder
import app.octopus.local.providers.*
import app.octopus.local.automation.LiveCatalogBridge
import app.octopus.local.catalog.*
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
    val existingQuantityConsent = MutableStateFlow<ExistingQuantityConsent?>(null)
    fun approveExistingMatchingQuantity() { existingQuantityConsent.value=draft.value?.let {ExistingQuantityConsent(it.fingerprint,System.currentTimeMillis())} }
    val draftReady = MutableStateFlow(false)
    val draftStorageError = MutableStateFlow<String?>(null)
    private val draftLock = Mutex()
    private val activeOrderId = "active-order"
    val history = reports.observe().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val session = MutableStateFlow(SessionState())
    val serviceConnected = MutableStateFlow(false)
    val catalog = MutableStateFlow(LiveCatalogState())
    private val catalogDatabase = Room.databaseBuilder(app, CatalogLibraryDatabase::class.java, "octopus_catalog.db").build()
    private val catalogRepository = CatalogLibraryRepository(catalogDatabase.library())
    private val catalogLock = Mutex()
    val catalogLibrary = MutableStateFlow(CatalogLibrary())
    val catalogReady = MutableStateFlow(false)
    private val publicDatabase = Room.databaseBuilder(app, PublicCatalogDatabase::class.java, "octopus_public_catalog.db").build()
    private val publicCache = RoomPublicCatalogCache(publicDatabase.pages())
    val publicMealSelection = MutableStateFlow<Pair<PublicMenu, PublicMeal>?>(null)
    val publicCatalog = PublicCatalogController(scope, PublicCatalogHttpClient(), publicCache,
        directoryChanged = { rows ->
            // The native adapters currently identify restaurants by name. Same-name branches
            // cannot safely inherit one another's menu or become a guessed match.
            val unambiguous = rows.groupBy { IdentityText.clean(it.name) }.filterValues { it.size == 1 }
                .values.map { it.single().live() }
            catalog.value = catalog.value.copy(restaurants = unambiguous)
        },
        menuChanged = { menu ->
            if (!catalog.value.busy && !catalog.value.comparisonRunning && catalog.value.selectedRestaurant == menu.restaurant.name)
                catalog.value = catalog.value.copy(menu = menu.live(), product = null, error = null)
        })
    val installations = MutableStateFlow<List<ProviderInstallation>>(emptyList())
    init {
        LiveCatalogBridge.attachGraph(this)
        refreshInstallations()
        scope.launch {
            catalogLock.withLock {
                try {
                    catalogLibrary.value = withContext(Dispatchers.IO) { catalogRepository.load() }
                    catalog.value = catalog.value.copy(restaurants = catalogLibrary.value.restaurants.map { it.restaurant })
                    catalogReady.value = true
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { catalog.value = catalog.value.copy(error = "تعذرت قراءة القوائم المحفوظة؛ لم تُحذف البيانات.") }
            }
        }
        scope.launch {
            draftLock.withLock {
                try {
                    draft.value = withContext(Dispatchers.IO) { orders.load(activeOrderId) }
                    draftReady.value = true
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { draftStorageError.value = "تعذرت قراءة الطلب المحفوظ. لم يُستبدل أو يُحذف." }
            }
        }
        scope.launch {
            catalogReady.first { it }
            settings.map { it.catalogArea }.distinctUntilChanged().collect { area ->
                publicMealSelection.value = null
                if (area != null) catalog.value = catalog.value.copy(restaurants = emptyList(), menu = null, product = null,
                    selectedRestaurant = null, error = null)
                publicCatalog.configure(area)
                if (area != null) PublicCatalogRefreshJob.schedule(app)
            }
        }
    }

    fun refreshPublicRestaurants(force: Boolean = true): Boolean {
        if (catalog.value.busy || catalog.value.comparisonRunning || publicCatalog.state.value.area == null) return false
        publicCatalog.refresh(force)
        return true
    }

    suspend fun addPublicMeal(menu: PublicMenu, meal: PublicMeal, quantity: Int, replace: Boolean): DraftChange = draftLock.withLock {
        if (!draftReady.value || catalog.value.busy || catalog.value.comparisonRunning)
            return@withLock DraftChange.Rejected("انتظر انتهاء العملية الحالية.")
        val current = publicCatalog.currentMenu
        if (current?.restaurant?.id != menu.restaurant.id || current.meals.none { it == meal } || quantity !in 1..99)
            return@withLock DraftChange.Rejected("تغيرت القائمة؛ افتح الوجبة مجددًا.")
        // Public pages do not expose modifier coverage. Save user intent only; compareDraft
        // freshly reads and validates required provider options before preparing any basket.
        commitDraft(DraftEditor.add(draft.value, menu.restaurant.name, CanonicalItem(meal.name,null,emptyList(),quantity),replace))
    }
    suspend fun addDraftProduct(details: ConfiguredProduct, selection: ProductSelection, replaceRestaurant: Boolean = false): DraftChange = draftLock.withLock {
        if (!draftReady.value) return@withLock DraftChange.Rejected("جارٍ تحميل الطلب المحفوظ.")
        val selected = MenuSelectionValidator().selectIntent(details, selection)
        if (selected !is SelectionResult.Accepted) return@withLock DraftChange.Rejected("خيارات الوجبة ناقصة أو غير متاحة في القائمة المحفوظة.")
        commitDraft(DraftEditor.add(draft.value, details.restaurant, selected.item, replaceRestaurant))
    }
    suspend fun changeDraftQuantity(key: String, quantity: Int): DraftChange = draftLock.withLock {
        if (!draftReady.value) return@withLock DraftChange.Rejected("جارٍ تحميل الطلب المحفوظ.")
        commitDraft(DraftEditor.quantity(draft.value, key, quantity))
    }
    suspend fun savePreparationSelection(details: ConfiguredProduct, selection: ProductSelection): DraftChange = draftLock.withLock {
        if (!draftReady.value) return@withLock DraftChange.Rejected("جارٍ تحميل الطلب المحفوظ.")
        val result = MenuSelectionValidator().selectConfigured(details, selection, System.currentTimeMillis())
        if (result !is SelectionResult.Accepted) return@withLock DraftChange.Rejected("أعد قراءة خيارات الوجبة.")
        val current = draft.value
        if (current != null && (current.restaurant != details.restaurant || current.items.size != 1 ||
            CartIdentity.itemKey(current.items.single()) != CartIdentity.itemKey(result.item)))
            return@withLock DraftChange.Rejected("طلبك المحلي مختلف؛ لم أستبدله تلقائيًا.")
        commitDraft(DraftChange.Changed(CanonicalCart(details.restaurant, null, listOf(result.item))))
    }
    private suspend fun commitDraft(change: DraftChange): DraftChange {
        if (change is DraftChange.Changed) {
            withContext(Dispatchers.IO) { if (change.cart == null) orders.delete(activeOrderId) else orders.save(change.cart, activeOrderId) }
            if (change.cart != draft.value) {
                existingQuantityConsent.value=null
                catalog.value = catalog.value.forChangedDraft(change.cart)
            }
            draft.value = change.cart
            catalog.value = catalog.value.copy(selectedRestaurant = change.cart?.restaurant,
                comparisonProgress = emptyMap())
        }
        return change
    }
    suspend fun clearOrders() = draftLock.withLock {
        withContext(Dispatchers.IO) { orders.clear() }
        draft.value = null
        existingQuantityConsent.value=null
        catalog.value = catalog.value.forChangedDraft(null)
        draftReady.value = true
        draftStorageError.value = null
    }

    suspend fun saveCatalogObservation(update: ManualCatalogUpdate, mayDisplay: () -> Boolean = { true }) = catalogLock.withLock {
        if (!catalogReady.value || !update.hasData) return@withLock
        var next = catalogLibrary.value
        val version = HungerStationCatalog.TESTED_VERSION
        if (update.restaurants.isNotEmpty()) next = next.mergeRestaurants(update.restaurants, version, System.currentTimeMillis())
        update.menu?.let { next = next.mergeMenu(it, version) }
        update.product?.let { next = next.mergeProduct(it) }
        if (next == catalogLibrary.value) return@withLock
        // Finish the local transaction before releasing the manual session. A stop cannot leave
        // an acknowledged read visible in the UI but missing from disk after restart.
        withContext(NonCancellable + Dispatchers.IO) { catalogRepository.save(next) }
        catalogLibrary.value = next
        if (!mayDisplay()) return@withLock
        val listing = update.screen in setOf(CatalogScreen.BROWSE, CatalogScreen.RESULTS)
        val displayedMenu = when {
            listing -> null
            update.menu != null -> next.menu(update.menu.restaurant.source, update.menu.restaurant.name)
            update.product != null -> next.menu(update.product.source.provider, update.product.restaurant)
            else -> catalog.value.menu
        }
        catalog.value = catalog.value.copy(restaurants = next.restaurants.map { it.restaurant },
            menu = displayedMenu,
            product = if (listing || update.menu != null) null else update.product ?: catalog.value.product,
            selectedRestaurant = update.menu?.restaurant?.name ?: update.product?.restaurant ?: catalog.value.selectedRestaurant,
            message = "حُفظت البيانات المقروءة؛ اختر منها داخل الأخطبوط. السعر والتوفر يتحققان عند المقارنة.")
    }

    suspend fun clearCatalog() = catalogLock.withLock {
        publicCatalog.stopAndJoin()
        PublicCatalogRefreshJob.cancel(app)
        withContext(Dispatchers.IO) { publicCache.clear() }
        publicMealSelection.value = null
        withContext(Dispatchers.IO) { catalogRepository.clear() }
        catalogLibrary.value = CatalogLibrary()
        catalog.value = LiveCatalogState()
        catalogReady.value = true
    }

    fun showLocalRestaurants(query: String = ""): Boolean {
        if (!catalogReady.value || catalog.value.busy || catalog.value.comparisonRunning) return false
        publicCatalog.leaveMenu()
        publicMealSelection.value = null
        val all = if (publicCatalog.state.value.area != null) publicCatalog.state.value.directory
            .groupBy { IdentityText.clean(it.name) }.filterValues { it.size == 1 }.values.map { it.single().live() }
            else catalogLibrary.value.restaurants.map { it.restaurant }
        catalog.value = catalog.value.copy(menu = null, product = null, error = null,
            restaurants = all.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) })
        return true
    }

    fun showLocalMenu(restaurant: LiveRestaurant): Boolean {
        if (catalog.value.busy || catalog.value.comparisonRunning) return false
        publicMealSelection.value = null
        if (publicCatalog.state.value.area != null) {
            val row = publicCatalog.state.value.directory.singleOrNull { it.name == restaurant.name } ?: return false
            catalog.value = catalog.value.copy(selectedRestaurant = restaurant.name, menu = null, product = null, error = null)
            return publicCatalog.openMenu(row)
        }
        val menu = catalogLibrary.value.menu(restaurant.source, restaurant.name)
        catalog.value = catalog.value.copy(selectedRestaurant = restaurant.name, menu = menu, product = null,
            error = if (menu == null) "قائمة هذا المطعم غير محفوظة. افتحها بنفسك في هنقرستيشن ثم اضغط «احفظ وارجع للأخطبوط»." else null)
        return menu != null
    }

    fun showLocalProduct(restaurant: LiveRestaurant, item: LiveMenuItem): Boolean {
        if (catalog.value.busy || catalog.value.comparisonRunning) return false
        publicCatalog.currentMenu?.takeIf { it.restaurant.name == restaurant.name }?.let { menu ->
            val meal = menu.meals.singleOrNull { it.selector(menu.restaurant.id) == item.selector && it.name == item.name } ?: return false
            catalog.value = catalog.value.copy(product = null, error = null)
            publicMealSelection.value = menu to meal
            return true
        }
        val product = catalogLibrary.value.configuredProduct(restaurant.source, restaurant.name, item.selector)
            ?.takeIf { it.product.title == item.name }
        catalog.value = catalog.value.copy(selectedRestaurant = restaurant.name, product = product,
            error = if (product == null) "خيارات هذه الوجبة غير محفوظة. افتحها بنفسك في هنقرستيشن ثم اضغط «احفظ وارجع للأخطبوط»." else null)
        return product != null
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
