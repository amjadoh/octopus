package app.octopus.local.automation

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import app.octopus.local.AppGraph
import app.octopus.local.MainActivity
import app.octopus.local.BuildConfig
import app.octopus.local.domain.ProviderId
import app.octopus.local.domain.*
import app.octopus.local.diagnostics.*
import app.octopus.local.providers.*
import app.octopus.local.safety.CatalogAction
import app.octopus.local.safety.SafetyGuard
import app.octopus.local.safety.ProviderAccessRestrictions
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray

/** Evidenced HS catalog navigation plus an explicit, bounded Ridan basket preparation request. */
class CatalogNavigator(private val service: AccessibilityService, private val graph: AppGraph) {
    private val provider = ProviderId.HUNGERSTATION
    private var job: Job? = null
    private var overlay: Button? = null
    private var targetSeen = false
    private var query = ""
    private var generation = 0L
    private var phase = "فتح المنصة"
    private var lastStats = JSONObject()
    private var sensitiveSkipped = 0
    private val inspectionReader = InspectionReader()
    private var sessionEvidence: SessionEvidenceBuffer? = null
    private val traceRequest = InspectionRequest(InspectionStage.MENU, "", "")
    val active: Boolean get() = job?.isActive == true

    fun stop(message: String = "أوقفت البحث. لم تتغير أي سلة.") {
        generation++
        sessionEvidence = null
        job?.cancel(); job = null
        hideStop()
        graph.catalog.value = graph.catalog.value.copy(busy = false, message = message)
    }

    fun search(text: String): Boolean {
        if (text.isBlank() || text.length > 100) return false
        query = text.trim()
        return begin {
            phase = "فتح المنصة"
            openHome()
            phase = "فتح البحث"
            click(CatalogScreen.HOME, CatalogAction.OPEN_SEARCH) { it.id.endsWith(":id/new_search_card_view") }
            phase = "التعرف على حقل البحث"
            val search = await { HungerStationCatalog.screen(it) == CatalogScreen.SEARCH }
            phase = "إدخال اسم المطعم"
            val input = HungerStationCatalog.searchInputs(search).single()
            act(search, CatalogAction.SET_QUERY, { it.index == input.index && it.editable }, query)
            phase = "قراءة اقتراحات البحث"
            val suggestions = await { HungerStationCatalog.restaurantSuggestions(it).isNotEmpty() }
            // This is a real search suggestion, not a delivery/availability claim.
            val suggestion = HungerStationCatalog.restaurantSuggestions(suggestions)
                .firstOrNull { it.label.substringBefore(',').trim().contains(query, ignoreCase = true) }
                ?: error("لم تظهر نتيجة بحث مطابقة. جرّب اسم المطعم كما يظهر في المنصة.")
            act(suggestions, CatalogAction.SELECT_SUGGESTION, { it.id == suggestion.id && it.label == suggestion.label })
            phase = "قراءة نتائج المطاعم"
            val results = await { HungerStationCatalog.screen(it) == CatalogScreen.RESULTS }
            val restaurants = HungerStationCatalog.restaurants(results, query)
            graph.catalog.value = LiveCatalogState(busy = true, restaurants = restaurants,
                message = if (restaurants.isEmpty()) "لم تظهر مطاعم يمكن التحقق من أسمائها في النتائج المقروءة." else "مطاعم مقروءة من هنقرستيشن للعنوان النشط؛ لم تُقارن الأسعار بعد.")
        }
    }

    fun openMenu(restaurant: LiveRestaurant): Boolean = begin {
        val viewOnlyNotice = navigateToRestaurant(restaurant)
        val items = linkedMapOf<String, LiveMenuItem>()
        var reachedEnd = false
        var lastBody = ""
        for (step in 0 until 30) {
            val menu = await { HungerStationCatalog.screen(it) == CatalogScreen.MENU }
            if (step == 0) require(menu.nodes.any { n -> n.label.lineSequence().any { it == restaurant.name } }) { "لم يُتحقق اسم المطعم داخل القائمة." }
            HungerStationCatalog.menuItems(menu).forEach { item -> items[item.selector] = item }
            val body = HungerStationCatalog.menuItems(menu).joinToString { it.selector + it.name + it.listedPrice }
            if (body == lastBody && body.isNotEmpty()) break
            lastBody = body
            val moved = act(menu, CatalogAction.SCROLL_MENU, { it.scrollable && it.className == "android.widget.ScrollView" }, allowNoOp = true)
            if (!moved) { reachedEnd = true; break }
        }
        require(items.isNotEmpty()) { "تعذرت قراءة أصناف القائمة؛ لم تُنشأ قائمة بديلة." }
        graph.catalog.value = graph.catalog.value.copy(menu = LiveMenu(restaurant, items.values.toList(), System.currentTimeMillis(), reachedEnd),
            message = if (viewOnlyNotice) "عرضت المنصة تنبيه توفر؛ هذه قائمة للعرض فقط ولا تثبت إمكانية الطلب. قد تكون جزئية."
                else if (reachedEnd) "قائمة مقروءة من هنقرستيشن. الخيارات تؤخذ من صفحة كل وجبة." else "قائمة جزئية؛ لم نثبت الوصول إلى نهايتها.")
    }

