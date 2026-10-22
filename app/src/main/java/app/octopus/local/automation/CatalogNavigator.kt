package app.octopus.local.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
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
import app.octopus.local.safety.CatalogFinancialBoundary
import app.octopus.local.safety.CatalogTargetGuard
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import org.json.JSONObject
import org.json.JSONArray

/** Evidenced HS catalog navigation plus an explicit, bounded Ridan basket preparation request. */
class CatalogNavigator(private val service: AccessibilityService, private val graph: AppGraph) {
    private class ScreenChangedBeforeAction : IllegalArgumentException("تغيرت الشاشة؛ أوقفت الإجراء بدل النقر على عنصر قديم.")
    private class UserInterrupted(message: String) : IllegalStateException(message)
    private var provider = ProviderId.HUNGERSTATION
    private var comparedProduct: ConfiguredProduct? = null
    private var requestFingerprint: String? = null
    private var keetaContext: KeetaMenuReader? = null
    private val keetaMenu get() = checkNotNull(keetaContext) { "لا يوجد طلب محدد لقراءة كيتا." }
    private val keetaBasket get() = KeetaBasketReader(keetaMenu)
    private fun bindKeetaRequest(cart: CanonicalCart) {
        require(PreviewEvidence.supports(cart))
        requestFingerprint = cart.fingerprint
        val version = graph.installations.value.singleOrNull { it.provider == ProviderId.KEETA }?.version
        check(version in setOf("3.12.501", "3.13.203")) { "إصدار كيتا غير مدعوم في مسار القراءة الحالي." }
        keetaContext = KeetaMenuReader(cart.restaurant, cart.items.single().name, version!!)
    }
    private var job: Job? = null
    private var comparisonJob: Job? = null
    private var overlay: Button? = null
    private var targetSeen = false
    private var query = ""
    private var generation = 0L
    private var phase = "فتح المنصة"
    private var lastStats = JSONObject()
    private var sensitiveSkipped = 0
    private val inspectionReader = InspectionReader()
    private var sessionEvidence: SessionEvidenceBuffer? = null
    private var catalogStop = CatalogStopState()
    private var operation = CatalogOperation.BROWSE
    private var priorRecognizedScreen: Pair<ProviderId, CatalogScreen>? = null
    private val traceRequest = InspectionRequest(InspectionStage.MENU, "", "")
    val active: Boolean get() = job?.isActive == true

    private fun forgetPrepared(id: ProviderId) {
        graph.catalog.value = graph.catalog.value.copy(preparedBasket = null,
            preparedBaskets = graph.catalog.value.preparedBaskets.invalidate(id))
    }

    private fun rememberPrepared(desired: CanonicalCart, evidence: BasketEvidence, version: String) {
        check(graph.draft.value?.fingerprint == desired.fingerprint) { "تغير طلبك؛ لم أحتفظ بسلة لطلب سابق." }
        val record = PreparedBasketRecord.create(desired, evidence, version, System.currentTimeMillis())
            ?: error("السلة المقروءة لا تطابق الطلب؛ لا توجد سلة جاهزة للتسليم.")
        graph.catalog.value = graph.catalog.value.copy(preparedBasket = evidence,
            preparedBaskets = graph.catalog.value.preparedBaskets.record(record))
    }

    fun stop(message: String = "أوقفت العملية. راجع السلة إن بدأ تجهيزها.") {
        comparisonJob?.cancel(); comparisonJob = null
        generation++
        sessionEvidence = null
        job?.cancel(); job = null
        hideStop()
        graph.catalog.value = graph.catalog.value.copy(busy = false, comparisonRunning = false, message = message)
    }