    /** Opens and reads choices only. In particular it never presses flutterAddButton. */
    fun openProduct(restaurant: LiveRestaurant, requested: LiveMenuItem): Boolean {
        val previousMenu = graph.catalog.value.menu
        return begin {
            val viewOnly = navigateToRestaurant(restaurant)
            check(!viewOnly) { "المطعم يعرض تنبيه توفر؛ لن تُجهز وجبة منه." }
            phase = "العثور على الوجبة المختارة"
            var opened: LiveMenuItem? = null
            var lastBody: String? = null
            for (step in 0 until 30) {
                val menu = await { HungerStationCatalog.screen(it) == CatalogScreen.MENU }
                val matches = menu.nodes.mapNotNull { n -> HungerStationCatalog.menuItem(n)?.let { n to it } }
                    .filter { (_, item) -> item.selector == requested.selector && item.name == requested.name }
                if (matches.size > 1) error("ظهرت وجبتان غير قابلتين للتمييز؛ توقفت العملية.")
                if (matches.size == 1) {
                    val (node, item) = matches.single()
                    check(node.enabled) { "الوجبة غير متاحة في الشاشة الحالية." }
                    act(menu, CatalogAction.OPEN_PRODUCT, { it.index == node.index && HungerStationCatalog.menuItem(it) == item })
                    opened = item; break
                }
                val body = HungerStationCatalog.menuItems(menu).toString()
                if (body == lastBody) break
                lastBody = body
                if (!act(menu, CatalogAction.SCROLL_MENU, { it.scrollable && it.className == "android.widget.ScrollView" }, allowNoOp = true)) break
            }
            val selected = opened ?: error("لم تعد الوجبة المختارة ظاهرة؛ لم تُستبدل بوجبة أخرى.")
            phase = "قراءة خيارات الوجبة"
            val reader = HungerStationProductReader(restaurant, selected)
            var previous: CatalogFrame? = null
            for (step in 0 until 30) {
                val product = await { HungerStationCatalog.screen(it) == CatalogScreen.PRODUCT }
                reader.observe(product)
                if (reader.optionsEndObserved || product == previous) break
                previous = product
                if (!act(product, CatalogAction.SCROLL_PRODUCT, { it.scrollable && it.className == "android.widget.ScrollView" }, allowNoOp = true)) break
            }
            val details = reader.finish(System.currentTimeMillis())
            graph.catalog.value = graph.catalog.value.copy(menu = previousMenu, product = details,
                message = if (details.optionsComplete) "اختر خيارات الوجبة لحفظها في طلبك داخل الأخطبوط. لم تتغير سلة المنصة."
                    else "قراءة خيارات الوجبة جزئية أو غير مدعومة؛ لن تُضاف بخيارات ناقصة.")
        }
    }

    private suspend fun navigateToRestaurant(restaurant: LiveRestaurant): Boolean {
        phase = "فتح المطعم"
        // Re-run the known search when reopening, rather than assuming a stale screen position.
        openHome()
        click(CatalogScreen.HOME, CatalogAction.OPEN_SEARCH) { it.id.endsWith(":id/new_search_card_view") }
        val search = await { HungerStationCatalog.screen(it) == CatalogScreen.SEARCH }
        val input = HungerStationCatalog.searchInputs(search).single()
        act(search, CatalogAction.SET_QUERY, { it.index == input.index && it.editable }, restaurant.name)
        val suggestions = await { HungerStationCatalog.restaurantSuggestions(it).isNotEmpty() }
        val matching = HungerStationCatalog.restaurantSuggestions(suggestions).filter {
            it.label.substringBefore(',').trim() == restaurant.name }
        require(matching.isNotEmpty()) { "تعذرت مطابقة اسم المطعم في البحث." }
        val selectedSuggestion = matching.first()
        act(suggestions, CatalogAction.SELECT_SUGGESTION, { it.index == selectedSuggestion.index && it.label == selectedSuggestion.label })
        val results = await { HungerStationCatalog.screen(it) == CatalogScreen.RESULTS }
        act(results, CatalogAction.OPEN_RESTAURANT, { it.id.endsWith(":id/title") && it.label == restaurant.name })
        var viewOnlyNotice = false
        val landing = await { HungerStationCatalog.screen(it) in setOf(CatalogScreen.MENU, CatalogScreen.RESTAURANT_NOTICE) }
        if (HungerStationCatalog.screen(landing) == CatalogScreen.RESTAURANT_NOTICE) {
            viewOnlyNotice = true
            act(landing, CatalogAction.VIEW_MENU_NOTICE, { it.clickable && it.label == "عرض قائمة المطعم" })
        }
        val menu = await { HungerStationCatalog.screen(it) == CatalogScreen.MENU }
        require(menu.nodes.any { n -> n.label.lineSequence().any { it == restaurant.name } }) { "لم يُتحقق اسم المطعم داخل القائمة." }
        return viewOnlyNotice
    }

    /** Explicit UI request only. Never replaces a nonmatching basket or opens checkout. */
    fun prepareRidan(details: ConfiguredProduct, selection: ProductSelection): Boolean {
        if (!RidanCartProgram.supports(details, selection, System.currentTimeMillis())) return false
        val priorMenu = graph.catalog.value.menu
        return begin {
            phase = "تجهيز سلة ريدان"
            val restaurant = LiveRestaurant(details.restaurant, null, details.restaurant)
            check(!navigateToRestaurant(restaurant)) { "ريدان غير متاح للطلب حاليًا." }
            var menu = await { HungerStationCatalog.screen(it) == CatalogScreen.MENU }
            val hasBasket = menu.nodes.any { it.id == "FlutterMenu_goToCartButton" }
            if (!hasBasket) {
                val program = RidanCartProgram.create(details, selection, menu, System.currentTimeMillis())
                    ?: error("تعذر إثبات سلة ريدان الفارغة؛ لم تتغير السلال.")
                val item = menu.nodes.singleOrNull { HungerStationCatalog.menuItem(it)?.let { item ->
                    item.name == details.product.title && item.selector == details.product.id } == true }
                    ?: error("الوجبة المختارة غير ظاهرة؛ لم تُستبدل بصنف آخر.")
                act(menu, CatalogAction.OPEN_PRODUCT, { it == item })
                val product = await { HungerStationCatalog.screen(it) == CatalogScreen.PRODUCT }
                val reader = HungerStationProductReader(restaurant, LiveMenuItem(details.product.title, details.product.listedPriceHalala, details.product.id))
                reader.observe(product)
                val current = reader.finish(System.currentTimeMillis())
                check(RidanCartProgram.supports(current, selection, System.currentTimeMillis())) { "تغيرت خيارات الوجبة؛ توقفت الإضافة." }
                val add = product.nodes.singleOrNull { it.id == "flutterAddButton" }
                    ?: error("لم يظهر زر إضافة واحد موثوق.")
                // Consume before dispatch. Timeout/failure must never repeat the add.
                act(product, CatalogAction.ADD_RIDAN, { it == add }, ridanProgram = program)
                menu = await { HungerStationCatalog.screen(it) == CatalogScreen.MENU && it.hasId("FlutterMenu_goToCartButton") }
            }
            val count = menu.nodes.singleOrNull { it.id == "FlutterMenu_goToCartQuantity" }?.label?.trim()
            check(count in setOf("1", "١")) { "سلة ريدان تحتوي أصنافًا أو كميات مختلفة؛ لم أعدلها أو أكرر الإضافة." }
            val target = HungerStationBasketControls.openTarget(menu) ?: error("تعذر تحديد زر السلة الموثوق.")
            val command = BasketOpenCommand(menu, target, System.currentTimeMillis())
            act(menu, CatalogAction.OPEN_BASKET, { it == target }, basketCommand = command)
            val cart = await { HungerStationCatalog.screen(it) == CatalogScreen.CART }
            // The fixed footer's count is one, and exactly one fully parsed row must agree.
            val evidence = BasketEvidenceReader.hungerStation(cart, endOfCartObserved = false)
            check(evidence.restaurant == RidanCartProgram.RESTAURANT && evidence.lines.size == 1 &&
                cart.nodes.count { it.id == "FlutterCart_cartItem" } == 1 && evidence.lines.single().let {
                    it.name == details.product.title && it.quantity == 1 && it.choices.isEmpty() }) {
                "لم تتطابق السلة المقروءة مع الوجبة؛ لا توجد إضافة أخرى أو تأكيد طلب."
            }
            graph.catalog.value = graph.catalog.value.copy(menu = priorMenu,
                message = "تم التحقق من سلة ريدان: ${details.product.title}، الكمية 1. لم يُؤكد طلب أو دفع. سعر الأصناف ليس إجمالي التوصيل.",
                preparedBasket = evidence)
        }
    }