    fun compareAndPrepare(): Boolean {
        val desired = graph.draft.value ?: return false
        if (!matchesSelectedRestaurant()) return false
        if (active || comparisonJob?.isActive == true || graph.session.value.active || !PreviewEvidence.supports(desired)) return false
        graph.catalog.value = graph.catalog.value.copy(comparisonRunning = true, comparisonProgress = emptyMap(), error = null)
        comparisonJob = graph.scope.launch {
            try {
                CartComparisonFlow().run(
                    readMenus = {
                        check(compareDraft()) { "تعذر بدء قراءة أسعار الوجبات." }
                        job?.join()
                    },
                    available = { id -> graph.catalog.value.previews.any { it.provider == id && it.itemSubtotal != null && it.fresh(desired, System.currentTimeMillis()) } },
                    prepare = { id ->
                        val started = if (id == ProviderId.HUNGERSTATION) prepareComparedHungerStation() else prepareComparedKeeta()
                        if (!started) "تعذر بدء تجهيز السلة من القراءة الحالية." else {
                            job?.join()
                            graph.catalog.value.error ?: if (graph.catalog.value.preparedBaskets.forRequest(desired, id) == null)
                                "لم يكتمل التحقق من السلة." else null
                        }
                    },
                    unchanged = { graph.draft.value?.fingerprint == desired.fingerprint },
                    progress = { value -> graph.catalog.value = graph.catalog.value.copy(
                        comparisonProgress = graph.catalog.value.comparisonProgress + (value.provider to value)) },
                )
                graph.catalog.value = graph.catalog.value.copy(message = "انتهت محاولة تجهيز السلتين. الإجمالي النهائي غير متحقق؛ لا توجد نتيجة أرخص مؤكدة.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: IllegalStateException) {
                graph.catalog.value = graph.catalog.value.copy(error = failure.message)
            } finally {
                graph.catalog.value = graph.catalog.value.copy(comparisonRunning = false)
                comparisonJob = null
            }
        }
        return true
    }

    fun compareDraft(): Boolean {
        val desired = graph.draft.value ?: return false
        if (!matchesSelectedRestaurant()) return false
        if (!PreviewEvidence.supports(desired)) {
            graph.catalog.value = graph.catalog.value.copy(error = "المقارنة الحالية تحتاج صنفًا واحدًا دون خيارات أو ملاحظات؛ احتُفظ بطلبك دون حساب سعر ناقص.")
            return false
        }
        return begin(operation = CatalogOperation.COMPARE_MENU) {
            requestFingerprint = desired.fingerprint
            comparedProduct = null
            graph.catalog.value = graph.catalog.value.copy(previews = emptyList())
            val hunger = try {
                phase = "قراءة سعر هنقرستيشن وعروضه"
                val restaurant = LiveRestaurant(desired.restaurant, null, desired.restaurant)
                check(!navigateToRestaurant(restaurant)) { "المطعم غير متاح للطلب في القراءة الحالية." }
                phase = "قراءة الوجبة في هنقرستيشن"
                var menu = await { HungerStationCatalog.screen(it) == CatalogScreen.MENU }
                val offerLabels = PreviewEvidence.offers(menu)
                var previousItems: String? = null
                for (page in 0 until 30) {
                    if (HungerStationCatalog.menuItems(menu).any { it.name == desired.items.single().name }) break
                    val visible = HungerStationCatalog.menuItems(menu).toString()
                    if (visible == previousItems) break
                    previousItems = visible
                    if (!act(menu, CatalogAction.SCROLL_MENU,
                            { it.scrollable && it.className == "android.widget.ScrollView" }, allowNoOp = true)) break
                    menu = await { HungerStationCatalog.screen(it) == CatalogScreen.MENU }
                }
                val item = HungerStationCatalog.menuItems(menu).singleOrNull { it.name == desired.items.single().name }
                    ?: error("لم تظهر الوجبة المطابقة في هنقرستيشن؛ لم أستبدلها بوجبة أخرى.")
                act(menu, CatalogAction.OPEN_PRODUCT, { HungerStationCatalog.menuItem(it) == item })
                phase = "قراءة خيارات هنقرستيشن"
                val details = readHungerProduct(restaurant, item).details
                graph.catalog.value = graph.catalog.value.copy(product = details)
                check(details.optionsComplete && details.product.available && details.product.listedPriceHalala != null) {
                    "لم يكتمل التحقق من الوجبة في هنقرستيشن؛ لا توجد مقارنة مؤكدة."
                }
                check(MenuSelectionValidator().selectConfigured(details,
                    ProductSelection(details.product.id, emptyMap(), 1), System.currentTimeMillis()) is SelectionResult.Accepted) {
                    "تحتاج الوجبة خيارات إلزامية؛ لم أستخدم سعرًا لطلب ناقص."
                }
                comparedProduct = details
                MenuCostPreview(ProviderId.HUNGERSTATION, desired.fingerprint,
                    Math.multiplyExact(details.product.listedPriceHalala!!, desired.items.single().quantity.toLong()), offerLabels, System.currentTimeMillis(), HungerStationCatalog.TESTED_VERSION,
                    deliveryBeforeOffers = PreviewEvidence.hungerComponent(menu, "رسوم التوصيل"),
                    minimumOrder = PreviewEvidence.hungerComponent(menu, "الحد الأدنى للطلب"),
                    rules = MenuOfferRules.fromFrame(ProviderId.HUNGERSTATION, menu))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (stopped: CatalogStoppedException) { throw stopped }
            catch (interrupted: UserInterrupted) { throw interrupted }
            catch (changed: ScreenChangedBeforeAction) { throw changed }
            catch (_: Exception) {
                failedMenuPreview(ProviderId.HUNGERSTATION, desired,
                    "تعذرت قراءة الوجبة وخياراتها في هنقرستيشن؛ سأكمل قراءة كيتا.")
            }
            graph.catalog.value = graph.catalog.value.copy(previews = listOf(hunger), message = "انتهت محاولة هنقرستيشن؛ جارٍ قراءة كيتا دون تغيير السلال…")
            val keeta = try {
                bindKeetaRequest(desired)
                // An unsupported installation is a preflight failure, never a coupon-read fallback.
                provider = ProviderId.KEETA
                checkSession()
                check(graph.installations.value.any { it.provider == provider && it.version == keetaMenu.VERSION }) {
                    "إصدار كيتا يحتاج تحققًا قبل قراءة المقارنة."
                }
                if (keetaMenu.VERSION == "3.13.203") readCurrentKeetaProduct(desired) else try {
                    targetSeen = false
                    phase = "قراءة سعر كيتا وعروضه"
                    val launch = service.packageManager.getLaunchIntentForPackage(provider.packageName) ?: error("كيتا غير مثبت.")
                    service.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    var frame = openKeetaRequest()
                    if (keetaMenu.searchCandidate(frame) != null) {
                        keetaBasketAction { keetaMenu.searchCandidate(it) }
                        frame = await { keetaMenu.menu(it) }
                    }
                    if (keetaMenu.basketDrawer(frame) || keetaMenu.product(frame)) {
                        closeKeetaReadSheet(frame)
                        frame = await { keetaReadable(it) && !keetaMenu.basketDrawer(it) && !keetaMenu.product(it) }
                    }
                    var owned: app.octopus.local.pricing.OfferRule? = null
                    if (KeetaPromotionEvidence.terms(frame)) {
                        owned = KeetaPromotionEvidence.ownedRule(frame, System.currentTimeMillis())
                        closeKeetaReadSheet(frame)
                        frame = await { KeetaPromotionEvidence.couponSheet(it) || keetaMenu.menu(it) }
                    }
                    if (KeetaPromotionEvidence.couponSheet(frame)) {
                        if (owned == null && KeetaPromotionEvidence.termsTarget(frame) != null) {
                            val termsSeenAt = System.currentTimeMillis()
                            openKeetaCoupon(frame, true)
                            frame = await { KeetaPromotionEvidence.terms(it) }
                            owned = KeetaPromotionEvidence.ownedRule(frame, termsSeenAt, true)
                            closeKeetaReadSheet(frame)
                            frame = await { KeetaPromotionEvidence.couponSheet(it) }
                        }
                        closeKeetaReadSheet(frame)
                        frame = await { keetaMenu.menu(it) }
                    }
                    if (keetaMenu.menu(frame)) {
                        frame = restoreKeetaHeader(frame)
                        var previous = ""
                        for (step in 0 until 12) {
                            val body = frame.nodes.joinToString { it.label + it.className }
                            if (body == previous) break
                            previous = body
                            if (!scrollKeetaHeader(frame)) break
                            frame = await { keetaMenu.menu(it) }
                        }
                        if (owned == null && KeetaPromotionEvidence.couponTarget(frame, keetaMenu) != null) {
                            openKeetaCoupon(frame, false)
                            frame = await { KeetaPromotionEvidence.couponSheet(it) }
                            if (KeetaPromotionEvidence.termsTarget(frame) != null) {
                                val termsSeenAt = System.currentTimeMillis()
                                openKeetaCoupon(frame, true)
                                frame = await { KeetaPromotionEvidence.terms(it) }
                                owned = KeetaPromotionEvidence.ownedRule(frame, termsSeenAt, true)
                                closeKeetaReadSheet(frame)
                                frame = await { KeetaPromotionEvidence.couponSheet(it) }
                            }
                            closeKeetaReadSheet(frame)
                            frame = await { keetaMenu.menu(it) }
                        }
                    }
                    var rules = MenuOfferRules.fromFrame(provider, frame)
                    owned?.let { coupon ->
                        rules = rules.filterNot { it.target == app.octopus.local.pricing.OfferTarget.ITEMS &&
                            it.percentBasisPoints == coupon.percentBasisPoints && it.minimum == coupon.minimum } + coupon
                        if (coupon.termsComplete && KeetaPromotionEvidence.simultaneous(frame, coupon, keetaMenu))
                            rules = rules.map { rule -> if (rule.id == coupon.id || rule.target == app.octopus.local.pricing.OfferTarget.DELIVERY)
                                rule.copy(stackGroup="observed-${coupon.id}") else rule }
                    }
                    val offers = PreviewEvidence.offers(frame)
                    if (!keetaMenu.product(frame)) {
                        openKeetaPreviewProduct(frame)
                        frame = await { keetaMenu.product(it) }
                    }
                    val price = keetaMenu.unitPrice(frame) ?: error("تعذر ربط سعر كيتا بالوجبة المطلوبة.")
                    MenuCostPreview(provider, desired.fingerprint, Math.multiplyExact(price, desired.items.single().quantity.toLong()), offers,
                        System.currentTimeMillis(), keetaMenu.VERSION, rules = rules)
                } catch (cancelled: CancellationException) {
                    if (cancelled !is TimeoutCancellationException) throw cancelled
                    keetaPriceWithoutCoupon(desired)
                } catch (stopped: CatalogStoppedException) {
                    throw stopped
                } catch (interrupted: UserInterrupted) {
                    throw interrupted
                } catch (changed: ScreenChangedBeforeAction) {
                    throw changed
                } catch (_: Exception) {
                    keetaPriceWithoutCoupon(desired)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (stopped: CatalogStoppedException) { throw stopped }
            catch (interrupted: UserInterrupted) { throw interrupted }
            catch (changed: ScreenChangedBeforeAction) { throw changed }
            catch (_: Exception) {
                failedMenuPreview(ProviderId.KEETA, desired,
                    "تعذرت قراءة الوجبة المطابقة في كيتا؛ احتُفظ بنتيجة هنقرستيشن المتاحة.")
            }
            check(graph.draft.value?.fingerprint == desired.fingerprint) { "تغير طلبك؛ أعد المقارنة." }
            graph.catalog.value = graph.catalog.value.copy(previews = listOf(hunger, keeta),
                message = if (listOf(hunger, keeta).all { it.itemSubtotal != null })
                    "قرأت أسعار الوجبات في التطبيقين. الإجماليات والرسوم المشروطة لم تُتحقق بعد."
                else "انتهت محاولة القراءة؛ تظهر نتيجة كل منصة وسبب تعذرها. لا يوجد إجمالي نهائي مؤكد.")
        }
    }

    private fun failedMenuPreview(id: ProviderId, desired: CanonicalCart, reason: String) =
        MenuCostPreview(id, desired.fingerprint, null, emptyList(), System.currentTimeMillis(),
            graph.installations.value.singleOrNull { it.provider == id }?.version.orEmpty(), error = reason)

    fun prepareComparedHungerStation(): Boolean {
        if (!matchesSelectedRestaurant()) return false
        val desired = graph.draft.value ?: return false
        val details = comparedProduct ?: return false
        val now = System.currentTimeMillis()
        if (!PreviewEvidence.supports(desired) || graph.catalog.value.previews.none {
                it.provider == ProviderId.HUNGERSTATION && it.itemSubtotal != null && it.fresh(desired, now) }) return false
        return prepareRidan(details, ProductSelection(details.product.id, emptyMap(), 1), desired.items.single().quantity)
    }

    /** Only changes the fully visible, single fixed-meal Ridan basket after explicit provider selection. */
    fun prepareComparedKeeta(handoff: Boolean = false): Boolean {
        if (!matchesSelectedRestaurant()) return false
        val desired = graph.draft.value ?: return false
        if (!PreviewEvidence.supports(desired)) return false
        if (handoff) {
            val saved = graph.catalog.value.preparedBaskets.forRequest(desired, ProviderId.KEETA)?.evidence ?: return false
            if (saved.provider != ProviderId.KEETA || saved.restaurant != desired.restaurant || saved.lines.size != 1 ||
                saved.lines.single().let { it.name != desired.items.single().name || it.quantity != desired.items.single().quantity || it.choices.isNotEmpty() }) return false
        } else if (graph.catalog.value.previews.none {
                it.provider == ProviderId.KEETA && it.itemSubtotal != null && it.fresh(desired, System.currentTimeMillis()) }) return false
        return begin(returnOnSuccess = !handoff,
            operation = if (handoff) CatalogOperation.OPEN_PREPARED_CART else CatalogOperation.BUILD_CART) {
            bindKeetaRequest(desired)
            forgetPrepared(ProviderId.KEETA)
            provider = ProviderId.KEETA; checkSession(); targetSeen = false
            check(graph.installations.value.any { it.provider == provider && it.version == keetaMenu.VERSION })
            phase = "التحقق من سلة المطعم المختار في كيتا"
            val launch = service.packageManager.getLaunchIntentForPackage(provider.packageName) ?: error("كيتا غير مثبت.")
            service.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            var frame = openKeetaRequest()
            if (keetaMenu.searchCandidate(frame) != null) {
                keetaBasketAction { keetaMenu.searchCandidate(it) }
                frame = await { keetaMenu.menu(it) }
            }
            if (!keetaMenu.basketDrawer(frame)) {
                if (keetaMenu.product(frame) || KeetaPromotionEvidence.terms(frame) || KeetaPromotionEvidence.couponSheet(frame)) {
                    closeKeetaReadSheet(frame)
                    frame = await { keetaMenu.menu(it) || KeetaPromotionEvidence.couponSheet(it) }
                    if (KeetaPromotionEvidence.couponSheet(frame)) {
                        closeKeetaReadSheet(frame); frame = await { keetaMenu.menu(it) }
                    }
                }
                if (keetaMenu.menu(frame) && keetaBasket.footerBag(frame) == null)
                    frame = restoreKeetaHeader(frame)
                if (keetaBasket.emptyMenu(frame)) {
                    check(!handoff) { "سلة كيتا أصبحت فارغة؛ أعد تجهيزها." }
                    val preview = graph.catalog.value.previews.single { it.provider == ProviderId.KEETA }
                    val expectedUnit = preview.itemSubtotal!! / desired.items.single().quantity
                    openKeetaPreviewProduct(frame)
                    frame = await { keetaMenu.product(it) }
                    check(keetaBasket.addTarget(frame, expectedUnit) != null) { "تغير سعر الوجبة أو خياراتها أو ملاحظتها؛ لم أضفها." }
                    // One add dispatch per run. A subsequent run sees the resulting basket and cannot duplicate it.
                    keetaBasketAction { keetaBasket.addTarget(it, expectedUnit) }
                    frame = await { keetaMenu.menu(it) && keetaBasket.footerBag(it) != null }
                }
                keetaBasketAction { keetaBasket.footerBag(it) }
                frame = await { keetaMenu.basketDrawer(it) }
            }
            var evidence = keetaBasket.evidence(frame) ?: error("سلة كيتا ليست وجبة المطعم المختار الواحدة المقروءة بالكامل؛ لم أغيرها.")
            val quantity = desired.items.single().quantity
            if (handoff) check(evidence.lines.single().quantity == quantity) { "تغيرت الكمية؛ أعد تجهيز السلة." }
            var changes = 0
            while (evidence.lines.single().quantity != quantity) {
                check(keetaMenu.VERSION in setOf("3.12.501", "3.13.203")) { "إصدار تعديل الكمية غير مدعوم." }
                check(++changes <= 98 && graph.draft.value?.fingerprint == desired.fingerprint)
                val before = evidence.lines.single().quantity!!
                val increase = before < quantity
                phase = "مطابقة كمية المطعم المختار في كيتا"
                keetaBasketAction { current ->
                    if (keetaBasket.evidence(current)?.lines?.singleOrNull()?.quantity != before) null
                    else keetaBasket.quantityTarget(current, increase)
                }
                val expected = before + if (increase) 1 else -1
                frame = await { keetaBasket.evidence(it)?.lines?.singleOrNull()?.quantity == expected }
                evidence = keetaBasket.evidence(frame) ?: error("تعذرت إعادة قراءة السلة بعد تعديل واحد؛ توقفت دون إعادة الإجراء.")
            }
            check(graph.draft.value?.fingerprint == desired.fingerprint)
            rememberPrepared(desired, evidence, keetaMenu.VERSION)
            graph.catalog.value = graph.catalog.value.copy(preparedBasket = evidence,
                message = "تحققت سلة كيتا: ${desired.items.single().name}، الكمية $quantity. لم يُفتح الدفع ولم يُؤكد طلب.")
        }
    }

    private suspend fun keetaBasketAction(selector: (CatalogFrame) -> CatalogNode?) {
        currentCoroutineContext().ensureActive(); checkSession()
        val root = service.rootInActiveWindow ?: error("اختفت سلة كيتا.")
        try {
            check(provider == ProviderId.KEETA && root.packageName?.toString() == provider.packageName)
            val frame = capture(root)
            val target = selector(frame) ?: error("تعذر إثبات زر السلة أو الكمية؛ لم أنفذ الإجراء.")
            check(target.label != "إتمام الدفع" && target.label != "تفريغ السلة")
            // One dispatch only, then await the exact resulting quantity. Never retry a mutation.
            check(performKeetaReadAction(root, target, AccessibilityNodeInfo.ACTION_CLICK)) { "تعذر تنفيذ إجراء السلة؛ لم أكرره." }
        } finally { @Suppress("DEPRECATION") root.recycle() }
    }

    private fun keetaReadable(frame: CatalogFrame): Boolean = keetaMenu.product(frame) ||
        keetaMenu.menu(frame) || keetaMenu.basketDrawer(frame) ||
        KeetaPromotionEvidence.couponSheet(frame) || KeetaPromotionEvidence.terms(frame) ||
        KeetaPromotionEvidence.emptySheet(frame) || keetaMenu.searchCandidate(frame) != null

    /** Uses only previously observed home/search controls. Never backs out of another live basket. */
    private suspend fun openKeetaRequest(): CatalogFrame {
        var frame = await { keetaReadable(it) || keetaMenu.homeSearch(it) != null ||
            keetaMenu.searchInput(it) != null || keetaMenu.editSearchQuery(it) != null }
        if (keetaReadable(frame)) return frame
        val home = keetaMenu.homeSearch(frame)
        val edit = keetaMenu.editSearchQuery(frame)
        if (home != null || edit != null) {
            keetaSearchAction(frame, if (home != null) KeetaSearchAction.OPEN_SEARCH else KeetaSearchAction.EDIT_QUERY)
            frame = await { keetaMenu.searchInput(it) != null }
        }
        keetaSearchAction(frame, KeetaSearchAction.ENTER_QUERY)
        frame = await { keetaMenu.searchSubmit(it) != null }
        keetaSearchAction(frame, KeetaSearchAction.SUBMIT_QUERY)
        return await { keetaMenu.searchCandidate(it) != null }
    }

    private suspend fun keetaSearchAction(previous: CatalogFrame, action: KeetaSearchAction) {
        currentCoroutineContext().ensureActive(); checkSession()
        val root = service.rootInActiveWindow ?: error("اختفت شاشة البحث في كيتا.")
        try {
            check(provider == ProviderId.KEETA && root.packageName?.toString() == provider.packageName)
            val fresh = capture(root)
            check(fresh == previous) { "تغيرت شاشة البحث؛ توقفت العملية." }
            val target = keetaMenu.searchTarget(fresh, action) ?: error("تعذر التحقق من عنصر البحث في كيتا.")
            @Suppress("DEPRECATION")
            val version = service.packageManager.getPackageInfo(provider.packageName, 0).versionName
            check(SafetyGuard().allowsKeetaSearch(action, keetaMenu, fresh, target, root.packageName?.toString(),
                version, graph.settings.value.catalogConsent, currentCoroutineContext().isActive))
            val node = locateKeetaNode(root, target) ?: error("اختفى عنصر البحث.")
            try {
                check(node.refresh() && node.isVisibleToUser && node.isEnabled && !node.isPassword &&
                    !(android.os.Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive) &&
                    node.isEditable == (action == KeetaSearchAction.ENTER_QUERY) && capture(root) == fresh)
                currentCoroutineContext().ensureActive(); checkSession()
                val success = if (action == KeetaSearchAction.ENTER_QUERY) node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, keetaMenu.restaurant)
                }) else node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                check(success) { "لم تنفذ كيتا إجراء البحث؛ توقفت دون تكراره." }
            } finally { @Suppress("DEPRECATION") node.recycle() }
        } finally { @Suppress("DEPRECATION") root.recycle() }
    }

    /** Current native menu/product capture has no evidenced delivery-mode header or coupon path. */
    private suspend fun readCurrentKeetaProduct(desired: CanonicalCart): MenuCostPreview {
        targetSeen = false
        phase = "قراءة الوجبة المطابقة في كيتا"
        val launch = service.packageManager.getLaunchIntentForPackage(provider.packageName) ?: error("كيتا غير مثبت.")
        service.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var frame = openKeetaRequest()
        if (keetaMenu.basketDrawer(frame)) {
            val basket = keetaBasket.evidence(frame)
                ?: error("تعذرت قراءة محتويات سلة كيتا الحالية بالكامل.")
            val line = basket.lines.singleOrNull()
                ?: error("سلة كيتا الحالية لا تطابق الصنف المطلوب.")
            check(basket.complete && line.name == desired.items.single().name && line.choices.isEmpty() &&
                line.lineAmount != null) {
                "سلة كيتا الحالية مختلفة عن الوجبة المطلوبة؛ لم أستخدمها للمقارنة."
            }
            if (line.quantity == desired.items.single().quantity) {
                return MenuCostPreview(ProviderId.KEETA, desired.fingerprint, line.lineAmount, emptyList(),
                    System.currentTimeMillis(), keetaMenu.VERSION,
                    "مجموع الصنف من السلة المطابقة؛ إجمالي التوصيل لم يُقرأ بعد.")
            }
            // A cart amount may include quantity-dependent offers. Read the product again rather
            // than scaling a different cart's price, and leave its quantity untouched at this stage.
            closeKeetaReadSheet(frame)
            frame = await { keetaMenu.menu(it) }
        }
        if (keetaMenu.searchCandidate(frame) != null) {
            keetaBasketAction { keetaMenu.searchCandidate(it) }
            frame = await { keetaMenu.menu(it) }
        }
        if (keetaMenu.menu(frame)) {
            openKeetaPreviewProduct(frame)
            frame = await { keetaMenu.product(it) }
        }
        return keetaMenu.itemPreview(frame, desired, System.currentTimeMillis())
            ?: error("تعذر التحقق من سعر الوجبة المطابقة في كيتا؛ لم تتغير السلة.")
    }

    private suspend fun keetaPriceWithoutCoupon(desired:CanonicalCart):MenuCostPreview {
        try {
            return withTimeout(30_000) {
                phase="قراءة سعر كيتا دون احتساب القسيمة غير المقروءة"
                var frame=await { keetaReadable(it) }
                repeat(3) {
                    if(keetaMenu.menu(frame)) return@repeat
                    check(keetaMenu.product(frame) || keetaMenu.basketDrawer(frame) ||
                        KeetaPromotionEvidence.couponSheet(frame) || KeetaPromotionEvidence.terms(frame) || KeetaPromotionEvidence.emptySheet(frame))
                    closeKeetaReadSheet(frame);frame=await { keetaReadable(it) }
                }
                check(keetaMenu.menu(frame))
                frame=restoreKeetaHeader(frame)
                val offers=PreviewEvidence.offers(frame)
                val rules=MenuOfferRules.fromFrame(ProviderId.KEETA,frame).filter { it.target==app.octopus.local.pricing.OfferTarget.DELIVERY }
                openKeetaPreviewProduct(frame)
                frame=await { keetaMenu.product(it) }
                val unit=keetaMenu.unitPrice(frame) ?: error("السعر غير مقروء")
                MenuCostPreview(ProviderId.KEETA,desired.fingerprint,Math.multiplyExact(unit,desired.items.single().quantity.toLong()),
                    offers,System.currentTimeMillis(),keetaMenu.VERSION,
                    "تعذر التحقق من القسيمة؛ لم أحسب خصمًا غير مقروء.",rules=rules)
            }
        } catch(cancelled:CancellationException) { if(cancelled !is TimeoutCancellationException) throw cancelled }
        catch(stopped:CatalogStoppedException) { throw stopped }
        catch(_:Exception) { }
        return MenuCostPreview(ProviderId.KEETA,desired.fingerprint,null,emptyList(),System.currentTimeMillis(),
            keetaMenu.VERSION,"لم تظهر صفحة المطعم المختار المعروفة في كيتا؛ سعره غير مقروء.")
    }

    /** Expand the existing menu without exiting its in-memory basket session. */
    private suspend fun restoreKeetaHeader(previous: CatalogFrame): CatalogFrame {
        var frame=previous
        val expectedQuantity=keetaBasket.menuQuantity(previous)
        repeat(3) {
            if(frame.nodes.any { it.label=="توصيل" }) return frame
            currentCoroutineContext().ensureActive();checkSession()
            val root=service.rootInActiveWindow ?: error("اختفت قائمة المطعم المختار.")
            val plan:HeaderSwipe
            try {
                check(provider==ProviderId.KEETA && root.packageName?.toString()==provider.packageName)
                val current=capture(root)
                check(keetaMenu.menu(current) && current.nodes.none { it.label=="توصيل" })
                val target=current.nodes.single { it.scrollable && it.className=="androidx.recyclerview.widget.RecyclerView" }
                val node=locateKeetaNode(root,target) ?: error("تعذر تحديد مساحة القائمة.")
                val view=Rect();val window=Rect()
                try { node.getBoundsInScreen(view);root.getBoundsInScreen(window) } finally { node.recycle() }
                val footerNodes=root.findAccessibilityNodeInfosByText("إتمام الدفع")
                val footerTop=try { footerNodes.filter { it.text?.toString()=="إتمام الدفع" }.singleOrNull()?.let {
                    val rect=Rect();it.getBoundsInScreen(rect);rect.top
                } } finally { footerNodes.forEach { it.recycle() } }
                plan=KeetaHeaderScroll.plan(HeaderViewport(view.left,view.top,view.right,view.bottom),
                    HeaderViewport(window.left,window.top,window.right,window.bottom),footerTop)
                    ?: error("مساحة التمرير غير متحققة؛ لم أنفذ إيماءة.")
            } finally { root.recycle() }
            phase="قراءة العروض من قائمة المطعم المختار الحالية"
            val path=Path().apply { moveTo(plan.x.toFloat(),plan.fromY.toFloat());lineTo(plan.x.toFloat(),plan.toY.toFloat()) }
            val gesture=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,450)).build()
            val completed=withTimeout(3_000) { suspendCancellableCoroutine<Boolean> { continuation ->
                val accepted=service.dispatchGesture(gesture,object:AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription:GestureDescription?) { if(continuation.isActive) continuation.resume(true) }
                    override fun onCancelled(gestureDescription:GestureDescription?) { if(continuation.isActive) continuation.resume(false) }
                },null)
                if(!accepted && continuation.isActive) continuation.resume(false)
            } }
            check(completed) { "لم تكتمل إيماءة القائمة؛ توقفت القراءة." }
            frame=await { keetaMenu.menu(it) }
            check(expectedQuantity==null || keetaBasket.menuQuantity(frame)==expectedQuantity) {
                "تغير عدد السلة أثناء القراءة؛ توقفت دون إضافة أو متابعة للدفع."
            }
        }
        error("لم تظهر ترويسة المطعم المختار كاملة؛ لم أغادر المطعم أو أغير السلة.")
    }

    private suspend fun openKeetaCoupon(previous: CatalogFrame, terms: Boolean) {
        currentCoroutineContext().ensureActive(); checkSession()
        val root = service.rootInActiveWindow ?: error("اختفى عرض كيتا.")
        try {
            check(provider == ProviderId.KEETA && root.packageName?.toString() == provider.packageName)
            val frame = capture(root)
            check(previous.complete)
            val target = (if (terms) KeetaPromotionEvidence.termsTarget(frame) else KeetaPromotionEvidence.couponTarget(frame, keetaMenu))
                ?: error("تعذر تحديد تفاصيل عرض كيتا.")
            check(performKeetaReadAction(root, target, AccessibilityNodeInfo.ACTION_CLICK)) { "تعذر فتح شروط العرض." }
        } finally { @Suppress("DEPRECATION") root.recycle() }
    }

    private suspend fun scrollKeetaHeader(previous: CatalogFrame): Boolean {
        currentCoroutineContext().ensureActive(); checkSession()
        val root = service.rootInActiveWindow ?: error("اختفت قائمة كيتا.")
        try {
            check(provider == ProviderId.KEETA && root.packageName?.toString() == provider.packageName)
            val frame = capture(root)
            check(previous.complete && keetaMenu.menu(frame))
            val target = frame.nodes.single { it.scrollable && it.className == "androidx.recyclerview.widget.RecyclerView" }
            return performKeetaReadAction(root, target, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        } finally { @Suppress("DEPRECATION") root.recycle() }
    }

    private fun locateKeetaNode(root: AccessibilityNodeInfo, target: CatalogNode): AccessibilityNodeInfo? {
        fun locate(current: AccessibilityNodeInfo, cursor: IntArray): AccessibilityNodeInfo? {
            if (!current.isVisibleToUser) return null
            val private = privateId(current.viewIdResourceName.orEmpty())
            if (current.isPassword || (android.os.Build.VERSION.SDK_INT >= 34 && current.isAccessibilityDataSensitive && !private)) return null
            val here = cursor[0]++
            if (here == target.index) return AccessibilityNodeInfo.obtain(current)
            val children = catalogChildren(current)
            try { for (child in children) locate(child, cursor)?.let { return it } }
            finally { children.forEach { @Suppress("DEPRECATION") it.recycle() } }
            return null
        }
        return locate(root,intArrayOf(0))
    }
    private fun performKeetaReadAction(root: AccessibilityNodeInfo, target: CatalogNode, action: Int): Boolean {
        require(action in setOf(AccessibilityNodeInfo.ACTION_CLICK, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
        checkSession()
        check(provider == ProviderId.KEETA && root.packageName?.toString() == provider.packageName)
        val expected = capture(root)
        check(expected.complete && expected.nodes.singleOrNull { it.index == target.index } == target)
        check(target.label !in setOf("إتمام الدفع", "تفريغ السلة", "تأكيد الطلب"))
        val node = locateKeetaNode(root,target) ?: error("تعذر تحديد عنصر القراءة في كيتا.")
        return try {
            check(node.refresh() && node.isVisibleToUser && node.isEnabled && !node.isEditable && !node.isPassword &&
                !(android.os.Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive) &&
                node.className?.toString() == target.className && node.viewIdResourceName.orEmpty() == target.id &&
                keetaMenu.functionalLabel((node.text ?: node.contentDescription)?.toString().orEmpty()) == target.label)
            check(if (action == AccessibilityNodeInfo.ACTION_CLICK) node.isClickable else node.isScrollable)
            val active = service.rootInActiveWindow ?: error("اختفت النافذة قبل الإجراء.")
            try { check(active.packageName?.toString() == provider.packageName && active.windowId == root.windowId && capture(active) == expected) }
            finally { @Suppress("DEPRECATION") active.recycle() }
            checkSession()
            node.performAction(action)
        } finally { @Suppress("DEPRECATION") node.recycle() }
    }

    /** Back from an evidenced basket preview only. No checkout control is selected. */
    private suspend fun closeKeetaReadSheet(previous: CatalogFrame) {
        currentCoroutineContext().ensureActive(); checkSession()
        val root = service.rootInActiveWindow ?: error("اختفت معاينة كيتا.")
        try {
            check(provider == ProviderId.KEETA && root.packageName?.toString() == provider.packageName)
            val frame = capture(root)
            check(previous.complete && (keetaMenu.basketDrawer(frame) || keetaMenu.product(frame) ||
                KeetaPromotionEvidence.couponSheet(frame) || KeetaPromotionEvidence.terms(frame) || KeetaPromotionEvidence.emptySheet(frame)))
            if (KeetaPromotionEvidence.couponSheet(frame) || KeetaPromotionEvidence.terms(frame) || KeetaPromotionEvidence.emptySheet(frame)) {
                val target = KeetaPromotionEvidence.closeTarget(frame) ?: error("تعذر تحديد زر إغلاق شروط العرض.")
                check(performKeetaReadAction(root, target, AccessibilityNodeInfo.ACTION_CLICK)) { "تعذر إغلاق شروط العرض." }
            } else if (keetaMenu.basketDrawer(frame)) {
                val target = keetaBasket.closeDrawer(frame) ?: error("تعذر تحديد إغلاق معاينة السلة.")
                check(performKeetaReadAction(root, target, AccessibilityNodeInfo.ACTION_CLICK)) { "تعذر إغلاق معاينة السلة." }
            } else {
                val target = keetaMenu.closeProduct(frame) ?: error("تعذر تحديد إغلاق الوجبة.")
                check(performKeetaReadAction(root, target, AccessibilityNodeInfo.ACTION_CLICK)) { "تعذر إغلاق معاينة الوجبة." }
            }
        } finally { @Suppress("DEPRECATION") root.recycle() }
    }

    private suspend fun openKeetaPreviewProduct(previous: CatalogFrame) {
        var frame = previous
        var previousBody: List<CatalogNode>? = null
        repeat(20) {
            currentCoroutineContext().ensureActive(); checkSession()
            val root = service.rootInActiveWindow ?: error("اختفت قائمة كيتا.")
            try {
                check(provider == ProviderId.KEETA && root.packageName?.toString() == provider.packageName)
                val fresh = capture(root)
                check(frame == fresh && fresh.complete) { "تغيرت القائمة؛ لم أفتح وجبة مختلفة." }
                val target = keetaMenu.productTarget(fresh)
                if (target != null) {
                    check(performKeetaReadAction(root, target, AccessibilityNodeInfo.ACTION_CLICK))
                    return
                }
                check(keetaMenu.menu(fresh) && fresh.nodes != previousBody) { "الوجبة المطابقة غير ظاهرة في قائمة كيتا." }
                previousBody = fresh.nodes
                val list = fresh.nodes.single { it.scrollable && it.className == "androidx.recyclerview.widget.RecyclerView" }
                check(performKeetaReadAction(root, list, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) { "وصلت إلى نهاية الجزء المقروء دون الوجبة المطلوبة." }
            } finally { @Suppress("DEPRECATION") root.recycle() }
            frame = await { keetaMenu.menu(it) }
        }
        error("لم أجد الوجبة المطابقة ضمن مهلة التصفح؛ لم أستبدلها.")
    }

    fun browseRestaurants(): Boolean = begin {
        phase = "فتح استعراض المطاعم"
        openHome()
        val home = await { HungerStationCatalog.browseEntry(it) != null }
        val entry = HungerStationCatalog.browseEntry(home)!!
        act(home, CatalogAction.OPEN_BROWSE, { it == entry })
        var listing = await { HungerStationCatalog.screen(it) in setOf(CatalogScreen.BROWSE, CatalogScreen.BROWSE_PROMO) }
        if (HungerStationCatalog.screen(listing) == CatalogScreen.BROWSE_PROMO) {
            act(listing, CatalogAction.CLOSE_BROWSE_PROMO, { it.id.endsWith(":id/close_button") && it.clickable })
            listing = await { HungerStationCatalog.screen(it) == CatalogScreen.BROWSE }
        }
        val restaurants = linkedMapOf<String, LiveRestaurant>()
        var previous = ""
        for (page in 0 until 10) {
            phase = "قراءة المطاعم المتاحة"
            val visible = HungerStationCatalog.browsedRestaurants(listing)
            visible.forEach { restaurants.putIfAbsent(it.name, it) }
            val fingerprint = visible.joinToString { it.name }
            if (fingerprint == previous && fingerprint.isNotEmpty()) break
            previous = fingerprint
            if (!act(listing, CatalogAction.SCROLL_RESTAURANTS,
                    { it.id.endsWith(":id/vendorsRecycler") && it.scrollable }, allowNoOp = true)) break
            listing = await { HungerStationCatalog.screen(it) == CatalogScreen.BROWSE }
        }
        check(restaurants.isNotEmpty()) { "تعذرت قراءة المطاعم؛ لم تُعرض قائمة تجريبية." }
        graph.catalog.value = graph.catalog.value.copy(menu = null, product = null, restaurants = restaurants.values.toList(),
            message = "${restaurants.size} مطعمًا من هنقرستيشن للعنوان النشط. هذه قائمة جزئية؛ اختر مطعمًا لقراءة قائمته أو حدّث الاستعراض.")
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
            phase = "إدخال اسم المطعم"
            setHungerQuery(query)
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
            graph.catalog.value = graph.catalog.value.copy(busy = true, restaurants = restaurants,
                menu = null, product = null, previews = emptyList(), preparedBasket = null,
                message = if (restaurants.isEmpty()) "لم تظهر مطاعم يمكن التحقق من أسمائها في النتائج المقروءة." else "مطاعم مقروءة من هنقرستيشن للعنوان النشط؛ لم تُقارن الأسعار بعد.")
        }
    }

    fun openMenu(restaurant: LiveRestaurant): Boolean {
        val started = begin {
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
        if (started) graph.catalog.value = graph.catalog.value.copy(selectedRestaurant = restaurant.name,
            previews = emptyList(), comparisonProgress = emptyMap(), preparedBasket = null)
        return started
    }

    private fun matchesSelectedRestaurant(): Boolean {
        if (ComparisonRequestSelection.matches(graph.catalog.value.selectedRestaurant, graph.draft.value)) return true
        graph.catalog.value = graph.catalog.value.copy(error = "اختر وجبة من المطعم المحدد وأضفها إلى طلبك أولًا؛ لم أشغّل مقارنة الطلب السابق.")
        return false
    }

    /** Opens and reads choices only. In particular it never presses flutterAddButton. */
    fun openProduct(restaurant: LiveRestaurant, requested: LiveMenuItem, returnToOctopus: Boolean = true): Boolean {
        val previousMenu = graph.catalog.value.menu
        return begin(returnOnSuccess = returnToOctopus, operation = CatalogOperation.READ_PRODUCT) {
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
            val details = readHungerProduct(restaurant, selected).details
            graph.catalog.value = graph.catalog.value.copy(menu = previousMenu, product = details,
                message = if (details.optionsComplete) "اختر خيارات الوجبة لحفظها في طلبك داخل الأخطبوط. لم تتغير سلة المنصة."
                    else "قراءة خيارات الوجبة جزئية أو غير مدعومة؛ لن تُضاف بخيارات ناقصة.")
        }
    }

    private suspend fun setHungerQuery(text: String) {
        for (attempt in 0 until 4) {
            val search = await { HungerStationCatalog.screen(it) == CatalogScreen.SEARCH }
            val input = HungerStationCatalog.searchInputs(search).single()
            try {
                act(search, CatalogAction.SET_QUERY, { it.index == input.index && it.editable }, text)
                return
            } catch (changed: ScreenChangedBeforeAction) {
                // Only retry a rejected, undispatched input. Never retry an add or quantity edit.
                if (attempt == 3) throw changed
                delay(200)
            }
        }
    }

    private suspend fun navigateToRestaurant(restaurant: LiveRestaurant): Boolean {
        phase = "فتح المطعم"
        // Re-run the known search when reopening, rather than assuming a stale screen position.
        openHome()
        click(CatalogScreen.HOME, CatalogAction.OPEN_SEARCH) { it.id.endsWith(":id/new_search_card_view") }
        setHungerQuery(restaurant.name)
        val suggestions = await { HungerStationCatalog.restaurantSuggestions(it).isNotEmpty() }
        val matching = HungerStationCatalog.restaurantSuggestions(suggestions).filter {
            it.label.substringBefore(',').trim() == restaurant.name }
        require(matching.isNotEmpty()) { "تعذرت مطابقة اسم المطعم في البحث." }
        val selectedSuggestion = matching.first()
        act(suggestions, CatalogAction.SELECT_SUGGESTION, { it.index == selectedSuggestion.index && it.label == selectedSuggestion.label })
        val results = await { HungerStationCatalog.screen(it) == CatalogScreen.RESULTS }
        val candidate = results.nodes.firstOrNull { it.id.endsWith(":id/title") && it.label == restaurant.name }
            ?: error("لم تظهر نتيجة مطابقة لاسم المطعم.")
        // Opening a candidate is read-only. This does not establish branch equivalence.
        act(results, CatalogAction.OPEN_RESTAURANT, { it.index == candidate.index && it.id == candidate.id && it.label == candidate.label })
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
    fun prepareRidan(details: ConfiguredProduct, selection: ProductSelection, desiredQuantity: Int = selection.quantity): Boolean {
        if (!RidanCartProgram.supports(details, selection, System.currentTimeMillis())) return false
        if (desiredQuantity !in 1..99 || graph.draft.value?.items?.singleOrNull()?.quantity != desiredQuantity) return false
        val desired = graph.draft.value ?: return false
        if (!PreviewEvidence.supports(desired) || desired.restaurant != details.restaurant ||
            desired.items.single().name != details.product.title) return false
        val priorMenu = graph.catalog.value.menu
        return begin(operation = CatalogOperation.BUILD_CART) {
            requestFingerprint = desired.fingerprint
            forgetPrepared(ProviderId.HUNGERSTATION)
            phase = "تجهيز سلة المطعم المختار"
            val restaurant = LiveRestaurant(details.restaurant, null, details.restaurant)
            check(!navigateToRestaurant(restaurant)) { "المطعم المختار غير متاح للطلب حاليًا." }
            var menu = await { HungerStationCatalog.screen(it) == CatalogScreen.MENU }
            val hasBasket = menu.nodes.any { it.id == "FlutterMenu_goToCartButton" }
            if (!hasBasket) {
                var item: CatalogNode? = null
                var previousMenuBody: String? = null
                for (page in 0 until 30) {
                    val matches = menu.nodes.filter { node -> HungerStationCatalog.menuItem(node)?.let {
                        it.name == details.product.title && it.selector == details.product.id } == true }
                    check(matches.size <= 1) { "الوجبة المختارة غير فريدة؛ لم تتغير السلة." }
                    if (matches.size == 1) { item = matches.single(); break }
                    val body = HungerStationCatalog.menuItems(menu).toString()
                    if (body == previousMenuBody) break
                    previousMenuBody = body
                    if (!act(menu, CatalogAction.SCROLL_MENU, { it.scrollable && it.className == "android.widget.ScrollView" }, allowNoOp = true)) break
                    menu = await { HungerStationCatalog.screen(it) == CatalogScreen.MENU }
                }
                val selected = item ?: error("الوجبة المختارة غير ظاهرة؛ لم تُستبدل بصنف آخر.")
                val program = RidanCartProgram.create(details, selection, menu, System.currentTimeMillis())
                    ?: error("تعذر إثبات سلة المطعم المختار الفارغة؛ لم تتغير السلال.")
                act(menu, CatalogAction.OPEN_PRODUCT, { it == selected })
                val read = readHungerProduct(restaurant, LiveMenuItem(details.product.title, details.product.listedPriceHalala, details.product.id))
                val product = read.lastFrame
                val current = read.details
                check(RidanCartProgram.supports(current, selection, System.currentTimeMillis())) { "تغيرت خيارات الوجبة؛ توقفت الإضافة." }
                val add = product.nodes.singleOrNull { it.id == "flutterAddButton" }
                    ?: error("لم يظهر زر إضافة واحد موثوق.")
                // Consume before dispatch. Timeout/failure must never repeat the add.
                act(product, CatalogAction.ADD_RIDAN, { it == add }, ridanProgram = program)
                menu = await { HungerStationCatalog.screen(it) == CatalogScreen.MENU && it.hasId("FlutterMenu_goToCartButton") }
            }
            val count = menu.nodes.singleOrNull { it.id == "FlutterMenu_goToCartQuantity" }?.label?.trim()
            val menuCount = count?.let { app.octopus.local.pricing.MoneyParser.parse(it)?.div(100)?.toInt() }
            check(menuCount in 1..99) { "تعذرت قراءة كمية السلة؛ لم أعدلها أو أكرر الإضافة." }
            val target = HungerStationBasketControls.openTarget(menu) ?: error("تعذر تحديد زر السلة الموثوق.")
            val command = BasketOpenCommand(menu, target, System.currentTimeMillis())
            act(menu, CatalogAction.OPEN_BASKET, { it == target }, basketCommand = command)
            var cart = await { HungerStationCatalog.screen(it) == CatalogScreen.CART }
            // The fixed footer's count is one, and exactly one fully parsed row must agree.
            var evidence = BasketEvidenceReader.hungerStation(cart, endOfCartObserved = false)
            check(evidence.restaurant == details.restaurant && evidence.lines.size == 1 &&
                cart.nodes.count { it.id == "FlutterCart_cartItem" } == 1 && evidence.lines.single().let {
                    it.name == details.product.title && it.quantity == menuCount && it.choices.isEmpty() }) {
                "لم تتطابق السلة المقروءة مع الوجبة؛ لا توجد إضافة أخرى أو تأكيد طلب."
            }
            var changes = 0
            while (evidence.lines.single().quantity != desiredQuantity) {
                check(++changes <= 98 && graph.draft.value?.fingerprint == desired.fingerprint)
                val before = evidence.lines.single().quantity!!
                val increase = before < desiredQuantity
                val command = RidanQuantityCommand(before, increase, System.currentTimeMillis(), details.restaurant, details.product.title)
                val targetQuantity = RidanQuantityCommand.target(cart, before, increase, details.restaurant, details.product.title) ?: error("تعذر تحديد زر كمية الوجبة؛ لم أعدله.")
                act(cart, CatalogAction.ADJUST_RIDAN_QUANTITY, { it == targetQuantity }, quantityCommand = command)
                val expected = before + if (increase) 1 else -1
                cart = await { HungerStationCatalog.screen(it) == CatalogScreen.CART &&
                    BasketEvidenceReader.hungerStation(it, false).lines.singleOrNull()?.quantity == expected }
                evidence = BasketEvidenceReader.hungerStation(cart, false)
                check(evidence.restaurant == details.restaurant && evidence.lines.singleOrNull()?.name == details.product.title &&
                    evidence.lines.single().choices.isEmpty()) { "تغيرت الوجبة؛ توقفت دون إعادة الإجراء." }
            }
            rememberPrepared(desired, evidence, HungerStationCatalog.TESTED_VERSION)
            graph.catalog.value = graph.catalog.value.copy(menu = priorMenu,
                message = "تم التحقق من سلة المطعم المختار: ${details.product.title}، الكمية $desiredQuantity. لم يُؤكد طلب أو دفع. سعر الأصناف ليس إجمالي التوصيل.",
                preparedBasket = evidence)
        }
    }

    fun openPreparedRidanBasket(): Boolean {
        if (!matchesSelectedRestaurant()) return false
        val draft = graph.draft.value ?: return false
        val saved = graph.catalog.value.preparedBaskets.forRequest(draft, ProviderId.HUNGERSTATION)?.evidence ?: return false
        if (!PreviewEvidence.supports(draft) || draft.restaurant != saved.restaurant ||
            draft.items.size != 1 || draft.items.single().let { it.name != saved.lines.singleOrNull()?.name || it.quantity !in 1..99 || it.options.isNotEmpty() }) return false
        return begin(returnOnSuccess = false, operation = CatalogOperation.OPEN_PREPARED_CART) {
            requestFingerprint = draft.fingerprint
            forgetPrepared(ProviderId.HUNGERSTATION)
            phase = "التحقق من سلة المطعم المختار قبل التسليم"
            check(!navigateToRestaurant(LiveRestaurant(draft.restaurant, null, draft.restaurant))) {
                "المطعم غير متاح حاليًا؛ لم يتغير طلبك."
            }
            val menu = await { HungerStationCatalog.screen(it) == CatalogScreen.MENU }
            check(menu.nodes.singleOrNull { it.id == "FlutterMenu_goToCartQuantity" }?.label?.let {
                app.octopus.local.pricing.MoneyParser.parse(it)?.div(100)?.toInt() } == draft.items.single().quantity) {
                "تغيرت السلة؛ لم تُعدّل ولم تُضف وجبة."
            }
            val target = HungerStationBasketControls.openTarget(menu) ?: error("تعذر فتح السلة بأمان.")
            act(menu, CatalogAction.OPEN_BASKET, { it == target }, basketCommand = BasketOpenCommand(menu, target, System.currentTimeMillis()))
            val cart = await { HungerStationCatalog.screen(it) == CatalogScreen.CART }
            val actual = BasketEvidenceReader.hungerStation(cart, false)
            check(actual.restaurant == saved.restaurant && actual.lines == saved.lines &&
                cart.nodes.count { it.id == "FlutterCart_cartItem" } == 1) { "تغيرت السلة منذ التجهيز؛ راجعها قبل المتابعة." }
            rememberPrepared(draft, actual, HungerStationCatalog.TESTED_VERSION)
            graph.catalog.value = graph.catalog.value.copy(preparedBasket = actual,
                message = "فُتحت سلة المطعم المختار وتحققت الوجبة والكمية مجددًا. التحكم الآن لك؛ لم يُؤكد طلب أو دفع.")
        }
    }

    private fun begin(returnOnSuccess: Boolean = true, operation: CatalogOperation = CatalogOperation.BROWSE,
        block: suspend () -> Unit): Boolean {
        if (active || graph.session.value.active) return false
        provider = ProviderId.HUNGERSTATION
        requestFingerprint = null
        if (!graph.settings.value.catalogConsent || provider !in graph.settings.value.allowed) return false
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
        val stopState = CatalogStopState().also { catalogStop = it }
        this.operation = operation
        priorRecognizedScreen = null
        lastStats = JSONObject()
        val evidenceGeneration = graph.inspectionRecorder.generation()
        graph.catalog.value = graph.catalog.value.copy(busy = true, error = null, menu = null, product = null, message = "جارٍ قراءة البيانات من هنقرستيشن…")
        job = graph.scope.launch {
            var outcome = "COMPLETED"
            try { withTimeout(150_000) { block() }; if (returnOnSuccess) returnToApp() }
            catch (cancelled: CancellationException) {
                outcome = if (cancelled is TimeoutCancellationException) "TIMEOUT" else "STOPPED"
                if (cancelled is TimeoutCancellationException) {
                    graph.catalog.value = graph.catalog.value.copy(error = "انتهت مهلة $phase. توقفت الإجراءات؛ راجع السلة إن بدأ تجهيزها.")
                    returnToApp()
                }
                throw cancelled
            } catch (failure: Exception) {
                outcome = if (failure is CatalogStoppedException) "FINANCIAL_STOP" else "FAILED"
                // Only our own fixed errors are surfaced; no provider exception text or screen dump is logged.
                graph.catalog.value = graph.catalog.value.copy(error = failure.message?.takeIf { it.any { c -> c in '\u0600'..'\u06ff' } }
                    ?: "توقفت العملية عند شاشة غير مدعومة؛ راجع السلة إن بدأ تجهيزها.")
                returnToApp()
            } finally {
                val frames = evidence.drain()
                val stopped = stopState.evidence
                // Freeze this session before suspension; another session must not replace its metadata.
                val stats = if (stopped == null) lastStats.toString() else catalogStopObservation(stopped).toString()
                // Capture has already stopped. Persist only bounded, redacted records, with no network operation.
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { graph.inspectionRecorder.recordAutomaticEvidence(ProviderId.HUNGERSTATION,
                        installed.version ?: "unknown", frames, outcome, evidenceGeneration, stopped) }
                }
                if (BuildConfig.DEBUG) withContext(NonCancellable + Dispatchers.IO) {
                    // Optional debug storage must never prevent releasing controls and clearing busy state.
                    runCatching { service.filesDir.resolve("catalog-frame-stats.json").writeText(stats, Charsets.UTF_8) }
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
        var frame = await { HungerStationCatalog.screen(it) in setOf(CatalogScreen.HOME, CatalogScreen.BROWSE) || CatalogCommands.backTarget(it) != null }
        repeat(6) {
            val screen = HungerStationCatalog.screen(frame)
            if (screen == CatalogScreen.HOME) return
            if (screen == CatalogScreen.BROWSE && CatalogCommands.backTarget(frame) == null) {
                act(frame, CatalogAction.SCROLL_RESTAURANTS, { it.id.endsWith(":id/vendorsRecycler") && it.scrollable })
                frame = await { HungerStationCatalog.screen(it) == CatalogScreen.BROWSE && CatalogCommands.backTarget(it) != null }
            }
            val target = CatalogCommands.backTarget(frame) ?: error("لا يوجد رجوع معروف من هذه الشاشة.")
            act(frame, if (screen == CatalogScreen.SPIN_PROMO) CatalogAction.CLOSE_SPIN_PROMO else CatalogAction.RETURN_TO_CATALOG,
                { it.index == target.index && it == target })
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
                    if (targetSeen) throw UserInterrupted("غادرت المنصة؛ توقفت العملية.")
                    delay(200); continue
                }
                val frame = capture(root)
                if (provider == ProviderId.HUNGERSTATION) {
                    val screen = HungerStationCatalog.screen(frame)
                    if (screen != CatalogScreen.UNKNOWN) priorRecognizedScreen = provider to screen
                }
                val structure = if (provider == ProviderId.HUNGERSTATION) inspectionReader.read(root, traceRequest) else null
                if (structure != null) sessionEvidence?.observe(SessionEvidence("AUTO_CATALOG_${HungerStationCatalog.screen(frame).name}",
                    structure.nodes, structure.count, structure.truncated, System.currentTimeMillis()), SystemClock.elapsedRealtime())
                lastStats = JSONObject().put("phase", phase).put("screen", HungerStationCatalog.screen(frame).name)
                    .put("complete", frame.complete).put("nodeCount", frame.nodes.size).put("sensitiveSkipped", sensitiveSkipped)
                    .put("keetaMenu", provider == ProviderId.KEETA && keetaMenu.menu(frame))
                    .put("keetaDrawer", provider == ProviderId.KEETA && keetaMenu.basketDrawer(frame))
                    .put("keetaBasketRead", provider == ProviderId.KEETA && keetaBasket.evidence(frame) != null)
                    .put("keetaDrawerClose", provider == ProviderId.KEETA && keetaBasket.closeDrawer(frame) != null)
                    .put("keetaProduct", provider == ProviderId.KEETA && keetaMenu.product(frame))
                    .put("keetaAddReady", provider == ProviderId.KEETA && keetaBasket.addTarget(frame, keetaMenu.unitPrice(frame) ?: -1) != null)
                    .put("enteredFields", frame.nodes.count { it.editable && it.enteredText })
                    .put("editables", frame.nodes.count { it.editable })
                    .put("scrollables", JSONArray(frame.nodes.filter { it.scrollable }.map { it.className + ":" + it.id }))
                    .put("ids", JSONArray(frame.nodes.map { it.id }.filter { it.isNotBlank() }.distinct()))
                if (predicate(frame)) {
                    val value = frame.nodes.joinToString { it.id + it.label + it.parent }
                    if (value != fingerprint) { fingerprint = value; since = SystemClock.elapsedRealtime() }
                    if (SystemClock.elapsedRealtime() - since >= 650) { targetSeen = true; return@withTimeout frame }
                } else { fingerprint = null }
            } finally { @Suppress("DEPRECATION") root.recycle() }
            delay(200)
        }
        @Suppress("UNREACHABLE_CODE") error("Unreachable")
    }

    private fun checkSession() {
        catalogStop.throwIfStopped()
        requestFingerprint?.let { if (graph.draft.value?.fingerprint != it)
            throw UserInterrupted("تغير طلبك؛ توقفت العملية قبل أي إجراء آخر.") }
        if (!graph.settings.value.catalogConsent || provider !in graph.settings.value.allowed)
            throw UserInterrupted("تغير السماح؛ توقفت العملية.")
        val lock = service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (lock.isKeyguardLocked) throw UserInterrupted("قُفل الجهاز؛ توقفت العملية.")
    }

    private fun capture(root: AccessibilityNodeInfo): CatalogFrame {
        val nodes = mutableListOf<CatalogNode>()
        var complete = true
        var firstDisabledFinancialControl: CatalogStopEvidence? = null
        sensitiveSkipped = 0
        fun walk(node: AccessibilityNodeInfo, parent: Int, depth: Int) {
            if (nodes.size >= 1800 || depth > 40) { complete = false; return }
            if (!node.isVisibleToUser) return
            val id = node.viewIdResourceName.orEmpty()
            // Personal home-header data is unnecessary for the catalog and never enters its state.
            val financial = CatalogFinancialBoundary.classify(id, node.className?.toString().orEmpty(),
                node.isClickable, node.isEditable, provider)
            if (financial == CatalogFinancialBoundary.Decision.STOP)
                catalogStop.stop(stopEvidence(node, CatalogStopReason.FINANCIAL_ELEMENT))
            val disabledFinancial = financial == CatalogFinancialBoundary.Decision.DISABLED_CONTROL
            if (disabledFinancial && firstDisabledFinancialControl == null)
                firstDisabledFinancialControl = stopEvidence(node, CatalogStopReason.FINANCIAL_CONTROL_ON_UNKNOWN_SCREEN)
            val private = privateId(id)
            if (node.isPassword) error("ظهرت شاشة كلمة مرور؛ توقفت القراءة.")
            if (android.os.Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive && !private) { sensitiveSkipped++; complete = false; return }
            val index = nodes.size
            val raw = if (private || node.isEditable || disabledFinancial) "" else (node.text ?: node.contentDescription)?.toString().orEmpty()
            if (raw.length > 1000) { complete = false; return }
            val label = if (provider == ProviderId.KEETA) keetaMenu.functionalLabel(raw) else raw
            ProviderAccessRestrictions.detect(raw)?.let { error(it.userMessage) }
            if (!private && !node.isEditable && !disabledFinancial) {
                ProviderAccessRestrictions.detect(node.contentDescription)?.let { error(it.userMessage) }
            }
            if (Regex("تسجيل الدخول|كلمة المرور|رمز التحقق|captcha|card number|verification code|password", RegexOption.IGNORE_CASE).containsMatchIn(raw))
                error("ظهرت شاشة دخول أو حماية؛ أكملها بنفسك داخل المنصة.")
            nodes += CatalogNode(index, parent, id, label, node.className?.toString().orEmpty(), node.isClickable && !disabledFinancial, node.isEditable, node.isScrollable, node.isEnabled && !disabledFinancial,
                omittedText = provider == ProviderId.KEETA && !private && !node.isEditable && raw.any(Char::isLetter) && label.isBlank(),
                enteredText = node.isEditable && !node.isShowingHintText && !node.text.isNullOrBlank() &&
                    node.text?.toString() != node.hintText?.toString())
            // catalogChildren applies the same privacy boundary during capture and target lookup.
            val children = catalogChildren(node)
            try { for (child in children) walk(child, index, depth + 1) }
            finally { children.forEach { @Suppress("DEPRECATION") it.recycle() } }
        }
        walk(root, -1, 0)
        val frame = CatalogFrame(nodes, complete)
        firstDisabledFinancialControl?.let {
            if (!CatalogFinancialBoundary.permitsDisabledControls(frame, provider)) catalogStop.stop(it)
        }
        return frame
    }

    /** Structural metadata only. Never inspect text, content descriptions, values or descendants here. */
    private fun stopEvidence(node: AccessibilityNodeInfo, reason: CatalogStopReason) = CatalogStopEvidence.create(
        provider = provider,
        providerVersion = graph.installations.value.singleOrNull { it.provider == provider }?.version,
        capturedAtMillis = System.currentTimeMillis(),
        priorRecognizedScreen = priorRecognizedScreen?.takeIf { it.first == provider }?.second,
        reason = reason, resourceId = node.viewIdResourceName, className = node.className?.toString(),
        clickable = node.isClickable, editable = node.isEditable, password = node.isPassword,
        dataSensitive = android.os.Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive,
        appVersion = BuildConfig.VERSION_NAME, appVersionCode = BuildConfig.VERSION_CODE.toLong(),
        operation = operation, requestFingerprint = requestFingerprint,
    )

    private suspend fun readHungerProduct(restaurant: LiveRestaurant, item: LiveMenuItem): ProductReadResult {
        val pages = JSONArray()
        try {
            return HungerStationProductReadSession().read(restaurant, item,
                frame = { await { HungerStationCatalog.screen(it) == CatalogScreen.PRODUCT }.also { current ->
                    if (BuildConfig.VERSION_NAME.endsWith("-device-tools") && current.nodes.none { it.editable } && pages.length() < 30) {
                        pages.put(JSONObject().put("complete", current.complete).put("nodes", JSONArray(current.nodes.map { n ->
                            JSONObject().put("index", n.index).put("parent", n.parent).put("id", n.id).put("label", n.label)
                                .put("className", n.className).put("clickable", n.clickable).put("enabled", n.enabled)
                                .put("scrollable", n.scrollable).put("editable", false)
                        })))
                    }
                } }, scroll = { scrollProduct(it) })
        } finally {
            if (pages.length() > 0) withContext(Dispatchers.IO) {
                runCatching { service.filesDir.resolve("live-hs-public-product.json").writeText(pages.toString(), Charsets.UTF_8) }
            }
        }
    }

    private suspend fun scrollProduct(frame: CatalogFrame): Boolean {
        require(HungerStationCatalog.screen(frame) == CatalogScreen.PRODUCT)
        val controls = frame.nodes.filter { it.scrollable && it.className == "android.widget.ScrollView" }
        if (controls.isEmpty()) return false
        val control = controls.singleOrNull() ?: error("تعذر تحديد منطقة خيارات واحدة للقراءة.")
        return act(frame, CatalogAction.SCROLL_PRODUCT, { it == control }, allowNoOp = true)
    }

    private suspend fun click(screen: CatalogScreen, action: CatalogAction, selector: (CatalogNode) -> Boolean) =
        act(await { HungerStationCatalog.screen(it) == screen }, action, selector)

    private suspend fun act(previous: CatalogFrame, action: CatalogAction, selector: (CatalogNode) -> Boolean,
        text: String? = null, allowNoOp: Boolean = false, ridanProgram: RidanCartProgram? = null,
        basketCommand: BasketOpenCommand? = null, quantityCommand: RidanQuantityCommand? = null): Boolean {
        currentCoroutineContext().ensureActive(); checkSession()
        val root = service.rootInActiveWindow ?: error("اختفت الشاشة قبل الإجراء.")
        try {
            check(root.packageName?.toString() == provider.packageName) { "غادرت المنصة؛ توقفت العملية." }
            val fresh = capture(root)
            if (fresh != previous) throw ScreenChangedBeforeAction()
            val selected = fresh.nodes.filter(selector)
            require(selected.size == 1) { "تعذر تحديد عنصر فريد؛ توقفت العملية." }
            val node = selected.single()
            check(node.enabled) { "العنصر غير متاح؛ توقفت العملية." }
            val allowed = when (action) {
                CatalogAction.CLOSE_SPIN_PROMO -> SafetyGuard().allowsSpinPromoClose(fresh, node,
                    root.packageName?.toString(), graph.installations.value.singleOrNull { it.provider == provider }?.version,
                    graph.settings.value.catalogConsent, currentCoroutineContext().isActive)
                CatalogAction.ADD_RIDAN -> graph.settings.value.catalogConsent &&
                    ridanProgram?.consumeAdd(fresh, node, System.currentTimeMillis()) == true
                CatalogAction.ADJUST_RIDAN_QUANTITY -> graph.settings.value.catalogConsent &&
                    quantityCommand?.consume(fresh, node, System.currentTimeMillis()) == true
                CatalogAction.OPEN_BASKET -> basketCommand != null && SafetyGuard().allowsBasketOpen(basketCommand,
                    fresh, root.packageName?.toString(), HungerStationCatalog.TESTED_VERSION,
                    graph.settings.value.catalogConsent, currentCoroutineContext().isActive) &&
                    basketCommand.consume(fresh, HungerStationCatalog.TESTED_VERSION, true, System.currentTimeMillis())
                else -> SafetyGuard().allowsCatalogAction(action, HungerStationCatalog.screen(fresh), root.packageName?.toString(),
                    graph.settings.value.catalogConsent, currentCoroutineContext().isActive, fresh.complete, node.label)
            }
            check(allowed) { "الإجراء خارج نطاق العملية المسموحة." }
            val operation = action.operation()
            val actionTarget = if (operation == ElementOperation.CLICK)
                CatalogTargetGuard.clickTarget(fresh, node, action, provider)
                    ?: error("لم أتعرف على عنصر نقر آمن؛ توقفت العملية.")
            else node
            fun locate(current: AccessibilityNodeInfo, cursor: IntArray): AccessibilityNodeInfo? {
                if (!current.isVisibleToUser) return null
                val private = privateId(current.viewIdResourceName.orEmpty())
                if (current.isPassword || (android.os.Build.VERSION.SDK_INT >= 34 && current.isAccessibilityDataSensitive && !private)) return null
                val here = cursor[0]++
                if (here == actionTarget.index) return AccessibilityNodeInfo.obtain(current)
                val children = catalogChildren(current)
                try { for (child in children) locate(child, cursor)?.let { return it } }
                finally { children.forEach { @Suppress("DEPRECATION") it.recycle() } }
                return null
            }
            var target = locate(root, intArrayOf(0)) ?: error("العنصر لم يعد متاحًا.")
            fun verifyTarget(actual: AccessibilityNodeInfo, expected: CatalogNode) {
                check(actual.refresh() && actual.isVisibleToUser && actual.isEnabled && !actual.isPassword &&
                    actual.windowId == root.windowId && actual.packageName?.toString() == provider.packageName &&
                    (android.os.Build.VERSION.SDK_INT < 34 || !actual.isAccessibilityDataSensitive)) {
                    "تغير العنصر قبل الإجراء؛ توقفت العملية."
                }
                val id = actual.viewIdResourceName.orEmpty()
                val className = actual.className?.toString().orEmpty()
                check(CatalogFinancialBoundary.classify(id, className, actual.isClickable, actual.isEditable,
                    provider) == CatalogFinancialBoundary.Decision.NORMAL) { "العنصر المالي غير مسموح." }
                val label = if (privateId(id) || actual.isEditable) ""
                    else (actual.text ?: actual.contentDescription)?.toString().orEmpty()
                val observed = expected.copy(id = id, label = label, className = className,
                    clickable = actual.isClickable, editable = actual.isEditable, scrollable = actual.isScrollable,
                    enabled = actual.isEnabled)
                check(CatalogTargetGuard.matches(expected, observed, provider)) {
                    "لم يعد العنصر يطابق الاختيار؛ توقفت العملية."
                }
            }
            var targetIdentity = actionTarget
            try {
                verifyTarget(target, targetIdentity)
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
                            targetIdentity = actionTarget.copy(id = "", label = "", className = "android.widget.EditText",
                                clickable = editor.isClickable, editable = true, scrollable = editor.isScrollable)
                            target.recycle()
                            target = actualEditor
                        }
                    } finally { editors.forEach { it.recycle() } }
                }
                val androidAction = when (operation) {
                    ElementOperation.SET_TEXT -> AccessibilityNodeInfo.ACTION_SET_TEXT
                    ElementOperation.SCROLL_FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    ElementOperation.CLICK -> AccessibilityNodeInfo.ACTION_CLICK
                }
                verifyTarget(target, targetIdentity)
                check(when (operation) {
                    ElementOperation.CLICK -> target.isClickable && !target.isEditable
                    ElementOperation.SET_TEXT -> target.isEditable && text != null
                    ElementOperation.SCROLL_FORWARD -> target.isScrollable && !target.isEditable
                }) { "العنصر لا يدعم الإجراء المطلوب." }
                val active = service.rootInActiveWindow ?: error("اختفت الشاشة قبل الإجراء.")
                try {
                    check(active.packageName?.toString() == provider.packageName &&
                        active.windowId == root.windowId && capture(active) == fresh) {
                        "تغيرت الشاشة قبل الإجراء؛ توقفت العملية."
                    }
                } finally { @Suppress("DEPRECATION") active.recycle() }
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
    /** The catalog back button shares an address container. Only that known grandchild is exposed. */
    private fun catalogChildren(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val id = node.viewIdResourceName.orEmpty()
        if (!CatalogFinancialBoundary.permitsChildren(id, node.className?.toString().orEmpty(),
                node.isClickable, node.isEditable, provider)) return emptyList()
        if (privateId(id)) {
            if (provider != ProviderId.HUNGERSTATION || !id.endsWith(":id/change_location_header_v2")) return emptyList()
            val controls = mutableListOf<AccessibilityNodeInfo>()
            for (i in 0 until node.childCount.coerceAtMost(30)) node.getChild(i)?.let { container ->
                try {
                    if (container.viewIdResourceName?.endsWith(":id/icon_container") == true)
                        for (j in 0 until container.childCount.coerceAtMost(10)) container.getChild(j)?.let { child ->
                            if (child.viewIdResourceName?.endsWith(":id/hsBackButton") == true) controls += child
                            else @Suppress("DEPRECATION") child.recycle()
                        }
                } finally { @Suppress("DEPRECATION") container.recycle() }
            }
            return controls
        }
        return (0 until node.childCount.coerceAtMost(300)).mapNotNull { node.getChild(it) }
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