    fun openPreparedRidanBasket(): Boolean {
        val saved = graph.catalog.value.preparedBasket ?: return false
        val draft = graph.draft.value ?: return false
        if (saved.restaurant != RidanCartProgram.RESTAURANT || draft.restaurant != saved.restaurant ||
            draft.items.size != 1 || draft.items.single().let { it.name != RidanCartProgram.TITLE || it.quantity != 1 || it.options.isNotEmpty() }) return false
        return begin(returnOnSuccess = false) {
            phase = "التحقق من سلة ريدان قبل التسليم"
            check(!navigateToRestaurant(LiveRestaurant(RidanCartProgram.RESTAURANT, null, RidanCartProgram.RESTAURANT))) {
                "المطعم غير متاح حاليًا؛ لم يتغير طلبك."
            }
            val menu = await { HungerStationCatalog.screen(it) == CatalogScreen.MENU }
            check(menu.nodes.singleOrNull { it.id == "FlutterMenu_goToCartQuantity" }?.label?.trim() in setOf("1", "١")) {
                "تغيرت السلة؛ لم تُعدّل ولم تُضف وجبة."
            }
            val target = HungerStationBasketControls.openTarget(menu) ?: error("تعذر فتح السلة بأمان.")
            act(menu, CatalogAction.OPEN_BASKET, { it == target }, basketCommand = BasketOpenCommand(menu, target, System.currentTimeMillis()))
            val cart = await { HungerStationCatalog.screen(it) == CatalogScreen.CART }
            val actual = BasketEvidenceReader.hungerStation(cart, false)
            check(actual.restaurant == saved.restaurant && actual.lines == saved.lines &&
                cart.nodes.count { it.id == "FlutterCart_cartItem" } == 1) { "تغيرت السلة منذ التجهيز؛ راجعها قبل المتابعة." }
            graph.catalog.value = graph.catalog.value.copy(preparedBasket = actual,
                message = "فُتحت سلة ريدان وتحققت الوجبة والكمية مجددًا. التحكم الآن لك؛ لم يُؤكد طلب أو دفع.")
        }
    }

    private fun begin(returnOnSuccess: Boolean = true, block: suspend () -> Unit): Boolean {
        if (active || graph.session.value.active || !graph.settings.value.catalogConsent || provider !in graph.settings.value.allowed) return false
        graph.refreshInstallations()
        val installed = graph.installations.value.singleOrNull { it.provider == provider && it.installed } ?: return false
        if (installed.version != HungerStationCatalog.TESTED_VERSION) {
            graph.catalog.value = graph.catalog.value.copy(error = "إصدار هنقرستيشن يحتاج تحققًا قبل تشغيل التنقل.")
            return false
        }
        targetSeen = false
        if (!showStop()) return false
        val token = ++generation
        val evidence = SessionEvidenceBuffer().also { sessionEvidence = it }
        val evidenceGeneration = graph.inspectionRecorder.generation()
        graph.catalog.value = graph.catalog.value.copy(busy = true, error = null, menu = null, product = null, restaurants = emptyList(), message = "جارٍ قراءة البيانات من هنقرستيشن…")
        job = graph.scope.launch {
            var outcome = "COMPLETED"
            try { withTimeout(150_000) { block() }; if (returnOnSuccess) returnToApp() }
            catch (cancelled: CancellationException) {
                outcome = if (cancelled is TimeoutCancellationException) "TIMEOUT" else "STOPPED"
                if (cancelled is TimeoutCancellationException) {
                    graph.catalog.value = graph.catalog.value.copy(error = "انتهت مهلة $phase. لم تتغير السلال.")
                    returnToApp()
                }
                throw cancelled
            } catch (failure: Exception) {
                outcome = "FAILED"
                // Only our own fixed errors are surfaced; no provider exception text or screen dump is logged.
                graph.catalog.value = graph.catalog.value.copy(error = failure.message?.takeIf { it.any { c -> c in '\u0600'..'\u06ff' } }
                    ?: "توقفت العملية عند شاشة غير مدعومة. لم تتغير السلال.")
                returnToApp()
            } finally {
                val frames = evidence.drain()
                // Capture has already stopped. Persist only bounded, redacted records, with no network operation.
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { graph.inspectionRecorder.recordAutomaticEvidence(provider, installed.version ?: "unknown", frames, outcome, evidenceGeneration) }
                }
                if (BuildConfig.DEBUG) withContext(NonCancellable + Dispatchers.IO) {
                    service.filesDir.resolve("catalog-frame-stats.json").writeText(lastStats.toString(), Charsets.UTF_8)
                }
                if (generation == token) { sessionEvidence = null; hideStop(); job = null; graph.catalog.value = graph.catalog.value.copy(busy = false) }
            }
        }
        return true
    }

    private suspend fun openHome() {
        val launch = service.packageManager.getLaunchIntentForPackage(provider.packageName) ?: error("هنقرستيشن غير مثبت.")
        service.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // Launch resumes the provider's last activity; it does not promise to open Home.
        var frame = await { HungerStationCatalog.screen(it) == CatalogScreen.HOME || CatalogCommands.backTarget(it) != null }
        repeat(6) {
            val screen = HungerStationCatalog.screen(frame)
            if (screen == CatalogScreen.HOME) return
            val target = CatalogCommands.backTarget(frame) ?: error("لا يوجد رجوع معروف من هذه الشاشة.")
            act(frame, CatalogAction.RETURN_TO_CATALOG, { it.index == target.index && it == target })
            frame = await {
                HungerStationCatalog.screen(it) != screen &&
                    (HungerStationCatalog.screen(it) == CatalogScreen.HOME || CatalogCommands.backTarget(it) != null)
            }
        }
        error("تعذر الرجوع إلى بداية البحث عبر الشاشات المعروفة.")
    }

    private suspend fun await(predicate: (CatalogFrame) -> Boolean): CatalogFrame = withTimeout(15_000) {
        var fingerprint: String? = null
        var since = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            checkSession()
            val root = service.rootInActiveWindow
            if (root == null) { delay(200); continue }
            try {
                if (root.packageName?.toString() != provider.packageName) {
                    if (targetSeen) error("غادرت المنصة؛ توقفت العملية.")
                    delay(200); continue
                }
                targetSeen = true
                val frame = capture(root)
                val structure = inspectionReader.read(root, traceRequest)
                sessionEvidence?.observe(SessionEvidence("AUTO_CATALOG_${HungerStationCatalog.screen(frame).name}",
                    structure.nodes, structure.count, structure.truncated, System.currentTimeMillis()), SystemClock.elapsedRealtime())
                lastStats = JSONObject().put("phase", phase).put("screen", HungerStationCatalog.screen(frame).name)
                    .put("complete", frame.complete).put("nodeCount", frame.nodes.size).put("sensitiveSkipped", sensitiveSkipped)
                    .put("editables", frame.nodes.count { it.editable }).put("ids", JSONArray(frame.nodes.map { it.id }.filter { it.isNotBlank() }.distinct()))
                if (predicate(frame)) {
                    val value = frame.nodes.joinToString { it.id + it.label + it.parent }
                    if (value != fingerprint) { fingerprint = value; since = SystemClock.elapsedRealtime() }
                    if (SystemClock.elapsedRealtime() - since >= 650) return@withTimeout frame
                } else { fingerprint = null }
            } finally { @Suppress("DEPRECATION") root.recycle() }
            delay(200)
        }
        @Suppress("UNREACHABLE_CODE") error("Unreachable")
    }

    private fun checkSession() {
        check(graph.settings.value.catalogConsent && provider in graph.settings.value.allowed) { "تغير السماح؛ توقفت العملية." }
        val lock = service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        check(!lock.isKeyguardLocked) { "قُفل الجهاز؛ توقفت العملية." }
    }

    private fun capture(root: AccessibilityNodeInfo): CatalogFrame {
        val nodes = mutableListOf<CatalogNode>()
        var complete = true
        sensitiveSkipped = 0
        fun walk(node: AccessibilityNodeInfo, parent: Int, depth: Int) {
            if (nodes.size >= 1800 || depth > 40) { complete = false; return }
            if (!node.isVisibleToUser) return
            val id = node.viewIdResourceName.orEmpty()
            // Personal home-header data is unnecessary for the catalog and never enters its state.
            if (id.contains(Regex("checkout|payment|credit.?card|bank.?card|cvv", RegexOption.IGNORE_CASE)))
                error("ظهرت شاشة مالية خارج مسار قراءة المطاعم؛ توقفت العملية.")
            val private = privateId(id)
            if (node.isPassword) error("ظهرت شاشة كلمة مرور؛ توقفت القراءة.")
            if (android.os.Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive && !private) { sensitiveSkipped++; complete = false; return }
            val index = nodes.size
            val raw = if (private || node.isEditable) "" else (node.text ?: node.contentDescription)?.toString().orEmpty()
            if (raw.length > 1000) { complete = false; return }
            val label = raw
            ProviderAccessRestrictions.detect(label)?.let { error(it.userMessage) }
            if (!private && !node.isEditable) {
                ProviderAccessRestrictions.detect(node.contentDescription)?.let { error(it.userMessage) }
            }
            if (Regex("تسجيل الدخول|كلمة المرور|رمز التحقق|captcha|card number|verification code|password", RegexOption.IGNORE_CASE).containsMatchIn(label))
                error("ظهرت شاشة دخول أو حماية؛ أكملها بنفسك داخل المنصة.")
            nodes += CatalogNode(index, parent, id, label, node.className?.toString().orEmpty(), node.isClickable, node.isEditable, node.isScrollable, node.isEnabled)
            if (private || node.isEditable) return
            for (i in 0 until node.childCount.coerceAtMost(300)) node.getChild(i)?.let { child ->
                try { walk(child, index, depth + 1) } finally { @Suppress("DEPRECATION") child.recycle() }
            }
        }
        walk(root, -1, 0)
        return CatalogFrame(nodes, complete)
    }

    private suspend fun click(screen: CatalogScreen, action: CatalogAction, selector: (CatalogNode) -> Boolean) =
        act(await { HungerStationCatalog.screen(it) == screen }, action, selector)

    private suspend fun act(previous: CatalogFrame, action: CatalogAction, selector: (CatalogNode) -> Boolean,
        text: String? = null, allowNoOp: Boolean = false, ridanProgram: RidanCartProgram? = null,
        basketCommand: BasketOpenCommand? = null): Boolean {
        currentCoroutineContext().ensureActive(); checkSession()
        val root = service.rootInActiveWindow ?: error("اختفت الشاشة قبل الإجراء.")
        try {
            check(root.packageName?.toString() == provider.packageName) { "غادرت المنصة؛ توقفت العملية." }
            val fresh = capture(root)
            require(fresh == previous) { "تغيرت الشاشة؛ أوقفت الإجراء بدل النقر على عنصر قديم." }
            val selected = fresh.nodes.filter(selector)
            require(selected.size == 1) { "تعذر تحديد عنصر فريد؛ توقفت العملية." }
            val node = selected.single()
            check(node.enabled) { "العنصر غير متاح؛ توقفت العملية." }
            val allowed = when (action) {
                CatalogAction.ADD_RIDAN -> graph.settings.value.catalogConsent &&
                    ridanProgram?.consumeAdd(fresh, node, System.currentTimeMillis()) == true
                CatalogAction.OPEN_BASKET -> basketCommand != null && SafetyGuard().allowsBasketOpen(basketCommand,
                    fresh, root.packageName?.toString(), HungerStationCatalog.TESTED_VERSION,
                    graph.settings.value.catalogConsent, currentCoroutineContext().isActive) &&
                    basketCommand.consume(fresh, HungerStationCatalog.TESTED_VERSION, true, System.currentTimeMillis())
                else -> SafetyGuard().allowsCatalogAction(action, HungerStationCatalog.screen(fresh), root.packageName?.toString(),
                    graph.settings.value.catalogConsent, currentCoroutineContext().isActive, fresh.complete, node.label)
            }
            check(allowed) { "الإجراء خارج نطاق العملية المسموحة." }
            fun locate(current: AccessibilityNodeInfo, cursor: IntArray): AccessibilityNodeInfo? {
                if (!current.isVisibleToUser) return null
                val private = privateId(current.viewIdResourceName.orEmpty())
                if (current.isPassword || (android.os.Build.VERSION.SDK_INT >= 34 && current.isAccessibilityDataSensitive && !private)) return null
                val here = cursor[0]++
                if (here == node.index) return AccessibilityNodeInfo.obtain(current)
                if (private || current.isEditable) return null
                for (i in 0 until current.childCount.coerceAtMost(300)) current.getChild(i)?.let { child ->
                    try { locate(child, cursor)?.let { return it } } finally { @Suppress("DEPRECATION") child.recycle() }
                }
                return null
            }
            var target = locate(root, intArrayOf(0)) ?: error("العنصر لم يعد متاحًا.")
            try {
                // Samsung/HS 8.0.303 exposes a semantics EditText containing the
                // actual editor. Keep editable branches out of capture; inspect
                // only this observed search anchor, without reading child text.
                if (action == CatalogAction.SET_QUERY &&
                    target.viewIdResourceName == "UniversalSearch_search_bar_text_field") {
                    val editors = mutableListOf<AccessibilityNodeInfo>()
                    try {
                        require(target.childCount <= 20) { "تغيرت بنية حقل البحث؛ توقفت العملية." }
                        for (i in 0 until target.childCount) {
                            val child = target.getChild(i) ?: continue
                            if (child.isVisibleToUser && child.isEditable &&
                                child.className?.toString() == "android.widget.EditText") {
                                editors += child
                            } else child.recycle()
                        }
                        require(editors.size <= 1) { "ظهر أكثر من حقل بحث داخلي؛ توقفت العملية." }
                        editors.singleOrNull()?.let { editor ->
                            check(editor.isEnabled && !editor.isPassword &&
                                editor.viewIdResourceName.isNullOrEmpty() &&
                                (android.os.Build.VERSION.SDK_INT < 34 || !editor.isAccessibilityDataSensitive)) {
                                "حقل البحث الداخلي غير متاح؛ توقفت العملية."
                            }
                            val actualEditor = AccessibilityNodeInfo.obtain(editor)
                            target.recycle()
                            target = actualEditor
                        }
                    } finally { editors.forEach { it.recycle() } }
                }
                val androidAction = when (action.operation()) {
                    ElementOperation.SET_TEXT -> AccessibilityNodeInfo.ACTION_SET_TEXT
                    ElementOperation.SCROLL_FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    ElementOperation.CLICK -> AccessibilityNodeInfo.ACTION_CLICK
                }
                if (androidAction == AccessibilityNodeInfo.ACTION_CLICK) {
                    while (!target.isClickable) {
                        val parent = target.parent ?: error("العنصر لا يدعم النقر.")
                        @Suppress("DEPRECATION") target.recycle(); target = parent
                    }
                }
                currentCoroutineContext().ensureActive(); checkSession()
                val success = if (text != null) target.performAction(androidAction, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }) else target.performAction(androidAction)
                if (!success && !allowNoOp) error("لم تدعم المنصة إجراء العنصر؛ توقفت العملية.")
                return success
            } finally { @Suppress("DEPRECATION") target.recycle() }
        } finally { @Suppress("DEPRECATION") root.recycle() }
    }

    private fun returnToApp() {
        val lock = service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (lock.isKeyguardLocked) return
        val root = service.rootInActiveWindow ?: return
        val isTarget = try { root.packageName?.toString() == provider.packageName }
            finally { @Suppress("DEPRECATION") root.recycle() }
        if (isTarget) service.startActivity(Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
    private fun privateId(id: String) = id.contains(Regex("address|location|welcome|wallet|phone|email|password|otp|verification|recipient|contact", RegexOption.IGNORE_CASE))
    private fun showStop(): Boolean = try {
        val button = Button(service).apply { text = "إيقاف بحث الأخطبوط ×"; setOnClickListener { stop() } }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 30 }
        (service.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(button, params)
        overlay = button; true
    } catch (_: Exception) { false }
    private fun hideStop() { overlay?.let { runCatching { (service.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) } }; overlay = null }
}
