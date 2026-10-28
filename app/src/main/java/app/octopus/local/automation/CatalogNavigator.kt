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
import android.widget.LinearLayout
import android.view.View
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
import app.octopus.local.safety.ReviewNavigationGuard
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
    private var multiReadStage = "IDLE"
    private val observedMenuTitles=linkedMapOf<ProviderId,LinkedHashSet<String>>()
    private var lastKeetaFoodProduct:CatalogFrame?=null
    private var requestFingerprint: String? = null
    private var requestAreaUrl: String? = null
    private var keetaContext: KeetaMenuReader? = null
    private val keetaMenu get() = checkNotNull(keetaContext) { "لا يوجد طلب محدد لقراءة كيتا." }
    private var keetaBasketTitles: Set<String> = emptySet()
    private val keetaBasket get() = KeetaBasketReader(keetaMenu, keetaBasketTitles)
    private fun installedKeetaProfile() = KeetaVersionProfile.resolve(
        graph.installations.value.singleOrNull { it.provider == ProviderId.KEETA }?.version)
    private fun bindKeetaRequest(cart: CanonicalCart, line: CanonicalItem = cart.items.single()) {
        val profile = checkNotNull(installedKeetaProfile()) { "إصدار كيتا غير مدعوم في مسار القراءة الحالي." }
        check(profile.allowsReading()) { "إصدار كيتا غير مدعوم في مسار القراءة الحالي." }
        require(PlainMenuReadPlan.create(cart, ProviderId.KEETA, profile.version)?.lines?.contains(line) == true)
        requestFingerprint = cart.fingerprint
        keetaBasketTitles = cart.items.map { it.name }.toSet()
        keetaContext = KeetaMenuReader(cart.restaurant, line.name, profile.version)
    }
    private var job: Job? = null
    private var comparisonJob: Job? = null
    private var overlay: View? = null
    private var manualJob: Job? = null
    private val manualCollector = ManualCatalogCollector()
    private var manualSummary = ManualCaptureSummary()
    private var comparisonEpoch = 0L
    private var stepInterrupted: String? = null
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
    val active: Boolean get() = job?.isActive == true || manualJob?.isActive == true || comparisonJob?.isActive == true
    private val movementAllowed: Boolean get() = manualJob?.isActive != true &&
        (comparisonJob?.isActive == true || (job?.isActive == true && operation in setOf(CatalogOperation.OPEN_PREPARED_CART, CatalogOperation.COMPARE_MENU)))

    private fun requireProviderWindow(root: AccessibilityNodeInfo) {
        if (root.packageName?.toString() != provider.packageName)
            throw UserInterrupted("غادرت المنصة أو ظهرت نافذة أخرى؛ توقفت المقارنة.")
    }

    /** A lost foreground is a whole-session interruption, not a provider availability failure. */
    private fun verifyActiveFrame(expected: CatalogFrame, expectedWindowId: Int) {
        val current = service.rootInActiveWindow ?: throw UserInterrupted("اختفت النافذة قبل الإجراء؛ توقفت المقارنة.")
        try {
            requireProviderWindow(current)
            if (current.windowId != expectedWindowId || capture(current) != expected) throw ScreenChangedBeforeAction()
        } finally { @Suppress("DEPRECATION") current.recycle() }
    }

    private fun forgetPrepared(id: ProviderId) {
        graph.catalog.value = graph.catalog.value.copy(preparedBasket = null,
            preparedBaskets = graph.catalog.value.preparedBaskets.invalidate(id))
    }

    private suspend fun rememberPrepared(desired: CanonicalCart, evidence: BasketEvidence, version: String) {
        checkSession()
        check(graph.draft.value?.fingerprint == desired.fingerprint) { "تغير طلبك؛ لم أحتفظ بسلة لطلب سابق." }
        val record = PreparedBasketRecord.create(desired, evidence, version, System.currentTimeMillis())
            ?: error("السلة المقروءة لا تطابق الطلب؛ لا توجد سلة جاهزة للتسليم.")
        observeBasketMutation(desired, evidence)
        graph.catalog.value = graph.catalog.value.copy(preparedBasket = evidence,
            preparedBaskets = graph.catalog.value.preparedBaskets.record(record))
    }

    private suspend fun observeBasketMutation(desired: CanonicalCart, evidence: BasketEvidence) {
        withContext(Dispatchers.IO) { graph.basketMutationJournal.observe(desired, evidence) }
    }

    private suspend fun requireFirstBasketAdd(desired: CanonicalCart) {
        withContext(Dispatchers.IO) { graph.basketMutationJournal.requireFirstAdd(provider, desired) }
    }

    private suspend fun recordBasketMutation() {
        val desired = graph.draft.value ?: error("اختفى الطلب؛ لم أعدل السلة.")
        check(desired.fingerprint == requestFingerprint) { "تغير الطلب قبل تعديل السلة." }
        withContext(Dispatchers.IO) { graph.basketMutationJournal.beforeDispatch(provider, desired) }
    }

    fun stop(message: String = "أوقفت العملية. راجع السلة إن بدأ تجهيزها.") {
        comparisonEpoch++
        comparisonJob?.cancel(); comparisonJob = null
        manualJob?.cancel(); manualJob = null
        manualCollector.reset()
        generation++
        sessionEvidence = null
        job?.cancel(); job = null
        hideStop()
        graph.catalog.value = graph.catalog.value.copy(busy = false, comparisonRunning = false, manualCaptureActive = false, message = message)
    }

    /** Explicit manual import. Reading is passive: this path has no node action or gesture. */
    fun beginManualCatalog(): Boolean {
        if (active || graph.session.value.active || !graph.catalogReady.value) return false
        provider = ProviderId.HUNGERSTATION
        graph.refreshInstallations()
        if (!graph.settings.value.catalogConsent || provider !in graph.settings.value.allowed ||
            graph.installations.value.none { it.provider == provider && it.installed && it.version == HungerStationCatalog.TESTED_VERSION }) return false
        val launch = service.packageManager.getLaunchIntentForPackage(provider.packageName) ?: return false
        graph.publicCatalog.leaveMenu()
        graph.publicMealSelection.value = null
        manualCollector.reset()
        manualSummary = ManualCaptureSummary()
        catalogStop = CatalogStopState()
        operation = CatalogOperation.BROWSE
        priorRecognizedScreen = null
        requestFingerprint = null
        targetSeen = false
        if (!showManualControls()) return false
        graph.catalog.value = graph.catalog.value.copy(manualCaptureActive = true, error = null,
            message = "تصفح هنقرستيشن بنفسك، ثم اضغط «احفظ وارجع للأخطبوط». لا توجد نقرات أو تمرير تلقائي.")
        val token = ++generation
        manualJob = graph.scope.launch(start = CoroutineStart.LAZY) {
            val started = SystemClock.elapsedRealtime()
            try {
                while (isActive) {
                    val lock = service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                    if (lock.isKeyguardLocked || !graph.settings.value.catalogConsent || provider !in graph.settings.value.allowed) break
                    val root = service.rootInActiveWindow
                    if (root != null) try {
                        val currentPackage = root.packageName?.toString()
                        if (currentPackage == provider.packageName) {
                            targetSeen = true
                            saveManualFrame(root)
                        } else if (targetSeen && currentPackage != service.packageName) break
                    } finally { @Suppress("DEPRECATION") root.recycle() }
                    if ((!targetSeen && SystemClock.elapsedRealtime() - started > 10_000) ||
                        SystemClock.elapsedRealtime() - started > 30 * 60_000) break
                    delay(650)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                graph.catalog.value = graph.catalog.value.copy(error = "تعذرت قراءة الشاشة الحالية بأمان؛ بقيت القوائم المحفوظة متاحة.")
            } finally {
                // No timeout or interruption is allowed to pull the user back into Octopus.
                if (generation == token && manualJob === currentCoroutineContext()[Job] && graph.catalog.value.manualCaptureActive) {
                    graph.catalog.value = graph.catalog.value.copy(manualCaptureActive = false,
                        message = manualSummary.message)
                    hideStop()
                }
            }
        }
        return try {
            service.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            manualJob?.start()
            true
        } catch (_: Exception) { endManualCatalog(); false }
    }

    private suspend fun saveManualFrame(root: AccessibilityNodeInfo) {
        val token = generation
        val library = graph.catalogLibrary.value
        val update = manualCollector.observe(capture(root), System.currentTimeMillis(),
            library.restaurants.map { it.restaurant }, library.menus.map { it.menu })
        if (update.hasData) graph.saveCatalogObservation(update) { generation == token && graph.catalog.value.manualCaptureActive }
        if (generation == token) manualSummary.record(update)
        if (generation == token) update.reason?.let { graph.catalog.value = graph.catalog.value.copy(message = it) }
    }

    fun endManualCatalog() {
        generation++
        manualJob?.cancel(); manualJob = null
        manualCollector.reset()
        hideStop()
        graph.catalog.value = graph.catalog.value.copy(manualCaptureActive = false, message = manualSummary.message)
    }

    fun captureManualCatalog(): Boolean {
        if (!graph.catalog.value.manualCaptureActive) return false
        val polling = manualJob
        val token = generation
        manualJob = graph.scope.launch(start = CoroutineStart.LAZY) {
            polling?.cancelAndJoin()
            if (generation != token) return@launch
            try {
                val root = service.rootInActiveWindow
                if (root != null) try {
                    if (root.packageName?.toString() == provider.packageName) saveManualFrame(root)
                } finally { @Suppress("DEPRECATION") root.recycle() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                graph.catalog.value = graph.catalog.value.copy(error = "حُفظت القراءات السابقة؛ تعذرت قراءة الشاشة الأخيرة.")
            } finally {
                if (generation == token) {
                    // Only this explicit overlay tap returns the user to Octopus.
                    endManualCatalog()
                    service.startActivity(Intent(service, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
            }
        }
        manualJob?.start()
        return true
    }

    fun compareAndPrepare(): Boolean {
        val desired = graph.draft.value ?: return false
        if (!matchesSelectedRestaurant()) return false
        if (active || graph.catalog.value.busy || comparisonJob?.isActive == true || graph.session.value.active) {
            graph.catalog.value = graph.catalog.value.copy(error = "توجد عملية نشطة؛ أوقفها قبل بدء مقارنة جديدة.")
            return false
        }
        graph.refreshInstallations()
        val preflightFailure = ComparisonPreflight.failure(desired, graph.settings.value.catalogConsent,
            graph.settings.value.allowed, graph.installations.value)
        if (preflightFailure != null) {
            graph.catalog.value = graph.catalog.value.copy(error = preflightFailure)
            return false
        }
        graph.catalog.value = graph.catalog.value.copy(comparisonRunning = true, comparisonProgress = emptyMap(), finalTotals = emptyMap(), error = null)
        val comparisonAreaUrl = graph.settings.value.catalogArea?.url
        val epoch = ++comparisonEpoch
        comparisonJob = graph.scope.launch(start = CoroutineStart.LAZY) {
            try {
                CartComparisonFlow().run(
                    readMenus = {
                        check(compareDraft()) { graph.catalog.value.error ?: "تعذر بدء قراءة أسعار الوجبات." }
                        job?.join()
                        currentCoroutineContext().ensureActive()
                        stepInterrupted?.let { throw CancellationException(it) }
                    },
                    available = { id -> graph.catalog.value.previews.any { it.provider == id && it.itemSubtotal != null && it.fresh(desired, System.currentTimeMillis()) } },
                    prepare = { id ->
                        val started = if(id == ProviderId.HUNGERSTATION ||
                            installedKeetaProfile()?.allowsOwnedPlainPreparation == true)
                            begin(operation=CatalogOperation.BUILD_CART,timeoutMillis=600_000) {
                                val progress=PlainPreparationProgress()
                                if(id==ProviderId.HUNGERSTATION) prepareOwnedHungerSteps(desired,true,progress)
                                else prepareOwnedKeetaSteps(desired,true,progress)
                            }
                        else if (id == ProviderId.HUNGERSTATION) prepareComparedHungerStation() else prepareComparedKeeta()
                        if (!started) "تعذر بدء تجهيز السلة من القراءة الحالية." else {
                            job?.join()
                            currentCoroutineContext().ensureActive()
                            stepInterrupted?.let { throw CancellationException(it) }
                            graph.catalog.value.error ?: if (graph.catalog.value.preparedBaskets.forRequest(desired, id) == null)
                                "لم يكتمل التحقق من السلة." else null
                        }
                    },
                    unchanged = { graph.draft.value?.fingerprint == desired.fingerprint &&
                        graph.settings.value.catalogArea?.url == comparisonAreaUrl },
                    progress = { value -> graph.catalog.value = graph.catalog.value.copy(
                        comparisonProgress = graph.catalog.value.comparisonProgress + (value.provider to value)) },
                    totalAvailable = { id -> graph.catalog.value.finalTotals[id]?.fresh(desired, System.currentTimeMillis()) == true },
                )
                val count = graph.catalog.value.finalTotals.values.count { it.fresh(desired, System.currentTimeMillis()) }
                graph.catalog.value = graph.catalog.value.copy(message = "انتهت محاولة تجهيز السلتين؛ عدد إجماليات المراجعة المقروءة حاليًا: $count. راجع حالة كل منصة وتطابق العنوان والفرع.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: IllegalStateException) {
                graph.catalog.value = graph.catalog.value.copy(error = failure.message)
            } finally {
                if (comparisonEpoch == epoch) {
                    graph.catalog.value = graph.catalog.value.copy(comparisonRunning = false)
                    comparisonJob = null
                    if (stepInterrupted == null) returnToApp()
                }
            }
        }
        comparisonJob?.start()
        return true
    }

    fun compareDraft(): Boolean {
        val desired = graph.draft.value ?: return false
        if (!matchesSelectedRestaurant()) return false
        if (!PreviewEvidence.supports(desired)) {
            if (desired.items.size > 1 && PlainMenuReadPlan.create(desired, ProviderId.HUNGERSTATION, "admission") != null)
                return compareMultiplePlainMenuPrices(desired)
            graph.catalog.value = graph.catalog.value.copy(error = "المقارنة الحالية تحتاج صنفًا واحدًا دون خيارات أو ملاحظات؛ احتُفظ بطلبك دون حساب سعر ناقص.")
            return false
        }
        return begin(operation = CatalogOperation.COMPARE_MENU) {
            requestFingerprint = desired.fingerprint
            comparedProduct = null
            graph.catalog.value = graph.catalog.value.copy(previews = emptyList())
            val hunger = MenuReadTimeoutBoundary.read(onTimeout = {
                failedMenuPreview(ProviderId.HUNGERSTATION, desired,
                    "انتهت مهلة قراءة الوجبة في هنقرستيشن؛ لم أغير السلة وسأكمل قراءة كيتا.")
            }) { try {
                phase = "قراءة سعر هنقرستيشن وعروضه"
                val restaurant = LiveRestaurant(desired.restaurant, null, desired.restaurant)
                check(!navigateToRestaurant(restaurant)) { "المطعم غير متاح للطلب في القراءة الحالية." }
                phase = "قراءة الوجبة في هنقرستيشن"
                val menu = await { HungerStationCatalog.screen(it) == CatalogScreen.MENU }
                val offerLabels = PreviewEvidence.offers(menu)
                // Single- and multi-item comparisons use the same observed native menu
                // search. The old single-item scroll-only path missed off-screen products.
                val opened = openHungerPlainProduct(menu, desired, desired.items.single())
                phase = "قراءة خيارات هنقرستيشن"
                val read = readHungerProduct(restaurant, opened.item)
                val details = read.details
                graph.catalog.value = graph.catalog.value.copy(product = details)
                check(details.optionsComplete) { "لم تكتمل قراءة خيارات الوجبة في هنقرستيشن؛ لم أغير السلة." }
                check(details.product.available) { "زر إضافة الوجبة في هنقرستيشن غير متاح في القراءة الحالية؛ لم أغير السلة." }
                check(details.product.listedPriceHalala != null) { "سعر الوجبة في قائمة هنقرستيشن غير مقروء؛ لم أغير السلة." }
                check(MenuSelectionValidator().selectConfigured(details,
                    ProductSelection(details.product.id, emptyMap(), 1), System.currentTimeMillis()) is SelectionResult.Accepted) {
                    "تحتاج الوجبة خيارات إلزامية؛ لم أستخدم سعرًا لطلب ناقص."
                }
                comparedProduct = details
                if (opened.search != null) {
                    act(read.lastFrame, CatalogAction.RETURN_TO_CATALOG, { it.id == "pdpCloseButton" })
                    closeHungerMenuSearch(opened.search, desired)
                }
                MenuCostPreview(ProviderId.HUNGERSTATION, desired.fingerprint,
                    Math.multiplyExact(details.product.listedPriceHalala!!, desired.items.single().quantity.toLong()), offerLabels, System.currentTimeMillis(), HungerStationCatalog.TESTED_VERSION,
                    deliveryBeforeOffers = PreviewEvidence.hungerComponent(menu, "رسوم التوصيل"),
                    minimumOrder = PreviewEvidence.hungerComponent(menu, "الحد الأدنى للطلب"),
                    rules = MenuOfferRules.fromFrame(ProviderId.HUNGERSTATION, menu))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (stopped: CatalogStoppedException) { throw stopped }
            catch (interrupted: UserInterrupted) { throw interrupted }
            catch (changed: ScreenChangedBeforeAction) { throw changed }
            catch (failure: Exception) {
                failedMenuPreview(ProviderId.HUNGERSTATION, desired,
                    menuFailure(failure, "تعذرت قراءة الوجبة وخياراتها في هنقرستيشن أثناء: $phase؛ سأكمل قراءة كيتا."))
            }
            }
            graph.catalog.value = graph.catalog.value.copy(previews = listOf(hunger), message = "انتهت محاولة هنقرستيشن؛ جارٍ قراءة كيتا دون تغيير السلال…")
            val keeta = MenuReadTimeoutBoundary.read(onTimeout = {
                failedMenuPreview(ProviderId.KEETA, desired,
                    "انتهت مهلة قراءة الوجبة في كيتا؛ لم أغير السلة واحتُفظ بنتيجة هنقرستيشن المتاحة.")
            }) { try {
                bindKeetaRequest(desired)
                // An unsupported installation is a preflight failure, never a coupon-read fallback.
                provider = ProviderId.KEETA
                checkSession()
                check(graph.installations.value.any { it.provider == provider && it.version == keetaMenu.VERSION }) {
                    "إصدار كيتا يحتاج تحققًا قبل قراءة المقارنة."
                }
                if (keetaMenu.profile.usesCurrentLayout) readCurrentKeetaProduct(desired) else try {
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
                        frame = awaitKeetaProduct()
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
            catch (failure: Exception) {
                failedMenuPreview(ProviderId.KEETA, desired,
                    menuFailure(failure, "تعذرت قراءة الوجبة المطابقة في كيتا أثناء: $phase؛ احتُفظ بنتيجة هنقرستيشن المتاحة."))
            }
            }
            check(graph.draft.value?.fingerprint == desired.fingerprint) { "تغير طلبك؛ أعد المقارنة." }
            graph.catalog.value = graph.catalog.value.copy(previews = listOf(hunger, keeta),
                message = if (listOf(hunger, keeta).all { it.itemSubtotal != null })
                    "قرأت أسعار الوجبات في التطبيقين. الإجماليات والرسوم المشروطة لم تُتحقق بعد."
                else "انتهت محاولة القراءة؛ تظهر نتيجة كل منصة وسبب تعذرها. لا يوجد إجمالي نهائي مؤكد.")
        }
    }

    /** Price-reading only. No add, quantity, cart-open, promotion or review action exists in this path. */
    private fun compareMultiplePlainMenuPrices(desired: CanonicalCart): Boolean =
        begin(operation = CatalogOperation.COMPARE_MENU,
            timeoutMillis=(30_000L + desired.items.size * 90_000L).coerceAtMost(600_000L)) {
            requestFingerprint = desired.fingerprint
            comparedProduct = null
            graph.catalog.value = graph.catalog.value.copy(previews=emptyList(),finalTotals=emptyMap(),preparedBasket=null)
            val previews = mutableListOf<MenuCostPreview>()
            for (id in ProviderId.entries) {
                provider = id
                targetSeen = false
                val version = graph.installations.value.singleOrNull { it.provider == id }?.version.orEmpty()
                val preview = MenuReadTimeoutBoundary.read(onTimeout = {
                    failedMenuPreview(id,desired,"انتهت مهلة قراءة أصناف ${id.title}؛ لم أحتسب سعرًا ناقصًا ولم أغيّر السلة.")
                }) { try {
                    checkSession()
                    check((id == ProviderId.HUNGERSTATION && version == HungerStationCatalog.TESTED_VERSION) ||
                        (id == ProviderId.KEETA && KeetaVersionProfile.resolve(version)?.allowsReading(multipleItems=true)==true)) { "إصدار المنصة يحتاج تحققًا لقراءة جميع الأصناف." }
                    val plan = PlainMenuReadPlan.create(desired,id,version) ?: error("الطلب يحتاج مسار قراءة غير مدعوم.")
                    for (line in plan.lines) {
                        phase = "قراءة جميع أصناف الطلب في ${id.title}"
                        val unit = if (id == ProviderId.HUNGERSTATION) readHungerPlainLine(desired,line)
                            else readKeetaPlainLine(desired,line)
                        @Suppress("DEPRECATION")
                        val observedVersion=service.packageManager.getPackageInfo(id.packageName,0).versionName.orEmpty()
                        check(plan.record(graph.draft.value ?: error("اختفى الطلب."),line,unit,System.currentTimeMillis(),observedVersion)) {
                            "تغير الطلب أو قراءة الصنف؛ لم أحتسب مجموعًا ناقصًا."
                        }
                    }
                    plan.preview(graph.draft.value ?: error("اختفى الطلب."),System.currentTimeMillis())
                        ?: error("لم تكتمل قراءة كل الأصناف؛ لم أحتسب مجموعًا ناقصًا.")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (stopped: CatalogStoppedException) { throw stopped }
                catch (interrupted: UserInterrupted) { throw interrupted }
                catch (changed: ScreenChangedBeforeAction) { throw changed }
                catch (failure: Exception) {
                    if(BuildConfig.VERSION_NAME.endsWith("-device-tools")) withContext(Dispatchers.IO) {
                        service.filesDir.resolve("multi-menu-failure-${id.name.lowercase()}.json").writeText(
                            JSONObject().put("provider",id.name).put("stage",multiReadStage)
                                .put("recordedAt",System.currentTimeMillis())
                                .put("reason",menuFailure(failure,"UNCLASSIFIED"))
                                .put("exceptionType",failure.javaClass.name).toString(),Charsets.UTF_8)
                    }
                    failedMenuPreview(id,desired,menuFailure(failure,"لم تكتمل قراءة جميع أصناف الطلب في ${id.title}؛ لم أحتسب مجموعًا جزئيًا ولم أغيّر السلة."))
                }
                }
                previews += preview
                graph.catalog.value = graph.catalog.value.copy(previews=previews.toList())
            }
            check(graph.draft.value?.fingerprint == desired.fingerprint) { "تغير طلبك؛ أعد المقارنة." }
            graph.catalog.value = graph.catalog.value.copy(message=
                "انتهت قراءة أسعار جميع الأصناف المتاحة. هذه قراءة قائمة فقط؛ تجهيز السلال متعددة الأصناف وإجمالياتها لم يُنفذا في هذا المسار.")
        }

    private suspend fun readHungerPlainLine(desired: CanonicalCart, line: CanonicalItem): Long {
        multiReadStage="HS_NAVIGATE"
        val restaurant=LiveRestaurant(desired.restaurant,null,desired.restaurant)
        check(!navigateToRestaurant(restaurant)) { "المطعم غير متاح للطلب في القراءة الحالية." }
        val opened=openHungerPlainProduct(await {HungerStationCatalog.screen(it)==CatalogScreen.MENU},desired,line)
        val item=opened.item
        multiReadStage="HS_READ_PRODUCT"
        val read=readHungerProduct(restaurant,item)
        val details=read.details
        multiReadStage="HS_VALIDATE_PRODUCT"
        check(details.product.title==line.name && details.optionsComplete && details.product.available &&
            MenuSelectionValidator().selectConfigured(details,ProductSelection(details.product.id,emptyMap(),1),System.currentTimeMillis()) is SelectionResult.Accepted) {
            "خيارات الصنف غير مكتملة؛ لم أستخدم سعرًا ناقصًا."
        }
        val price=details.product.listedPriceHalala ?: error("سعر الصنف غير مقروء.")
        if(opened.search!=null) {
            act(read.lastFrame,CatalogAction.RETURN_TO_CATALOG,{it.id=="pdpCloseButton"})
            closeHungerMenuSearch(opened.search,desired)
        }
        return price
    }

    private data class OpenedHungerPlainProduct(val menu:CatalogFrame,val item:LiveMenuItem,val search:HungerMenuSearchSession?)

    /** Search moves only through controls scoped to the entire active request. No Add authority. */
    private suspend fun openHungerPlainProduct(menu:CatalogFrame,cart:CanonicalCart,line:CanonicalItem):OpenedHungerPlainProduct {
        val direct=HungerStationCatalog.menuItems(menu).filter {it.name==line.name}
        check(direct.size<=1) {"الصنف غير فريد؛ لم أختر بديلًا."}
        if(direct.size==1) {
            val item=direct.single()
            act(menu,CatalogAction.OPEN_PRODUCT,{HungerStationCatalog.menuItem(it)==item})
            return OpenedHungerPlainProduct(menu,item,null)
        }
        multiReadStage="HS_SEARCH_REQUESTED_ITEM"
        val session=HungerMenuSearchSession.create(menu,cart,line.name,HungerStationCatalog.TESTED_VERSION,System.currentTimeMillis())
            ?: error("لم تثبت قائمة المطعم لبحث الصنف المطلوب.")
        performHungerMenuSearch(session,HungerMenuSearchAction.OPEN,menu,cart)
        val input=await {session.target(HungerMenuSearchAction.TYPE,it,cart,HungerStationCatalog.TESTED_VERSION,System.currentTimeMillis())!=null}
        performHungerMenuSearch(session,HungerMenuSearchAction.TYPE,input,cart)
        val results=await {session.candidates(it,cart,HungerStationCatalog.TESTED_VERSION,System.currentTimeMillis()).isNotEmpty()}
        val candidate=session.candidates(results,cart,HungerStationCatalog.TESTED_VERSION,System.currentTimeMillis()).singleOrNull()
            ?: error("نتائج الصنف متعددة؛ لم أختر بينها بالاسم فقط.")
        performHungerMenuSearch(session,HungerMenuSearchAction.OPEN_RESULT,results,cart)
        return OpenedHungerPlainProduct(menu,candidate.item,session)
    }

    private suspend fun closeHungerMenuSearch(session:HungerMenuSearchSession,cart:CanonicalCart):CatalogFrame {
        val search=await {session.target(HungerMenuSearchAction.CLOSE,it,cart,HungerStationCatalog.TESTED_VERSION,System.currentTimeMillis())!=null}
        performHungerMenuSearch(session,HungerMenuSearchAction.CLOSE,search,cart)
        return await {HungerStationCatalog.screen(it)==CatalogScreen.MENU}
    }

    /** An Add is never repeated. Close only our own surviving search before full cart observation. */
    private suspend fun hungerMenuAfterAdd(opened:OpenedHungerPlainProduct,cart:CanonicalCart):CatalogFrame {
        val search=opened.search
        val after=await {HungerStationCatalog.screen(it)==CatalogScreen.MENU ||
            search?.target(HungerMenuSearchAction.CLOSE,it,cart,HungerStationCatalog.TESTED_VERSION,System.currentTimeMillis())!=null}
        val menu=if(HungerStationCatalog.screen(after)==CatalogScreen.MENU)after else {
            performHungerMenuSearch(checkNotNull(search),HungerMenuSearchAction.CLOSE,after,cart)
            await {HungerStationCatalog.screen(it)==CatalogScreen.MENU}
        }
        check(menu.hasId("FlutterMenu_goToCartButton")) {"لم يظهر عداد السلة بعد الإضافة؛ لم أكررها."}
        return menu
    }

    private suspend fun readCompleteHungerBasket(menu:CatalogFrame,desired:CanonicalCart):Pair<CatalogFrame,BasketEvidence> {
        val version=graph.installations.value.singleOrNull {it.provider==ProviderId.HUNGERSTATION}?.version
        val proof=HungerStationCompleteCartProof.fromMenu(menu,desired.restaurant,version,System.currentTimeMillis())
            ?: error("تعذر إثبات عداد السلة ومبلغها من القائمة؛ لم أعدلها.")
        act(menu,CatalogAction.OPEN_BASKET,{it==proof.command.node},basketCommand=proof.command)
        val frame=await {HungerStationCatalog.screen(it)==CatalogScreen.CART}
        val evidence=proof.finish(frame,version,System.currentTimeMillis())
        if(evidence==null && BuildConfig.VERSION_NAME.endsWith("-device-tools")) {
            val read=BasketEvidenceReader.hungerStation(frame,false)
            val cards=frame.nodes.filter {it.id=="FlutterCart_cartItem"}.flatMap {frame.descendants(it.index)}
            withContext(Dispatchers.IO) {
                service.filesDir.resolve("multi-prepare-hs-public-cart.json").writeText(JSONObject()
                    .put("recordedAt",System.currentTimeMillis()).put("completeFrame",frame.complete)
                    .put("subtotal",read.subtotal ?: JSONObject.NULL)
                    .put("nodes",JSONArray(cards.map {n->JSONObject().put("index",n.index).put("parent",n.parent)
                        .put("id",n.id).put("label",n.label).put("className",n.className)
                        .put("clickable",n.clickable).put("enabled",n.enabled).put("editable",n.editable)
                        .put("scrollable",n.scrollable)})).toString(),Charsets.UTF_8)
            }
        }
        val complete = evidence ?: error("لم تتطابق جميع وحدات السلة ومبالغها مع القائمة؛ لم أعدلها.")
        observeBasketMutation(desired, complete)
        return frame to complete
    }

    private suspend fun leaveCompleteHungerBasket(frame:CatalogFrame,evidence:BasketEvidence):CatalogFrame {
        checkSession()
        val version=graph.installations.value.singleOrNull {it.provider==ProviderId.HUNGERSTATION}?.version
        val command=HungerStationCartReturnCommand.create(frame,evidence,version,System.currentTimeMillis())
            ?: error("لم أثبت شاشة سلة كاملة للرجوع منها.")
        val root=service.rootInActiveWindow ?: error("اختفت السلة قبل الرجوع.")
        try {
            requireProviderWindow(root)
            check(command.consume(capture(root),version,System.currentTimeMillis())) {"تغيرت السلة قبل الرجوع؛ توقفت."}
            checkSession()
            check(service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {"تعذر الرجوع من السلة؛ توقفت."}
        } finally {@Suppress("DEPRECATION") root.recycle()}
        return await {HungerStationCatalog.screen(it)==CatalogScreen.MENU}
    }

    /** Opt-in device probe: restaurant search -> exact food PDP -> known menu, no cart edits. */
    fun inspectHungerMenuSearchForVerification(query:String):Boolean {
        if(!BuildConfig.DEBUG || !BuildConfig.VERSION_NAME.endsWith("-device-tools") || active || graph.session.value.active)return false
        val cart=graph.draft.value ?: return false
        if(cart.items.none {it.name==query} || !matchesSelectedRestaurant())return false
        return begin(operation=CatalogOperation.COMPARE_MENU,returnOnSuccess=false) {
            provider=ProviderId.HUNGERSTATION;requestFingerprint=cart.fingerprint
            phase="فحص البحث عن الوجبة داخل المطعم"
            val restaurant=LiveRestaurant(cart.restaurant,null,cart.restaurant)
            check(!navigateToRestaurant(restaurant)) {"المطعم غير متاح للقراءة."}
            val menu=await {HungerStationCatalog.screen(it)==CatalogScreen.MENU}
            val session=HungerMenuSearchSession.create(menu,cart,query,HungerStationCatalog.TESTED_VERSION,System.currentTimeMillis())
                ?: error("لم تثبت قائمة المطعم المحدد لبحث الوجبة؛ لم أغير السلة.")
            performHungerMenuSearch(session,HungerMenuSearchAction.OPEN,menu,cart)
            val input=await {session.target(HungerMenuSearchAction.TYPE,it,cart,HungerStationCatalog.TESTED_VERSION,System.currentTimeMillis())!=null}
            performHungerMenuSearch(session,HungerMenuSearchAction.TYPE,input,cart)
            val results=await {session.results(it,cart,HungerStationCatalog.TESTED_VERSION,System.currentTimeMillis()) &&
                it.nodes.any {n->!n.editable && n.label.contains(query)}}
            withContext(Dispatchers.IO) {
                service.filesDir.resolve("hs-in-menu-search-result.json").writeText(JSONObject()
                    .put("recordedAt",System.currentTimeMillis()).put("query",query)
                    .put("frame",JSONObject(com.google.gson.Gson().toJson(results))).toString(),Charsets.UTF_8)
            }
            val candidate=session.candidates(results,cart,HungerStationCatalog.TESTED_VERSION,System.currentTimeMillis()).singleOrNull()
                ?: error("نتيجة الصنف ليست فريدة؛ لم أفتح بديلًا.")
            performHungerMenuSearch(session,HungerMenuSearchAction.OPEN_RESULT,results,cart)
            val read=readHungerProduct(restaurant,candidate.item)
            check(read.details.product.title==query) {"تغير الصنف بعد فتح النتيجة."}
            act(read.lastFrame,CatalogAction.RETURN_TO_CATALOG,{it.id=="pdpCloseButton"})
            closeHungerMenuSearch(session,cart)
            withContext(Dispatchers.IO) {
                service.filesDir.resolve("hs-menu-search-product-result.json").writeText(JSONObject()
                    .put("recordedAt",System.currentTimeMillis()).put("details",JSONObject(com.google.gson.Gson().toJson(read.details)))
                    .put("returnedToMenu",true).put("basketEdits",0).toString(),Charsets.UTF_8)
            }
            graph.catalog.value=graph.catalog.value.copy(message="قرأت خيارات الصنف وعدت إلى القائمة؛ لم أضف أو أعدل سلة.")
        }
    }

    private suspend fun performHungerMenuSearch(session:HungerMenuSearchSession,action:HungerMenuSearchAction,
        previous:CatalogFrame,cart:CanonicalCart) {
        currentCoroutineContext().ensureActive();checkSession()
        val root=service.rootInActiveWindow ?: throw UserInterrupted("اختفت شاشة بحث الوجبة؛ توقفت.")
        try {
            requireProviderWindow(root)
            val fresh=capture(root)
            if(fresh!=previous)throw ScreenChangedBeforeAction()
            val now=System.currentTimeMillis()
            val current=graph.draft.value ?: error("اختفى الطلب.")
            check(current.fingerprint==cart.fingerprint)
            @Suppress("DEPRECATION")
            val version=service.packageManager.getPackageInfo(provider.packageName,0).versionName
            val target=session.target(action,fresh,current,version,now) ?: error("لم يتحدد عنصر بحث وجبة موثوق.")
            check(SafetyGuard().allowsHungerMenuSearch(action,session,fresh,target,current,
                root.packageName?.toString(),version,graph.settings.value.catalogConsent,currentCoroutineContext().isActive,now))
            // This indexed lookup uses the same privacy-pruned traversal as capture for either provider.
            val node=locateKeetaNode(root,target) ?: throw ScreenChangedBeforeAction()
            try {
                check(node.refresh() && node.isVisibleToUser && node.isEnabled && !node.isPassword &&
                    !(android.os.Build.VERSION.SDK_INT>=34 && node.isAccessibilityDataSensitive) &&
                    node.viewIdResourceName.orEmpty()==target.id && node.className?.toString()==target.className)
                verifyActiveFrame(fresh,root.windowId);checkSession()
                check(session.consume(action,fresh,target,current,version,System.currentTimeMillis()))
                val accepted=if(action==HungerMenuSearchAction.TYPE)node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,session.query)
                }) else node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                check(accepted) {"تعذر تنفيذ بحث الوجبة؛ لم أكرر الإجراء."}
            } finally {@Suppress("DEPRECATION") node.recycle()}
        } finally {@Suppress("DEPRECATION") root.recycle()}
    }

    /** Development-only public menu capture; known menu only, never review/account/payment screens. */
    fun inspectKeetaMenuForVerification():Boolean {
        if(!BuildConfig.DEBUG || !BuildConfig.VERSION_NAME.endsWith("-device-tools") || active || graph.session.value.active)return false
        val cart=graph.draft.value ?: return false
        val profile=installedKeetaProfile() ?: return false
        val line=PlainMenuReadPlan.create(cart,ProviderId.KEETA,profile.version)?.lines?.firstOrNull() ?: return false
        if(!matchesSelectedRestaurant())return false
        return begin(operation=CatalogOperation.COMPARE_MENU,returnOnSuccess=false) {
            provider=ProviderId.KEETA;bindKeetaRequest(cart,line);targetSeen=false
            val launch=service.packageManager.getLaunchIntentForPackage(provider.packageName) ?: error("كيتا غير مثبت.")
            service.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val frame=await {f->keetaMenu.menu(f) || (f.complete && f.nodes.count {it.label==cart.restaurant}==1 &&
                f.nodes.count {it.label==line.name && !it.clickable}==1 && f.nodes.count {it.label=="طلب خاص"}==1 &&
                f.nodes.count {it.label=="إضافة إلى السلة"}==1 && f.nodes.count {it.editable}==1 && f.nodes.none {it.enteredText})}
            withContext(Dispatchers.IO) {
                service.filesDir.resolve("keeta-public-menu-shape.json").writeText(JSONObject()
                    .put("recordedAt",System.currentTimeMillis()).put("frame",JSONObject(com.google.gson.Gson().toJson(frame)))
                    .toString(),Charsets.UTF_8)
            }
        }
    }

    private class PlainPreparationProgress(var stage:String="START",var adds:Int=0,var rows:Int=0)

    /** Reusable provider steps retain the full request; callers own session lifetime. */
    private suspend fun prepareOwnedHungerSteps(desired:CanonicalCart, readReview:Boolean, progress:PlainPreparationProgress, testEdits:Boolean=false, readOnly:Boolean=false) {
        provider=ProviderId.HUNGERSTATION
        requestFingerprint=desired.fingerprint
        forgetPrepared(provider)
        check(graph.installations.value.singleOrNull {it.provider==provider}?.version==HungerStationCatalog.TESTED_VERSION)
        progress.stage="OPEN_BASELINE"
        phase="التحقق من جميع صفوف سلة هنقرستيشن"
        val restaurant=LiveRestaurant(desired.restaurant,null,desired.restaurant)
        check(!navigateToRestaurant(restaurant)) {"المطعم غير متاح للطلب؛ لم أعدل السلة."}
        var menu=await {HungerStationCatalog.screen(it)==CatalogScreen.MENU}
        var seed:PlainBasketSeed?=null
        if(menu.nodes.none {it.id=="FlutterMenu_goToCartButton"}) {
            check(!readOnly) {"السلة أصبحت فارغة؛ أعد المقارنة. لم أضف أثناء التسليم."}
            requireFirstBasketAdd(desired)
            val first=PlainMenuReadPlan.create(desired,provider,HungerStationCatalog.TESTED_VERSION)!!.lines.first()
            val observedAt=System.currentTimeMillis()
            val opened=openHungerPlainProduct(menu,desired,first)
            val empty=opened.menu
            val read=readHungerProduct(restaurant,opened.item)
            val permit=RidanCartProgram.createForSeed(read.details,ProductSelection(read.details.product.id,emptyMap(),1),
                empty,desired,first,System.currentTimeMillis(),observedAt) ?: error("لم تثبت سلة فارغة وصنف أول مطابق؛ لم أضف.")
            var product=read.lastFrame
            val unit=read.details.product.listedPriceHalala ?: error("سعر الصنف الأول غير مقروء.")
            var changes=0
            while(HungerStationProductQuantityCommand.observed(product,first.name,unit)!=first.quantity) {
                check(++changes<=98)
                val command=HungerStationProductQuantityCommand.next(product,first.name,unit,first.quantity,HungerStationCatalog.TESTED_VERSION,System.currentTimeMillis())
                    ?: error("لم تثبت كمية الصنف الأول؛ لم أضف.")
                act(product,CatalogAction.ADJUST_RIDAN_QUANTITY,{it==command.target},productQuantityCommand=command)
                product=await {HungerStationProductQuantityCommand.observed(it,first.name,unit)==command.expectedAfter}
            }
            val add=product.nodes.singleOrNull {it.id=="flutterAddButton"} ?: error("زر الإضافة غير فريد.")
            progress.stage="SEED_FIRST_LINE"
            act(product,CatalogAction.ADD_RIDAN,{it==add},ridanProgram=permit.first)
            progress.adds++
            seed=permit.second
            menu=hungerMenuAfterAdd(opened,desired)
        }
        var (cart,evidence)=readCompleteHungerBasket(menu,desired)
        seed?.let {check(it.acknowledge(graph.draft.value ?: desired,evidence,HungerStationCatalog.TESTED_VERSION,System.currentTimeMillis())) {
            "لم تتطابق السلة كاملة بعد أول إضافة؛ لم أكررها."
        }}
        progress.rows=evidence.lines.size
        val owner=PlainBasketPreparationSession.create(desired,evidence,HungerStationCatalog.TESTED_VERSION)
            ?: error("السلة بها صنف أو إضافة غير مطابقة؛ لم أعدلها.")
        var ready=false
        for(step in 0 until 200) {
            checkSession()
            when(val next=owner.next(graph.draft.value ?: error("اختفى الطلب."),evidence,HungerStationCatalog.TESTED_VERSION,System.currentTimeMillis())) {
                PlainBasketPreparationSession.Step.Blocked -> error("تغيرت محتويات السلة؛ توقفت دون إعادة الإجراء.")
                PlainBasketPreparationSession.Step.Ready -> {ready=true;break}
                is PlainBasketPreparationSession.Step.Change -> {
                    check(!readOnly) {"تغيرت السلة منذ المقارنة؛ أعد المقارنة لتجهيز طلبك. لم أعدلها أثناء التسليم."}
                    check(testEdits || seed!=null || graph.existingQuantityConsent.value?.permits(desired,System.currentTimeMillis())==true) {
                        "السلة السابقة تحتاج تعديلًا؛ فعّل الموافقة على تعديل الأصناف المطابقة ثم أعد المقارنة."
                    }
                    val edit=next.edit
                    if(edit.kind!=PlainBasketPreparationSession.Kind.ADD_ONE) {
                        progress.stage="CHANGE_OWNED_QUANTITY"
                        val command=HungerStationOwnedQuantityCommand.create(cart,evidence,desired,owner,edit,HungerStationCatalog.TESTED_VERSION,System.currentTimeMillis())
                            ?: error("لم يثبت عنصر كمية الصف المملوك؛ لم أعدله.")
                        act(cart,CatalogAction.ADJUST_RIDAN_QUANTITY,{it==command.target},ownedQuantityCommand=command)
                        val afterFrame=await {command.afterMatches(it,graph.draft.value ?: desired,HungerStationCatalog.TESTED_VERSION,System.currentTimeMillis())}
                        val root=service.rootInActiveWindow ?: error("اختفت السلة بعد تعديل الكمية.")
                        try {
                            requireProviderWindow(root)
                            checkSession()
                            check(capture(root)==afterFrame && command.consumeReturn(afterFrame,graph.draft.value ?: desired,HungerStationCatalog.TESTED_VERSION,System.currentTimeMillis()))
                            check(service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                        } finally {@Suppress("DEPRECATION") root.recycle()}
                        menu=await {HungerStationCatalog.screen(it)==CatalogScreen.MENU}
                        val checked=readCompleteHungerBasket(menu,desired)
                        check(owner.acknowledge(edit,graph.draft.value ?: desired,checked.second,HungerStationCatalog.TESTED_VERSION,System.currentTimeMillis())) {
                            "لم تتطابق السلة كاملة بعد تعديل كمية واحد؛ لم أكرره."
                        }
                        cart=checked.first;evidence=checked.second;progress.rows=evidence.lines.size
                        continue
                    }
                    progress.stage="FIND_MISSING_LINE"
                    menu=leaveCompleteHungerBasket(cart,evidence)
                    // The first added product can leave the menu below the next requested line.
                    // Reset via known read-only restaurant search, then re-observe all rows before Add.
                    check(!navigateToRestaurant(restaurant)) {"المطعم غير متاح؛ لم أضف الصنف الناقص."}
                    menu=await {HungerStationCatalog.screen(it)==CatalogScreen.MENU}
                    // Re-observe every row immediately before opening the missing product.
                    progress.stage="RECHECK_BASELINE"
                    val checked=readCompleteHungerBasket(menu,desired)
                    check(checked.second==evidence) {"تغيرت السلة أثناء البحث؛ لم أضف صنفًا."}
                    check((owner.next(desired,checked.second,HungerStationCatalog.TESTED_VERSION,System.currentTimeMillis()) as? PlainBasketPreparationSession.Step.Change)?.edit===edit)
                    menu=leaveCompleteHungerBasket(checked.first,checked.second)
                    progress.stage="READ_MISSING_PRODUCT"
                    val opened=openHungerPlainProduct(menu,desired,edit.line)
                    val read=readHungerProduct(restaurant,opened.item)
                    val selection=ProductSelection(read.details.product.id,emptyMap(),1)
                    val program=RidanCartProgram.createForOwnedAddition(read.details,selection,menu,desired,evidence,owner,edit,System.currentTimeMillis())
                        ?: error("لم يتأكد تصريح إضافة الصنف إلى السلة المملوكة؛ لم أضفه.")
                    progress.stage="ADD_ONE"
                    phase="إضافة الصنف الناقص ثم مراجعة جميع صفوف السلة"
                    val add=read.lastFrame.nodes.singleOrNull {it.id=="flutterAddButton"} ?: error("لم يظهر زر إضافة فريد.")
                    act(read.lastFrame,CatalogAction.ADD_RIDAN,{it==add},ridanProgram=program)
                    progress.adds++
                    progress.stage="OBSERVE_ADDED_CART"
                    menu=hungerMenuAfterAdd(opened,desired)
                    val after=readCompleteHungerBasket(menu,desired)
                    check(owner.acknowledge(edit,graph.draft.value ?: desired,after.second,HungerStationCatalog.TESTED_VERSION,System.currentTimeMillis())) {
                        "لم تتطابق جميع صفوف السلة بعد الإضافة؛ توقفت ولم أكررها."
                    }
                    cart=after.first;evidence=after.second;progress.rows=evidence.lines.size
                }
            }
        }
        check(ready) {"انتهى حد الإجراءات؛ توقفت."}
        rememberPrepared(desired,evidence,HungerStationCatalog.TESTED_VERSION)
        if(readReview) {progress.stage="READ_FINAL_REVIEW";readFinalTotal(desired,cart)}
        progress.stage="COMPLETE"
        graph.catalog.value=graph.catalog.value.copy(preparedBasket=evidence,
            message=if(readReview) "تحققت سلة هنقرستيشن وقرأت إجمالي مراجعتها. لم يُؤكد طلب أو دفع."
            else "تحققت أصناف سلة هنقرستيشن وكمياتها كاملة. لم تُقرأ مراجعة الطلب ولم يُؤكد طلب أو دفع.")
    }

    fun preparePlainHungerForVerification(readReview:Boolean=false,showCart:Boolean=false):Boolean {
        if(!BuildConfig.DEBUG || !BuildConfig.VERSION_NAME.endsWith("-device-tools") || active || graph.session.value.active)return false
        val desired=graph.draft.value ?: return false
        val lines=PlainMenuReadPlan.create(desired,ProviderId.HUNGERSTATION,HungerStationCatalog.TESTED_VERSION)?.lines ?: return false
        if(lines.size!=2 || !matchesSelectedRestaurant())return false
        graph.catalog.value=graph.catalog.value.copy(comparisonRunning=true,
            finalTotals=graph.catalog.value.finalTotals.filterValues {it.fresh(desired,System.currentTimeMillis())} - ProviderId.HUNGERSTATION,error=null)
        val epoch=++comparisonEpoch
        comparisonJob=graph.scope.launch(start=CoroutineStart.LAZY) {
            val progress=PlainPreparationProgress()
            try {
                check(begin(operation=CatalogOperation.BUILD_CART,timeoutMillis=600_000) {
                    prepareOwnedHungerSteps(desired,readReview,progress,testEdits=true,readOnly=showCart)
                })
                job?.join()
            } finally {
                val mayReturn=currentCoroutineContext().isActive
                VerificationCleanup.finish(record={withContext(Dispatchers.IO) {
                    service.filesDir.resolve("multi-prepare-hs-stage.json").writeText(JSONObject()
                        .put("recordedAt",System.currentTimeMillis()).put("stage",progress.stage)
                        .put("dispatchedAdds",progress.adds).put("observedRows",progress.rows).toString(),Charsets.UTF_8)
                }},release={
                if(comparisonEpoch==epoch) {
                    graph.catalog.value=graph.catalog.value.copy(comparisonRunning=false)
                    comparisonJob=null
                    if(!showCart && stepInterrupted==null && mayReturn && catalogStop.evidence==null) returnToApp()
                }
                })
            }
        }
        comparisonJob?.start()
        return true
    }

    private suspend fun ensureKeetaPlainProduct(initial:CatalogFrame):CatalogFrame {
        if (keetaMenu.productBody(initial) != null) return initial
        val session = KeetaOptionalPlainSession.start(initial,keetaMenu,System.currentTimeMillis())
            ?: error("خيارات كيتا تحتاج قارئًا موثقًا لهذا المنتج؛ لم أضف.")
        var frame = initial
        repeat(12) {
            if (keetaMenu.acceptOptionalPlain(frame,session,System.currentTimeMillis())) return frame
            check(!session.blocked) {"تغيرت خيارات كيتا أو ظهر اختيار غير متحقق؛ لم أضف."}
            val body = KeetaOptionalPlainSession.readBody(frame,keetaMenu.restaurant,keetaMenu.title)
                ?: error("تغيرت صفحة خيارات كيتا؛ لم أضف.")
            scrollKeetaProductPart(frame,body)
            val before = frame
            frame = await { it != before && keetaMenu.product(it) }
        }
        error("لم تكتمل تغطية خيارات كيتا؛ لم أضف.")
    }

    private suspend fun scrollKeetaProductPart(previous:CatalogFrame,body:CatalogNode) {
        checkSession()
        val root=service.rootInActiveWindow ?: error("اختفت خيارات كيتا.")
        val rect=Rect()
        val windowId=root.windowId
        try {
            requireProviderWindow(root)
            if(capture(root)!=previous) throw ScreenChangedBeforeAction()
            val node=locateKeetaNode(root,body) ?: throw ScreenChangedBeforeAction()
            try {
                check(node.refresh() && node.isVisibleToUser && node.isEnabled && node.isScrollable && !node.isEditable)
                node.getBoundsInScreen(rect)
            } finally {node.recycle()}
        } finally {root.recycle()}
        check(rect.height()>240 && rect.width()>120)
        val x=rect.exactCenterX()
        val path=Path().apply {moveTo(x,rect.top+rect.height()*.70f);lineTo(x,rect.top+rect.height()*.40f)}
        val gesture=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,450)).build()
        val done=withTimeout(3000) {suspendCancellableCoroutine<Boolean> { c ->
            checkSession();verifyActiveFrame(previous,windowId)
            if(!service.dispatchGesture(gesture,object:AccessibilityService.GestureResultCallback(){
                override fun onCompleted(g:GestureDescription) {if(c.isActive)c.resume(true)}
                override fun onCancelled(g:GestureDescription) {if(c.isActive)c.resume(false)}
            },null) && c.isActive)c.resume(false)
        }}
        check(done) {"توقف تمرير الخيارات؛ لم أضف."}
    }

    private suspend fun readKeetaPlainLine(desired: CanonicalCart, line: CanonicalItem): Long {
        val frame=openKeetaPlainLine(desired,line)
        try {
            multiReadStage="KEETA_VALIDATE_PRODUCT"
            check(keetaMenu.productBody(frame)!=null) { "خيارات كيتا غير مكتملة؛ لم أستخدم سعرًا ناقصًا." }
            multiReadStage="KEETA_UNIT_PRICE"
            return keetaMenu.unitPrice(frame) ?: error("سعر الصنف غير مقروء.")
        } finally {
            if(keetaMenu.product(frame)) {
                closeKeetaReadSheet(frame)
                await {keetaMenu.menu(it)}
            }
        }
    }

    private suspend fun openKeetaPlainLine(desired: CanonicalCart, line: CanonicalItem): CatalogFrame {
        bindKeetaRequest(desired,line)
        targetSeen=false
        val launch=service.packageManager.getLaunchIntentForPackage(provider.packageName) ?: error("كيتا غير مثبت.")
        service.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        multiReadStage="KEETA_OPEN_REQUEST"
        var frame=openKeetaRequest()
        if(keetaMenu.basketDrawer(frame)) {closeKeetaReadSheet(frame);frame=await {keetaMenu.menu(it)}}
        if(keetaMenu.searchCandidate(frame)!=null) {
            keetaBasketAction { keetaMenu.searchCandidate(it) }
            frame=await {keetaMenu.menu(it)}
        }
        if(keetaMenu.menu(frame)) {
            // Each next requested item may be above the previous one. Rewind only the known list.
            multiReadStage="KEETA_REWIND_MENU"
            var rewinds=0
            while(keetaMenu.productTarget(frame)==null && rewinds++<60) {
                val before=frame.nodes
                if(!scrollKeetaHeader(frame))break
                frame=await {keetaMenu.menu(it) && it.nodes!=before}
            }
            multiReadStage="KEETA_FIND_PRODUCT"
            openKeetaPreviewProduct(frame)
            frame=awaitKeetaProduct()
        }
        return ensureKeetaPlainProduct(frame)
    }

    /** Device verification entry only. No checkout, clear-cart or replacement action exists. */
    private suspend fun prepareOwnedKeetaSteps(desired:CanonicalCart, readReview:Boolean, progress:PlainPreparationProgress, testEdits:Boolean=false, readOnly:Boolean=false,clearTestCart:Boolean=false) {
        val profile=checkNotNull(installedKeetaProfile()) {"إصدار كيتا غير مدعوم لتجهيز السلة."}
        check(profile.allowsOwnedPlainPreparation) {"إصدار كيتا غير مدعوم لتجهيز السلة."}
        val lines=PlainMenuReadPlan.create(desired,ProviderId.KEETA,profile.version)?.lines ?: error("طلب غير مدعوم.")
        check(profile.allowsPreparation(lines.size>1)) {"إصدار كيتا لا يدعم تجهيز هذا العدد من الأصناف."}
        provider=ProviderId.KEETA
        bindKeetaRequest(desired,lines.first())
        check(graph.installations.value.singleOrNull {it.provider==provider}?.version==profile.version)
        forgetPrepared(provider)
        progress.stage="OPEN_BASELINE"
        phase="قراءة السلة كاملة قبل تجهيز أصناف كيتا"
        val launch=service.packageManager.getLaunchIntentForPackage(provider.packageName) ?: error("كيتا غير مثبت.")
        service.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var frame=openKeetaRequest()
        if(keetaMenu.searchCandidate(frame)!=null) {
            keetaBasketAction {keetaMenu.searchCandidate(it)}
            frame=await {keetaMenu.menu(it)}
        }
        if(keetaMenu.product(frame)) {
            closeKeetaReadSheet(frame);frame=await {keetaMenu.menu(it)}
        }
        var seed:PlainBasketSeed?=null
        if(keetaMenu.menu(frame) && keetaBasket.footerBag(frame)==null && !keetaBasket.emptyMenu(frame)) {
            // Establish the first requested card without editing anything, then observe empty-menu evidence.
            frame=openKeetaPlainLine(desired,lines.first())
            closeKeetaReadSheet(frame)
            frame=await {keetaMenu.menu(it)}
        }
        if(!keetaMenu.basketDrawer(frame) && keetaBasket.emptyMenu(frame)) {
            check(!readOnly) {"سلة كيتا أصبحت فارغة؛ أعد المقارنة. لم أضف أثناء التسليم."}
            requireFirstBasketAdd(desired)
            val empty=frame
            val observedAt=System.currentTimeMillis()
            openKeetaPreviewProduct(frame)
            frame=ensureKeetaPlainProduct(awaitKeetaProduct())
            val unit=keetaMenu.unitPrice(frame) ?: error("سعر الصنف الأول غير مقروء.")
            val token=PlainBasketSeed.create(desired,lines.first(),provider,keetaMenu.VERSION,unit,empty,observedAt)
                ?: error("لم يثبت تصريح الصنف الأول من سلة فارغة؛ لم أضف.")
            progress.stage="SEED_FIRST_LINE"
            keetaBasketAction { current -> keetaBasket.addTarget(current,unit)?.takeIf {
                token.consumeAdd(graph.draft.value ?: desired,keetaMenu.VERSION,System.currentTimeMillis())
            }}
            progress.adds++
            seed=token
            frame=await {keetaMenu.menu(it) && keetaBasket.footerBag(it)!=null}
        }
        if(!keetaMenu.basketDrawer(frame)) {
            check(keetaMenu.menu(frame) && keetaBasket.footerBag(frame)!=null) {"تعذرت قراءة السلة كاملة؛ لم أكرر الإضافة."}
            keetaBasketAction {keetaBasket.footerBag(it)}
            frame=await {keetaMenu.basketDrawer(it)}
        }
        if(keetaBasket.evidence(frame)==null && keetaMenu.basketDrawer(frame)) {
            val heading=frame.nodes.single {it.label=="الأصناف المضافة"}
            val header=frame.nodes.single {it.index==heading.parent}
            val pane=frame.nodes.single {it.index==header.parent}
            withContext(Dispatchers.IO) {
                service.filesDir.resolve("multi-prepare-keeta-public-shape.json").writeText(JSONArray(frame.descendants(pane.index).map {n->
                    JSONObject().put("index",n.index).put("parent",n.parent).put("id",n.id)
                        .put("className",n.className).put("label",n.label).put("clickable",n.clickable)
                        .put("editable",n.editable).put("scrollable",n.scrollable).put("enabled",n.enabled)
                        .put("omittedText",n.omittedText)
                }).toString(),Charsets.UTF_8)
            }
        }
        var evidence=keetaBasket.evidence(frame) ?: error("لم تثبت قراءة سلة كيتا كاملة؛ لم أعدلها.")
        observeBasketMutation(desired, evidence)
        seed?.let {check(it.acknowledge(graph.draft.value ?: desired,evidence,keetaMenu.VERSION,System.currentTimeMillis())) {
            "لم تتطابق سلة كيتا كاملة بعد أول إضافة؛ لم أكررها."
        }}
        progress.rows=evidence.lines.size
        progress.stage="VALIDATE_BASELINE"
        val ownership=PlainBasketPreparationSession.create(desired,evidence,keetaMenu.VERSION)
            ?: error("السلة بها أصناف أو إضافات غير مطابقة؛ لم أعدلها.")
        var ready=false
        for(step in 0 until 200) {
            checkSession()
            when(val next=ownership.next(graph.draft.value ?: error("اختفى الطلب."),evidence,keetaMenu.VERSION,System.currentTimeMillis())) {
                PlainBasketPreparationSession.Step.Blocked -> error("تغيرت السلة أو الطلب؛ توقفت دون إعادة الإجراء.")
                PlainBasketPreparationSession.Step.Ready -> {ready=true;break}
                is PlainBasketPreparationSession.Step.Change -> {
                    check(!readOnly) {"تغيرت السلة منذ المقارنة؛ أعد المقارنة لتجهيز طلبك. لم أعدلها أثناء التسليم."}
                    check(testEdits || seed!=null || graph.existingQuantityConsent.value?.permits(desired,System.currentTimeMillis())==true) {
                        "السلة السابقة تحتاج تعديلًا؛ فعّل الموافقة على تعديل الأصناف المطابقة ثم أعد المقارنة."
                    }
                    val edit=next.edit
                    phase="مطابقة جميع أصناف سلة كيتا"
                    bindKeetaRequest(desired,edit.line)
                    if(edit.kind==PlainBasketPreparationSession.Kind.ADD_ONE) {
                        progress.stage="OPEN_MISSING_PRODUCT"
                        frame=openKeetaPlainLine(desired,edit.line)
                        progress.stage="ADD_MISSING_PRODUCT"
                        val unit=keetaMenu.unitPrice(frame) ?: error("سعر الصنف غير مقروء.")
                        keetaBasketAction {current ->
                            keetaBasket.addTarget(current,unit)?.takeIf {
                                ownership.consume(edit,graph.draft.value ?: desired,keetaMenu.VERSION,System.currentTimeMillis())
                            }
                        }
                        progress.adds++
                        progress.stage="WAIT_ADDED_MENU"
                        frame=await {keetaMenu.menu(it) && keetaBasket.footerBag(it)!=null}
                        progress.stage="OPEN_ADDED_CART"
                        keetaBasketAction {keetaBasket.footerBag(it)}
                    } else {
                        val before=evidence
                        keetaBasketAction {current ->
                            if(keetaBasket.evidence(current)!=before)null
                            else keetaBasket.quantityTarget(current,edit.kind==PlainBasketPreparationSession.Kind.INCREASE_ONE)?.takeIf {
                                ownership.consume(edit,graph.draft.value ?: desired,keetaMenu.VERSION,System.currentTimeMillis())
                            }
                        }
                    }
                    progress.stage="OBSERVE_CHANGED_CART"
                    frame=await {
                        val observed=keetaBasket.evidence(it)
                        observed!=null && observed!=evidence
                    }
                    val observed=keetaBasket.evidence(frame) ?: error("تعذرت قراءة السلة بعد الإجراء؛ لم أكرره.")
                    check(ownership.acknowledge(edit,graph.draft.value ?: desired,observed,keetaMenu.VERSION,System.currentTimeMillis())) {
                        "لم تتطابق جميع صفوف السلة بعد إجراء واحد؛ توقفت دون تكراره."
                    }
                    evidence=observed
                    observeBasketMutation(desired, evidence)
                    progress.rows=evidence.lines.size
                }
            }
        }
        progress.stage="VERIFY_COMPLETE"
        check(ready) {"انتهى حد إجراءات السلة؛ توقفت."}
        if(clearTestCart) {
            check(BuildConfig.DEBUG && BuildConfig.VERSION_NAME.endsWith("-device-tools") && testEdits && !readOnly && !readReview)
            val command=KeetaOwnedCartClearCommand.create(frame,desired,evidence,keetaMenu.VERSION,System.currentTimeMillis())
                ?: error("لم يثبت زر تفريغ سلة الاختبار المطابقة؛ لم أفرغها.")
            val root=service.rootInActiveWindow ?: error("اختفت سلة الاختبار.")
            try {
                requireProviderWindow(root)
                val current=capture(root)
                checkSession()
                check(command.consume(current,command.target,graph.draft.value ?: desired,keetaMenu.VERSION,System.currentTimeMillis()))
                val node=locateKeetaNode(root,command.target) ?: error("اختفى زر تفريغ سلة الاختبار.")
                try {
                    check(node.refresh() && node.isVisibleToUser && node.isEnabled && node.isClickable && !node.isPassword && !node.isEditable &&
                        !(android.os.Build.VERSION.SDK_INT>=34 && node.isAccessibilityDataSensitive))
                    verifyActiveFrame(current,root.windowId);checkSession()
                    check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                } finally {@Suppress("DEPRECATION") node.recycle()}
            } finally {@Suppress("DEPRECATION") root.recycle()}
            progress.stage="TEST_CLEAR_DISPATCHED"
            val after=await {keetaMenu.menu(it) && keetaBasket.footerBag(it)==null || it.nodes.size<45}
            withContext(Dispatchers.IO) {service.filesDir.resolve("keeta-test-clear-after.json").writeText(JSONObject()
                .put("recordedAt",System.currentTimeMillis()).put("complete",after.complete).put("nodes",JSONArray(after.nodes.map {n->JSONObject()
                    .put("index",n.index).put("parent",n.parent).put("id",n.id).put("label",n.label).put("className",n.className)
                    .put("clickable",n.clickable).put("enabled",n.enabled).put("editable",n.editable).put("scrollable",n.scrollable)
                    .put("omittedText",n.omittedText)})).toString(),Charsets.UTF_8)}
            check(keetaMenu.menu(after) && keetaBasket.footerBag(after)==null) {"ظهر تأكيد تفريغ غير مدعوم؛ لم أضغطه."}
            forgetPrepared(provider)
            progress.stage="TEST_CART_CLEARED"
            graph.catalog.value=graph.catalog.value.copy(message="فُرغت سلة الاختبار المطابقة. لم يُنشأ طلب أو دفع.")
            return
        }
        rememberPrepared(desired,evidence,keetaMenu.VERSION)
        if(BuildConfig.DEBUG && BuildConfig.VERSION_NAME.endsWith("-device-tools")) withContext(Dispatchers.IO) {
            service.filesDir.resolve("keeta-prepared-public-frame.json").writeText(JSONObject().put("recordedAt",System.currentTimeMillis())
                .put("complete",frame.complete).put("nodes",JSONArray(frame.nodes.map {n->JSONObject()
                    .put("index",n.index).put("parent",n.parent).put("id",n.id).put("label",n.label).put("className",n.className)
                    .put("clickable",n.clickable).put("enabled",n.enabled).put("editable",n.editable).put("scrollable",n.scrollable)
                    .put("omittedText",n.omittedText)})).toString(),Charsets.UTF_8)
        }
        if(readReview) {progress.stage="READ_FINAL_REVIEW";readFinalTotal(desired,frame)}
        progress.stage="COMPLETE"
        graph.catalog.value=graph.catalog.value.copy(preparedBasket=evidence,
            message=if(readReview) "تحققت سلة كيتا وقرأت إجمالي مراجعتها. لم يُؤكد طلب أو دفع."
            else "تحققت أصناف سلة كيتا وكمياتها كاملة. لم تُقرأ مراجعة الطلب ولم يُؤكد طلب أو دفع.")
    }

    fun preparePlainKeetaForVerification(readReview:Boolean=false,clearTestCart:Boolean=false): Boolean {
        if (!BuildConfig.DEBUG || !BuildConfig.VERSION_NAME.endsWith("-device-tools") || active || graph.session.value.active) return false
        val desired=graph.draft.value ?: return false
        val profile=installedKeetaProfile()?.takeIf {it.allowsOwnedPlainPreparation} ?: return false
        val lines=PlainMenuReadPlan.create(desired,ProviderId.KEETA,profile.version)?.lines ?: return false
        if(lines.size!=2 || !matchesSelectedRestaurant())return false
        graph.catalog.value=graph.catalog.value.copy(comparisonRunning=true,
            finalTotals=graph.catalog.value.finalTotals.filterValues {it.fresh(desired,System.currentTimeMillis())} - ProviderId.KEETA,error=null)
        val epoch=++comparisonEpoch
        comparisonJob=graph.scope.launch(start=CoroutineStart.LAZY) {
            val progress=PlainPreparationProgress()
            try {
                check(begin(operation=CatalogOperation.BUILD_CART,timeoutMillis=600_000) {
                    prepareOwnedKeetaSteps(desired,readReview,progress,testEdits=true,clearTestCart=clearTestCart)
                })
                job?.join()
            } finally {
                val mayReturn=currentCoroutineContext().isActive
                VerificationCleanup.finish(record={withContext(Dispatchers.IO) {
                    service.filesDir.resolve("multi-prepare-keeta-stage.json").writeText(JSONObject()
                        .put("recordedAt",System.currentTimeMillis()).put("stage",progress.stage)
                        .put("dispatchedAdds",progress.adds).put("observedRows",progress.rows).toString(),Charsets.UTF_8)
                }},release={
                if(comparisonEpoch==epoch) {
                    graph.catalog.value=graph.catalog.value.copy(comparisonRunning=false)
                    comparisonJob=null
                    if(!clearTestCart && stepInterrupted==null && mayReturn && catalogStop.evidence==null) returnToApp()
                }
                })
            }
        }
        comparisonJob?.start()
        return true
    }

    // Surface only our fixed messages; provider text and exception payloads stay private.
    private fun menuFailure(failure: Exception, fallback: String): String = failure.message?.takeIf { it in setOf(
        KeetaUnsupportedNotesSheet.MESSAGE,
        "المطعم غير متاح للطلب في القراءة الحالية.",
        "لم تظهر الوجبة المطابقة في هنقرستيشن؛ لم أستبدلها بوجبة أخرى.",
        "لم يكتمل التحقق من الوجبة في هنقرستيشن؛ لا توجد مقارنة مؤكدة.",
        "لم تكتمل قراءة خيارات الوجبة في هنقرستيشن؛ لم أغير السلة.",
        "زر إضافة الوجبة في هنقرستيشن غير متاح في القراءة الحالية؛ لم أغير السلة.",
        "سعر الوجبة في قائمة هنقرستيشن غير مقروء؛ لم أغير السلة.",
        "تحتاج الوجبة خيارات إلزامية؛ لم أستخدم سعرًا لطلب ناقص.",
        "خيارات كيتا تحتاج دعمًا، ولا توجد سلة مطابقة قابلة للتحقق؛ لم أضف وجبة.",
        "تعذرت قراءة سلة كيتا بالكامل.",
        "قراءة القائمة غير مكتملة؛ لم أفتح وجبة مختلفة.",
        "الوجبة المطابقة غير ظاهرة في قائمة كيتا.",
        "الصنف المطابق ظاهر لكن عنصر فتحه غير مدعوم؛ لم أنقر عنصرًا آخر.",
        "وصلت إلى نهاية الجزء المقروء دون الوجبة المطلوبة.",
        "لم أجد الوجبة المطابقة ضمن مهلة التصفح؛ لم أستبدلها.",
        "سلة كيتا مختلفة عن طلب المقارنة؛ لم أعدلها.",
        "تعذر التحقق من سعر الوجبة المطابقة في كيتا؛ لم تتغير السلة."
    ) } ?: fallback

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
        if(handoff && (PlainComparisonSupport.existingTwoRow(desired) ||
            installedKeetaProfile()?.allowsOwnedPlainPreparation == true))
            return openOwnedPlainHandoff(ProviderId.KEETA,desired)
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
                if (!keetaMenu.profile.usesCurrentLayout && keetaMenu.menu(frame) && keetaBasket.footerBag(frame) == null)
                    frame = restoreKeetaHeader(frame)
                if (keetaBasket.emptyMenu(frame)) {
                    check(!handoff) { "سلة كيتا أصبحت فارغة؛ أعد تجهيزها." }
                    requireFirstBasketAdd(desired)
                    val preview = graph.catalog.value.previews.single { it.provider == ProviderId.KEETA }
                    val expectedUnit = preview.itemSubtotal!! / desired.items.single().quantity
                    openKeetaPreviewProduct(frame)
                    frame = awaitKeetaProduct()
                    check(keetaBasket.addTarget(frame, expectedUnit) != null) { "تغير سعر الوجبة أو خياراتها أو ملاحظتها؛ لم أضفها." }
                    // One add dispatch per run. A subsequent run sees the resulting basket and cannot duplicate it.
                    keetaBasketAction { keetaBasket.addTarget(it, expectedUnit) }
                    frame = await { keetaMenu.menu(it) && keetaBasket.footerBag(it) != null }
                }
                keetaBasketAction { keetaBasket.footerBag(it) }
                frame = await { keetaMenu.basketDrawer(it) }
            }
            var evidence = keetaBasket.evidence(frame) ?: error("سلة كيتا ليست وجبة المطعم المختار الواحدة المقروءة بالكامل؛ لم أغيرها.")
            observeBasketMutation(desired, evidence)
            val quantity = desired.items.single().quantity
            if (handoff) check(evidence.lines.single().quantity == quantity) { "تغيرت الكمية؛ أعد تجهيز السلة." }
            var changes = 0
            while (evidence.lines.single().quantity != quantity) {
                check(keetaMenu.profile.allowsOwnedPlainPreparation) { "إصدار تعديل الكمية غير مدعوم." }
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
                observeBasketMutation(desired, evidence)
            }
            check(graph.draft.value?.fingerprint == desired.fingerprint)
            rememberPrepared(desired, evidence, keetaMenu.VERSION)
            graph.catalog.value = graph.catalog.value.copy(preparedBasket = evidence,
                message = "تحققت سلة كيتا: ${desired.items.single().name}، الكمية $quantity. لم يُفتح الدفع ولم يُؤكد طلب.")
            if (!handoff && comparisonJob?.isActive == true) readFinalTotal(desired)
        }
    }

    private suspend fun keetaBasketAction(selector: (CatalogFrame) -> CatalogNode?) {
        currentCoroutineContext().ensureActive(); checkSession()
        val root = service.rootInActiveWindow ?: throw UserInterrupted("اختفت سلة كيتا؛ توقفت المقارنة.")
        try {
            check(provider == ProviderId.KEETA)
            requireProviderWindow(root)
            val frame = capture(root)
            val target = selector(frame) ?: error("تعذر إثبات زر السلة أو الكمية؛ لم أنفذ الإجراء.")
            check(target.label != "إتمام الدفع" && target.label != "تفريغ السلة")
            val mutation = target.label == "إضافة إلى السلة" ||
                keetaBasket.quantityTarget(frame, true) == target || keetaBasket.quantityTarget(frame, false) == target
            if (mutation) {
                recordBasketMutation()
                // Journal I/O can suspend; the original ownership frame must still be active.
                verifyActiveFrame(frame, root.windowId)
                currentCoroutineContext().ensureActive(); checkSession()
            }
            // One dispatch only, then await the exact resulting quantity. Never retry a mutation.
            check(performKeetaReadAction(root, target, AccessibilityNodeInfo.ACTION_CLICK)) { "تعذر تنفيذ إجراء السلة؛ لم أكرره." }
        } finally { @Suppress("DEPRECATION") root.recycle() }
    }

    private fun unsupportedKeetaNotes(frame:CatalogFrame):Boolean =
        KeetaUnsupportedNotesSheet.matches(frame,keetaMenu.restaurant,keetaMenu.title,keetaMenu.VERSION,keetaMenu.product(frame))

    private fun requireSupportedKeetaNotes(frame:CatalogFrame) {
        check(!unsupportedKeetaNotes(frame)) {KeetaUnsupportedNotesSheet.MESSAGE}
    }

    private suspend fun awaitKeetaProduct():CatalogFrame =
        await {keetaMenu.product(it) || unsupportedKeetaNotes(it)}.also(::requireSupportedKeetaNotes)

    private fun keetaReadable(frame: CatalogFrame): Boolean = keetaMenu.product(frame) ||
        keetaMenu.menu(frame) || keetaMenu.basketDrawer(frame) ||
        KeetaPromotionEvidence.couponSheet(frame) || KeetaPromotionEvidence.terms(frame) ||
        KeetaPromotionEvidence.emptySheet(frame) || keetaMenu.searchCandidate(frame) != null

    /** Uses observed home/search controls and the exact review-header back arrow. */
    private suspend fun openKeetaRequest(): CatalogFrame {
        val version = graph.installations.value.single { it.provider == ProviderId.KEETA }.version.orEmpty()
        val ready:(CatalogFrame)->Boolean = {keetaReadable(it) || unsupportedKeetaNotes(it) || keetaMenu.homeSearch(it)!=null ||
            keetaMenu.searchInput(it)!=null || keetaMenu.editSearchQuery(it)!=null ||
            ReviewNavigationGuard.keetaReviewBackTarget(it,version)!=null}
        var frame = try {await(ready)} catch(timeout:TimeoutCancellationException) {
            currentCoroutineContext().ensureActive();checkSession()
            val root=service.rootInActiveWindow ?: throw timeout
            try {
                requireProviderWindow(root)
                val foreign=capture(root)
                check(KeetaForeignMenuRecovery.permits(foreign,version)) {"شاشة كيتا الحالية ليست قائمة طعام معروفة للعودة الآمنة؛ توقفت."}
                val entry=android.content.ComponentName(provider.packageName,"com.sankuai.sailor.shell.SplashActivity")
                @Suppress("DEPRECATION")
                val activity=service.packageManager.getActivityInfo(entry,0)
                check(activity.enabled && activity.exported)
                verifyActiveFrame(foreign,root.windowId);checkSession()
                // Observed installed entry, not a guessed deep link. One launcher recovery only;
                // it changes no basket and never presses an unknown screen control.
                targetSeen=false
                service.startActivity(Intent().setComponent(entry).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } finally {@Suppress("DEPRECATION") root.recycle()}
            await(ready)
        }
        if (ReviewNavigationGuard.keetaReviewBackTarget(frame, version) != null) {
            leaveKeetaPreviousReview(frame, version)
            frame = await { keetaReadable(it) || unsupportedKeetaNotes(it) || keetaMenu.homeSearch(it) != null ||
                keetaMenu.searchInput(it) != null || keetaMenu.editSearchQuery(it) != null }
        }
        requireSupportedKeetaNotes(frame)
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

    /** Leaves only the observed review header, without touching cart contents or financial controls. */
    private suspend fun leaveKeetaPreviousReview(previous: CatalogFrame, version: String) {
        currentCoroutineContext().ensureActive(); checkSession()
        check(provider == ProviderId.KEETA)
        val root = service.rootInActiveWindow ?: throw ScreenChangedBeforeAction()
        try {
            requireProviderWindow(root)
            val fresh = capture(root)
            if (fresh != previous) throw ScreenChangedBeforeAction()
            val target = ReviewNavigationGuard.keetaReviewBackTarget(fresh, version)
                ?: error("لم أتعرف على رجوع آمن من مراجعة كيتا.")
            val node = locateKeetaNode(root, target) ?: throw ScreenChangedBeforeAction()
            try {
                val screen = Rect().also(root::getBoundsInScreen)
                val arrow = Rect().also(node::getBoundsInScreen)
                check(arrow.top >= screen.top && arrow.bottom <= screen.top + screen.height()/4) {
                    "زر الرجوع خارج ترويسة المراجعة؛ توقفت العملية."
                }
            } finally { node.recycle() }
            check(performKeetaReadAction(root, target, AccessibilityNodeInfo.ACTION_CLICK)) {
                "تعذر الرجوع من مراجعة كيتا؛ لم أكرر النقر."
            }
        } finally { root.recycle() }
    }

    private suspend fun keetaSearchAction(previous: CatalogFrame, action: KeetaSearchAction) {
        KeetaSearchReplan.run(previous,
            observe = { await { keetaMenu.searchTarget(it, action) != null } }) { planned, dispatch ->
            try {
                lastStats.put("keetaSearchAction", action.name).put("keetaSearchDispatched", false)
                currentCoroutineContext().ensureActive(); checkSession()
                val root = service.rootInActiveWindow ?: throw UserInterrupted("اختفت شاشة البحث في كيتا؛ توقفت المقارنة.")
                try {
                    check(provider == ProviderId.KEETA)
                    requireProviderWindow(root)
                    val fresh = capture(root)
                    if (fresh != planned) throw ScreenChangedBeforeAction()
                    val target = keetaMenu.searchTarget(fresh, action) ?: error("تعذر التحقق من عنصر البحث في كيتا.")
                    @Suppress("DEPRECATION")
                    val version = service.packageManager.getPackageInfo(provider.packageName, 0).versionName
                    check(SafetyGuard().allowsKeetaSearch(action, keetaMenu, fresh, target, root.packageName?.toString(),
                        version, graph.settings.value.catalogConsent, currentCoroutineContext().isActive))
                    val node = locateKeetaNode(root, target) ?: error("اختفى عنصر البحث.")
                    try {
                        if (!(node.refresh() && node.isVisibleToUser && node.isEnabled && !node.isPassword &&
                            !(android.os.Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive) &&
                            node.isEditable == (action == KeetaSearchAction.ENTER_QUERY) && capture(root) == fresh)) throw ScreenChangedBeforeAction()
                        verifyActiveFrame(fresh, root.windowId)
                        currentCoroutineContext().ensureActive(); checkSession()
                        // Mark before the native call: false or an exception after dispatch is terminal.
                        dispatch.mark()
                        lastStats.put("keetaSearchDispatched", true)
                        val success = if (action == KeetaSearchAction.ENTER_QUERY) node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, keetaMenu.restaurant)
                        }) else node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        check(success) { "لم تنفذ كيتا إجراء البحث؛ توقفت دون تكراره." }
                    } finally { @Suppress("DEPRECATION") node.recycle() }
                } finally { @Suppress("DEPRECATION") root.recycle() }
                KeetaSearchReplan.Result.Dispatched
            } catch (changed: ScreenChangedBeforeAction) {
                if (dispatch.attempted) throw changed
                KeetaSearchReplan.Result.ChangedBeforeDispatch(changed)
            }
        }
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
            frame = awaitKeetaProduct()
        }
        if (keetaMenu.product(frame) && keetaMenu.productBody(frame) == null) {
            if (BuildConfig.VERSION_NAME.endsWith("-device-tools")) withContext(Dispatchers.IO) {
                // capture() has already removed personal and unrelated text. Local debug APK only.
                service.filesDir.resolve("live-keeta-product.json").writeText(com.google.gson.Gson().toJson(frame))
            }
            // Unknown modifier coverage permits closing only. Read a fully parsed matching
            // existing basket instead; do not add a potentially incomplete product.
            closeKeetaReadSheet(frame)
            frame = await { keetaMenu.menu(it) }
            check(keetaBasket.footerBag(frame) != null) { "خيارات كيتا تحتاج دعمًا، ولا توجد سلة مطابقة قابلة للتحقق؛ لم أضف وجبة." }
            keetaBasketAction { keetaBasket.footerBag(it) }
            frame = await { keetaMenu.basketDrawer(it) }
            val basket = keetaBasket.evidence(frame) ?: error("تعذرت قراءة سلة كيتا بالكامل.")
            val line = basket.lines.singleOrNull() ?: error("سلة كيتا ليست الطلب المحدد.")
            check(basket.complete && line.name == desired.items.single().name && line.choices.isEmpty() &&
                line.quantity == desired.items.single().quantity && line.lineAmount != null) {
                "سلة كيتا مختلفة عن طلب المقارنة؛ لم أعدلها."
            }
            return MenuCostPreview(ProviderId.KEETA,desired.fingerprint,line.lineAmount,emptyList(),
                System.currentTimeMillis(),keetaMenu.VERSION,"مجموع الأصناف من السلة المطابقة؛ الإجمالي النهائي يُقرأ من المراجعة.")
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
                frame=awaitKeetaProduct()
                val unit=keetaMenu.unitPrice(frame) ?: error("السعر غير مقروء")
                MenuCostPreview(ProviderId.KEETA,desired.fingerprint,Math.multiplyExact(unit,desired.items.single().quantity.toLong()),
                    offers,System.currentTimeMillis(),keetaMenu.VERSION,
                    "تعذر التحقق من القسيمة؛ لم أحسب خصمًا غير مقروء.",rules=rules)
            }
        } catch(cancelled:CancellationException) { if(cancelled !is TimeoutCancellationException) throw cancelled }
        catch(stopped:CatalogStoppedException) { throw stopped }
        catch(interrupted:UserInterrupted) { throw interrupted }
        catch(changed:ScreenChangedBeforeAction) { throw changed }
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
            val root=service.rootInActiveWindow ?: throw UserInterrupted("اختفت قائمة المطعم المختار؛ توقفت المقارنة.")
            val plan:HeaderSwipe
            val expectedWindowId=root.windowId
            try {
                check(provider==ProviderId.KEETA)
                requireProviderWindow(root)
                val current=capture(root)
                if(current!=frame) throw ScreenChangedBeforeAction()
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
                checkSession()
                verifyActiveFrame(frame,expectedWindowId)
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
        val root = service.rootInActiveWindow ?: throw UserInterrupted("اختفى عرض كيتا؛ توقفت المقارنة.")
        try {
            check(provider == ProviderId.KEETA)
            requireProviderWindow(root)
            val frame = capture(root)
            if (frame != previous) throw ScreenChangedBeforeAction()
            check(previous.complete)
            val target = (if (terms) KeetaPromotionEvidence.termsTarget(frame) else KeetaPromotionEvidence.couponTarget(frame, keetaMenu))
                ?: error("تعذر تحديد تفاصيل عرض كيتا.")
            check(performKeetaReadAction(root, target, AccessibilityNodeInfo.ACTION_CLICK)) { "تعذر فتح شروط العرض." }
        } finally { @Suppress("DEPRECATION") root.recycle() }
    }

    private suspend fun scrollKeetaHeader(previous: CatalogFrame): Boolean {
        currentCoroutineContext().ensureActive(); checkSession()
        val root = service.rootInActiveWindow ?: throw UserInterrupted("اختفت قائمة كيتا؛ توقفت المقارنة.")
        try {
            check(provider == ProviderId.KEETA)
            requireProviderWindow(root)
            val frame = capture(root)
            if (frame != previous) throw ScreenChangedBeforeAction()
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
        check(provider == ProviderId.KEETA)
        requireProviderWindow(root)
        val expected = capture(root)
        check(expected.complete && expected.nodes.singleOrNull { it.index == target.index } == target)
        check(target.label !in setOf("إتمام الدفع", "تفريغ السلة", "تأكيد الطلب"))
        val node = locateKeetaNode(root,target) ?: error("تعذر تحديد عنصر القراءة في كيتا.")
        return try {
            if (!(node.refresh() && node.isVisibleToUser && node.isEnabled && !node.isEditable && !node.isPassword &&
                !(android.os.Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive) &&
                node.className?.toString() == target.className && node.viewIdResourceName.orEmpty() == target.id &&
                keetaMenu.functionalLabel((node.text ?: node.contentDescription)?.toString().orEmpty()) == target.label)) throw ScreenChangedBeforeAction()
            check(if (action == AccessibilityNodeInfo.ACTION_CLICK) node.isClickable else node.isScrollable)
            verifyActiveFrame(expected, root.windowId)
            checkSession()
            node.performAction(action)
        } finally { @Suppress("DEPRECATION") node.recycle() }
    }

    /** Back from an evidenced basket preview only. No checkout control is selected. */
    private suspend fun closeKeetaReadSheet(previous: CatalogFrame) {
        currentCoroutineContext().ensureActive(); checkSession()
        var drawerReturn: KeetaOwnedDrawerReturn? = null
        val root = service.rootInActiveWindow ?: throw UserInterrupted("اختفت معاينة كيتا؛ توقفت المقارنة.")
        try {
            check(provider == ProviderId.KEETA)
            requireProviderWindow(root)
            val frame = capture(root)
            if (frame != previous) throw ScreenChangedBeforeAction()
            check(previous.complete && (keetaMenu.basketDrawer(frame) || keetaMenu.product(frame) ||
                KeetaPromotionEvidence.couponSheet(frame) || KeetaPromotionEvidence.terms(frame) || KeetaPromotionEvidence.emptySheet(frame)))
            if (KeetaPromotionEvidence.couponSheet(frame) || KeetaPromotionEvidence.terms(frame) || KeetaPromotionEvidence.emptySheet(frame)) {
                val target = KeetaPromotionEvidence.closeTarget(frame) ?: error("تعذر تحديد زر إغلاق شروط العرض.")
                check(performKeetaReadAction(root, target, AccessibilityNodeInfo.ACTION_CLICK)) { "تعذر إغلاق شروط العرض." }
            } else if (keetaMenu.basketDrawer(frame)) {
                val target = keetaBasket.closeDrawer(frame)
                if (target != null) {
                    check(performKeetaReadAction(root, target, AccessibilityNodeInfo.ACTION_CLICK)) { "تعذر إغلاق معاينة السلة." }
                } else {
                    val desired = graph.draft.value ?: error("اختفى الطلب؛ لم أخرج من السلة.")
                    val evidence = keetaBasket.evidence(frame) ?: error("لم تثبت سلة كاملة للخروج منها.")
                    @Suppress("DEPRECATION")
                    val version = service.packageManager.getPackageInfo(provider.packageName, 0).versionName
                    val command = KeetaOwnedDrawerReturn.create(frame, evidence, desired, version, System.currentTimeMillis())
                        ?: error("تعذر تحديد إغلاق معاينة السلة.")
                    verifyActiveFrame(frame, root.windowId)
                    currentCoroutineContext().ensureActive(); checkSession()
                    check(command.consumeBack(frame, graph.draft.value ?: desired, version, System.currentTimeMillis()))
                    check(service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) { "لم يكتمل الرجوع من السلة؛ لم أكرره." }
                    drawerReturn = command
                }
            } else {
                val target = keetaMenu.closeProduct(frame) ?: error("تعذر تحديد إغلاق الوجبة.")
                check(performKeetaReadAction(root, target, AccessibilityNodeInfo.ACTION_CLICK)) { "تعذر إغلاق معاينة الوجبة." }
            }
        } finally { @Suppress("DEPRECATION") root.recycle() }
        drawerReturn?.let { finishKeetaOwnedDrawerReturn(it) }
    }

    private suspend fun finishKeetaOwnedDrawerReturn(command: KeetaOwnedDrawerReturn) {
        fun desired() = graph.draft.value ?: error("اختفى الطلب أثناء الرجوع من السلة.")
        fun matchesMenu(frame: CatalogFrame) = command.menuMatches(frame, desired(), keetaMenu.VERSION, System.currentTimeMillis())
        var frame = await { matchesMenu(it) || command.searchTarget(it, desired(), keetaMenu.VERSION, System.currentTimeMillis()) != null }
        if (!matchesMenu(frame)) {
            val root = service.rootInActiveWindow ?: throw ScreenChangedBeforeAction()
            try {
                requireProviderWindow(root)
                if (capture(root) != frame) throw ScreenChangedBeforeAction()
                @Suppress("DEPRECATION")
                val version = service.packageManager.getPackageInfo(provider.packageName, 0).versionName
                val target = command.searchTarget(frame, desired(), version, System.currentTimeMillis())
                    ?: error("لم تظهر نتيجة البحث المطابقة بعد الرجوع؛ لم أختر مطعمًا آخر.")
                verifyActiveFrame(frame, root.windowId)
                currentCoroutineContext().ensureActive(); checkSession()
                check(command.consumeReopen(frame, target, desired(), version, System.currentTimeMillis()))
                check(performKeetaReadAction(root, target, AccessibilityNodeInfo.ACTION_CLICK)) { "لم تفتح قائمة المطعم؛ لم أكرر الإجراء." }
            } finally { @Suppress("DEPRECATION") root.recycle() }
            frame = await { matchesMenu(it) }
        }
        @Suppress("DEPRECATION")
        val version = service.packageManager.getPackageInfo(provider.packageName, 0).versionName
        currentCoroutineContext().ensureActive(); checkSession()
        check(command.finish(frame, desired(), version, System.currentTimeMillis())) {
            "تغير المطعم أو عدد السلة أثناء الرجوع؛ توقفت قبل أي إجراء آخر."
        }
    }

    /** Fallback only after a rejected structured scroll on the same complete known food menu. */
    private suspend fun scrollKeetaMenuGesture(previous:CatalogFrame):Boolean {
        checkSession()
        val root=service.rootInActiveWindow ?: throw UserInterrupted("اختفت قائمة الطعام؛ توقفت.")
        val plan:HeaderSwipe
        val windowId=root.windowId
        val quantity=keetaBasket.menuQuantity(previous)
        try {
            check(provider==ProviderId.KEETA);requireProviderWindow(root)
            val fresh=capture(root)
            if(fresh!=previous)throw ScreenChangedBeforeAction()
            check(fresh.complete && keetaMenu.menu(fresh) && fresh.nodes.none {it.editable})
            val list=fresh.nodes.single {it.scrollable && it.className=="androidx.recyclerview.widget.RecyclerView"}
            val node=locateKeetaNode(root,list) ?: throw ScreenChangedBeforeAction()
            val view=Rect();val window=Rect()
            try {
                check(node.refresh() && node.isVisibleToUser && node.isEnabled && node.isScrollable && !node.isPassword &&
                    !(android.os.Build.VERSION.SDK_INT>=34 && node.isAccessibilityDataSensitive))
                node.getBoundsInScreen(view);root.getBoundsInScreen(window)
            } finally {node.recycle()}
            val footerNodes=root.findAccessibilityNodeInfosByText("إتمام الدفع")
            val footerTop=try {footerNodes.filter {it.text?.toString()=="إتمام الدفع"}.minOfOrNull {
                val r=Rect();it.getBoundsInScreen(r);r.top
            }} finally {footerNodes.forEach {it.recycle()}}
            plan=KeetaMenuScroll.plan(HeaderViewport(view.left,view.top,view.right,view.bottom),
                HeaderViewport(window.left,window.top,window.right,window.bottom),footerTop)
                ?: error("مساحة قائمة الطعام غير متحققة؛ لم أنفذ إيماءة.")
        } finally {root.recycle()}
        val path=Path().apply {moveTo(plan.x.toFloat(),plan.fromY.toFloat());lineTo(plan.x.toFloat(),plan.toY.toFloat())}
        val gesture=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,450)).build()
        val done=withTimeout(3_000) {suspendCancellableCoroutine<Boolean> {continuation->
            checkSession();verifyActiveFrame(previous,windowId)
            val accepted=service.dispatchGesture(gesture,object:AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription:GestureDescription?) {if(continuation.isActive)continuation.resume(true)}
                override fun onCancelled(gestureDescription:GestureDescription?) {if(continuation.isActive)continuation.resume(false)}
            },null)
            if(!accepted && continuation.isActive)continuation.resume(false)
        }}
        check(done) {"لم تكتمل إيماءة قائمة الطعام؛ توقفت."}
        val after=await {keetaMenu.menu(it) && it.nodes!=previous.nodes}
        check(quantity==null || keetaBasket.menuQuantity(after)==quantity) {"تغير عدد السلة أثناء قراءة القائمة؛ توقفت."}
        return true
    }

    private suspend fun openKeetaPreviewProduct(previous: CatalogFrame) {
        var frame = previous
        var previousBody: List<CatalogNode>? = null
        // Kudo's observed side dishes begin beyond the first 20 menu viewports.
        // The finite scan still stops on an unchanged/end/unknown screen and never adds items.
        repeat(80) {
            currentCoroutineContext().ensureActive(); checkSession()
            val root = service.rootInActiveWindow ?: throw UserInterrupted("اختفت قائمة كيتا؛ توقفت المقارنة.")
            var rejectedScroll=false
            try {
                check(provider == ProviderId.KEETA)
                requireProviderWindow(root)
                val fresh = capture(root)
                if (frame != fresh) throw ScreenChangedBeforeAction()
                check(fresh.complete) { "قراءة القائمة غير مكتملة؛ لم أفتح وجبة مختلفة." }
                val target = keetaMenu.productTarget(fresh)
                if (target != null) {
                    check(performKeetaReadAction(root, target, AccessibilityNodeInfo.ACTION_CLICK))
                    return
                }
                check(fresh.nodes.none { it.id == "com.sankuai.sailor.afooddelivery:id/product_name" && it.label == keetaMenu.title }) {
                    "الصنف المطابق ظاهر لكن عنصر فتحه غير مدعوم؛ لم أنقر عنصرًا آخر."
                }
                check(keetaMenu.menu(fresh) && fresh.nodes != previousBody) { "الوجبة المطابقة غير ظاهرة في قائمة كيتا." }
                previousBody = fresh.nodes
                val list = fresh.nodes.single { it.scrollable && it.className == "androidx.recyclerview.widget.RecyclerView" }
                rejectedScroll=!performKeetaReadAction(root, list, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            } finally { @Suppress("DEPRECATION") root.recycle() }
            if(rejectedScroll) {
                // A provider can return false after a real scroll. Observe before considering fallback.
                delay(350)
                val checkRoot=service.rootInActiveWindow ?: throw UserInterrupted("اختفت قائمة الطعام؛ توقفت.")
                val current=try {requireProviderWindow(checkRoot);capture(checkRoot)} finally {checkRoot.recycle()}
                check(keetaMenu.menu(current)) {"تغيرت شاشة القائمة؛ لم أنفذ إيماءة."}
                if(current==frame)scrollKeetaMenuGesture(frame)
            }
            frame = await { keetaMenu.menu(it) && it.nodes != previousBody }
        }
        error("لم أجد الوجبة المطابقة ضمن مهلة التصفح؛ لم أستبدلها.")
    }

    // Browsing and local selection never dispatch provider actions.
    fun browseRestaurants(): Boolean = graph.showLocalRestaurants()
    fun search(text: String): Boolean = graph.showLocalRestaurants(text)
    fun openMenu(restaurant: LiveRestaurant): Boolean = graph.showLocalMenu(restaurant)

    private fun matchesSelectedRestaurant(): Boolean {
        if (ComparisonRequestSelection.matches(graph.catalog.value.selectedRestaurant, graph.draft.value)) return true
        graph.catalog.value = graph.catalog.value.copy(error = "اختر وجبة من المطعم المحدد وأضفها إلى طلبك أولًا؛ لم أشغّل مقارنة الطلب السابق.")
        return false
    }

    fun openProduct(restaurant: LiveRestaurant, requested: LiveMenuItem, returnToOctopus: Boolean = true): Boolean =
        graph.showLocalProduct(restaurant, requested)

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
        var landing = await { HungerStationCatalog.screen(it) in setOf(CatalogScreen.MENU, CatalogScreen.RESTAURANT_NOTICE) ||
            HungerMenuSearchScreen.closeTarget(it)!=null ||
            graph.draft.value?.items?.singleOrNull()?.name?.let {title -> HungerStationExistingVariant.read(it,title)!=null}==true }
        HungerMenuSearchScreen.closeTarget(landing)?.let { close ->
            act(landing,CatalogAction.RETURN_TO_CATALOG,{it==close})
            landing=await {HungerStationCatalog.screen(it) in setOf(CatalogScreen.MENU,CatalogScreen.RESTAURANT_NOTICE)}
        }
        graph.draft.value?.items?.singleOrNull()?.name?.let {title -> HungerStationExistingVariant.read(landing,title)?.let {sheet ->
            act(landing,CatalogAction.CLOSE_HUNGER_VARIANT,{it==sheet.close},variantTitle=title)
            landing=await{HungerStationCatalog.screen(it) in setOf(CatalogScreen.MENU,CatalogScreen.RESTAURANT_NOTICE)}
        } }
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
        val desired = graph.draft.value ?: return false
        if (!RidanCartProgram.supports(details, selection, System.currentTimeMillis()) ||
            !PreviewEvidence.supports(desired) || desired.restaurant != details.restaurant ||
            desired.items.single().name != details.product.title || desired.items.single().quantity != desiredQuantity) return false
        return begin(operation = CatalogOperation.BUILD_CART) {
            prepareOwnedHungerSteps(desired, comparisonJob?.isActive == true, PlainPreparationProgress())
        }
    }

    /** No actions on the review except scrolling. All order/payment confirmations remain unreachable. */
    private fun isRecognizedReview(root: AccessibilityNodeInfo): Boolean {
        val bounds = Rect().also(root::getBoundsInScreen)
        var recognized = false
        var excluded = false
        var count = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 35 || count++ > 2000 || !node.isVisibleToUser || node.isPassword || node.isEditable ||
                privateId(node.viewIdResourceName.orEmpty()) ||
                (android.os.Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive)) return
            val id = node.viewIdResourceName.orEmpty()
            if (id in setOf("FlutterCart_title", "menuBackButton", "pdpCloseButton")) excluded = true
            val rect = Rect().also(node::getBoundsInScreen)
            if (rect.top < bounds.top + bounds.height()/4) {
                val value = (node.text ?: node.contentDescription)?.toString()?.trim()
                if (value in setOf("الدفع", "مراجعة الطلب", "تأكيد الطلب", "Checkout", "Order review") ||
                    (root.packageName?.toString() == ProviderId.HUNGERSTATION.packageName &&
                        ReviewNavigationGuard.hungerReviewHeading(value, graph.draft.value?.restaurant)) ||
                    (root.packageName?.toString() == "com.sankuai.sailor.afooddelivery" && value == "إتمام الدفع")) recognized = true
            }
            for (i in 0 until node.childCount.coerceAtMost(200)) {
                val child = node.getChild(i) ?: continue
                try { walk(child, depth + 1) } finally { @Suppress("DEPRECATION") child.recycle() }
            }
        }
        walk(root,0)
        return recognized && !excluded
    }

    private data class ReviewStructure(val frame: CatalogFrame, val scrollNodes: Map<Int, AccessibilityNodeInfo>) {
        fun recycle() { scrollNodes.values.forEach { @Suppress("DEPRECATION") it.recycle() } }
    }

    /** Structure only: no review labels, address values or financial controls enter the catalog. */
    private fun reviewStructure(root: AccessibilityNodeInfo): ReviewStructure {
        val nodes = mutableListOf<CatalogNode>()
        val scrollNodes = mutableMapOf<Int, AccessibilityNodeInfo>()
        var complete = true
        fun walk(node: AccessibilityNodeInfo, parent: Int, depth: Int) {
            if (depth > 30 || nodes.size >= 2500) { complete = false; return }
            if (!node.isVisibleToUser || node.isEditable || node.isPassword ||
                InspectionRedactor.privateSubtree(node.viewIdResourceName.orEmpty()) ||
                (android.os.Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive)) return
            val index = nodes.size
            nodes += CatalogNode(index, parent, node.viewIdResourceName.orEmpty(), "",
                node.className?.toString().orEmpty(), node.isClickable, false, node.isScrollable, node.isEnabled)
            if (node.isScrollable) scrollNodes[index] = AccessibilityNodeInfo.obtain(node)
            if (node.childCount > 200) complete = false
            for (i in 0 until node.childCount.coerceAtMost(200)) {
                val child = node.getChild(i) ?: continue
                try { walk(child, index, depth + 1) } finally { @Suppress("DEPRECATION") child.recycle() }
            }
        }
        walk(root, -1, 0)
        return ReviewStructure(CatalogFrame(nodes, complete), scrollNodes)
    }

    private suspend fun readFinalTotal(desired: CanonicalCart,verifiedSource:CatalogFrame?=null) {
        val saved = graph.catalog.value.preparedBaskets.forRequest(desired, provider) ?: error("لم تُتحقق السلة قبل قراءة الإجمالي.")
        val version = graph.installations.value.single { it.provider == provider }.version ?: error("إصدار المنصة غير معروف.")
        @Suppress("DEPRECATION")
        val observedVersion=service.packageManager.getPackageInfo(provider.packageName,0).versionName
        check(observedVersion==version) {"تغير إصدار المنصة؛ لم أفتح مراجعة السلة."}
        val directDrawer=provider==ProviderId.KEETA && desired.items.size==1 &&
            KeetaVersionProfile.resolve(version)?.allowsDirectDrawerReview==true
        val admission=if(desired.items.size>1 || directDrawer) app.octopus.local.safety.PreparedReviewAdmission.create(provider,desired,saved,
            verifiedSource ?: error("لم تُربط السلة بشاشتها المتحققة."),version,System.currentTimeMillis())
                ?: error("انتهت صلاحية إثبات السلة؛ لم أفتح المراجعة.") else null
        if (provider == ProviderId.KEETA && !directDrawer) {
            val drawer = await { keetaMenu.basketDrawer(it) || keetaMenu.menu(it) }
            if (keetaMenu.basketDrawer(drawer)) closeKeetaReadSheet(drawer)
            await { keetaMenu.menu(it) }
        }
        check(saved.evidence.restaurant == desired.restaurant)
        checkSession()
        val root = service.rootInActiveWindow ?: throw UserInterrupted("اختفت السلة؛ توقفت المقارنة.")
        try {
            requireProviderWindow(root)
            val frame = capture(root)
            val target = ReviewNavigationGuard.target(provider, frame, desired, version,admission)
                ?: error("لم أتعرف على انتقال مراجعة معروف من السلة؛ لم أنقر أي زر دفع.")
            val node = locateKeetaNode(root, target) ?: error("اختفى زر المراجعة.")
            try {
                if (!(node.refresh() && node.isVisibleToUser && node.isEnabled && node.isClickable && !node.isPassword && !node.isEditable &&
                    (android.os.Build.VERSION.SDK_INT < 34 || !node.isAccessibilityDataSensitive))) throw ScreenChangedBeforeAction()
                checkSession()
                if (capture(root) != frame || ReviewNavigationGuard.target(provider, frame, desired, version,admission) != target)
                    throw ScreenChangedBeforeAction()
                verifyActiveFrame(frame, root.windowId)
                currentCoroutineContext().ensureActive(); checkSession()
                if(admission!=null) check(admission.consume(frame,target,provider,desired,version,System.currentTimeMillis())) {
                    "لم يعد تصريح مراجعة السلة صالحًا؛ لم أفتحها."
                }
                check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "لم تفتح مراجعة الطلب." }
            } finally { @Suppress("DEPRECATION") node.recycle() }
        } finally { @Suppress("DEPRECATION") root.recycle() }
        phase = "قراءة إجمالي مراجعة الطلب في ${provider.title}"
        val reader = SnapshotReader()
        val extractor = app.octopus.local.pricing.QuoteExtractor()
        var stable: QuoteCandidate? = null
        var stableSince = 0L
        val start = SystemClock.elapsedRealtime()
        var lastScroll = start
        var scrolls = 0
        var lastReviewDiagnostic: String? = null
        var recommendationsSkipped = false
        while (SystemClock.elapsedRealtime() - start < 20_000) {
            currentCoroutineContext().ensureActive(); checkSession()
            val review = service.rootInActiveWindow
            if (review != null) try {
                requireProviderWindow(review)
                if (provider == ProviderId.HUNGERSTATION && !recommendationsSkipped) {
                    val frame = capture(review)
                    val next = ReviewNavigationGuard.hungerRecommendationsTarget(frame, version)
                    if (next != null) {
                        lastStats.put("hungerRecommendationsVariant", if (frame.nodes.any {
                            it.label == "لا تفوت الفرصة!\nفرصتك لإضافة المزيد لطلبك" }) "NEW" else "OLD")
                            .put("hungerRecommendationsDispatched", false)
                        val node = locateKeetaNode(review, next) ?: throw ScreenChangedBeforeAction()
                        try {
                            if (!node.refresh() || !node.isVisibleToUser || !node.isEnabled || !node.isClickable ||
                                node.isPassword || node.isEditable ||
                                (android.os.Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive)) throw ScreenChangedBeforeAction()
                            verifyActiveFrame(frame, review.windowId)
                            checkSession(); currentCoroutineContext().ensureActive()
                            recommendationsSkipped = true
                            lastStats.put("hungerRecommendationsAttemptedAt", System.currentTimeMillis())
                                .put("hungerRecommendationsDispatched", true)
                            check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "تعذر تجاوز توصيات الأصناف إلى المراجعة." }
                        } finally { @Suppress("DEPRECATION") node.recycle() }
                        delay(250)
                        continue
                    }
                }
                val snapshot = reader.read(review)
                if (snapshot.sensitiveScreen || snapshot.accessRestriction != null)
                    throw UserInterrupted("ظهرت شاشة حماية أو بيانات محمية؛ توقفت المقارنة.")
                val quote = extractor.extract(snapshot)
                val recognized = isRecognizedReview(review)
                if (BuildConfig.VERSION_NAME.endsWith("-device-tools")) {
                    val key="${quote.fingerprint}:$recognized:${snapshot.visibleNodeCount}:${snapshot.sensitiveScreen}"
                    if(key!=lastReviewDiagnostic) {
                        lastReviewDiagnostic=key
                        val structure=inspectionReader.read(review,InspectionRequest(InspectionStage.REVIEW,"",""))
                        val diagnostic=JSONObject().put("provider",provider.name).put("recognized",recognized)
                            .put("sensitive",snapshot.sensitiveScreen).put("truncated",snapshot.truncated)
                            .put("fields",JSONObject(quote.fields.mapKeys {it.key.name})).put("nodes",JSONArray(structure.nodes))
                        // Store only presence of fixed public headings, never their field values.
                        val headings = listOf("طريقة الدفع", "عنوان التوصيل", "ملخص الطلب", "الإجمالي", "المجموع",
                            "تأكيد الطلب", "وقت التوصيل", "اختر عنوان", "أضف عنوان", "لا يوجد اتصال بالإنترنت",
                            "استلام", "توصيل", "اختيار", "حدد")
                        diagnostic.put("knownHeadings", JSONArray(headings.filter { heading ->
                            val matches = review.findAccessibilityNodeInfosByText(heading)
                            try { matches.isNotEmpty() } finally { matches.forEach { it.recycle() } }
                        }))
                        withContext(Dispatchers.IO) {service.filesDir.resolve("live-review-${provider.name.lowercase()}.json")
                            .writeText(diagnostic.toString(),Charsets.UTF_8)}
                    }
                }
                if (quote.total != null && !snapshot.truncated && recognized) {
                    if (stable?.fingerprint != quote.fingerprint) { stable = quote; stableSince = SystemClock.elapsedRealtime() }
                    else if (SystemClock.elapsedRealtime() - stableSince >= 1_000) {
                        checkSession()
                        graph.catalog.value = graph.catalog.value.copy(finalTotals = graph.catalog.value.finalTotals +
                            (provider to FinalTotalReading(provider, version, desired.fingerprint, quote.fields, System.currentTimeMillis())))
                        return
                    }
                } else {
                    stable = null
                    if (recognized && scrolls < 5 && SystemClock.elapsedRealtime() - lastScroll >= 2_000) {
                        val structure = reviewStructure(review)
                        try {
                            val selected = ReviewScrollSelector.target(structure.frame, recognized)
                            if (selected != null) {
                                checkSession()
                                val current = service.rootInActiveWindow
                                    ?: throw UserInterrupted("اختفت المراجعة قبل التمرير؛ توقفت المقارنة.")
                                try {
                                    requireProviderWindow(current)
                                    if (current.windowId != review.windowId || !isRecognizedReview(current)) throw ScreenChangedBeforeAction()
                                    run {
                                        val fresh = reviewStructure(current)
                                        try {
                                            val sameTarget = ReviewScrollSelector.target(fresh.frame, true)
                                            val node = fresh.scrollNodes[sameTarget?.index]
                                            if (fresh.frame != structure.frame || sameTarget != selected) throw ScreenChangedBeforeAction()
                                            if (node != null &&
                                                node.refresh() && node.isVisibleToUser && node.isEnabled && node.isScrollable &&
                                                !node.isPassword && !node.isEditable &&
                                                (android.os.Build.VERSION.SDK_INT < 34 || !node.isAccessibilityDataSensitive)) {
                                                checkSession()
                                                if (node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) scrolls++
                                                else scrolls = 5
                                            }
                                        } finally { fresh.recycle() }
                                    }
                                } finally { @Suppress("DEPRECATION") current.recycle() }
                            } else scrolls = 5
                        } finally { structure.recycle() }
                        lastScroll = SystemClock.elapsedRealtime()
                    }
                }
            } catch (_: ScreenChangedBeforeAction) {
                // The guard rejected an undispatched navigation/scroll action.
                // Re-read within the same bounded review deadline, never reuse its node.
                stable = null
            } finally { @Suppress("DEPRECATION") review.recycle() }
            delay(250)
        }
        error("السلة متحققة، لكن الإجمالي الصريح لم يظهر مستقرًا في المراجعة؛ لم أقدّر سعرًا ولم أؤكد طلبًا.")
    }

    private fun openOwnedPlainHandoff(id:ProviderId,desired:CanonicalCart):Boolean {
        if(active || graph.session.value.active || !matchesSelectedRestaurant() ||
            graph.catalog.value.preparedBaskets.forRequest(desired,id)==null)return false
        val prior=graph.catalog.value.finalTotals[id]
        return begin(returnOnSuccess=false,operation=CatalogOperation.OPEN_PREPARED_CART) {
            graph.catalog.value=graph.catalog.value.copy(finalTotals=graph.catalog.value.finalTotals-id)
            val progress=PlainPreparationProgress()
            if(id==ProviderId.HUNGERSTATION)prepareOwnedHungerSteps(desired,true,progress,readOnly=true)
            else prepareOwnedKeetaSteps(desired,true,progress,readOnly=true)
            val current=graph.catalog.value.finalTotals[id] ?: error("لم يكتمل التحقق من الإجمالي قبل التسليم.")
            val changed=prior!=null && prior.cartFingerprint==desired.fingerprint && prior.total!=current.total
            graph.catalog.value=graph.catalog.value.copy(message=if(changed)
                "تغير إجمالي ${id.title} منذ المقارنة؛ راجع القراءة الجديدة قبل الاختيار. لم يُؤكد طلب أو دفع."
                else "تحققت أصناف السلة وكمياتها وإجمالي مراجعتها مجددًا. التحكم الآن لك؛ تحقق من العنوان والفرع قبل الدفع.")
            if(changed)returnToApp()
        }
    }

    fun openPreparedRidanBasket(): Boolean {
        if (!matchesSelectedRestaurant()) return false
        val draft = graph.draft.value ?: return false
        if(PreviewEvidence.supports(draft) || PlainComparisonSupport.existingTwoRow(draft))
            return openOwnedPlainHandoff(ProviderId.HUNGERSTATION,draft)
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
        timeoutMillis: Long = 150_000, block: suspend () -> Unit): Boolean {
        require(timeoutMillis in 1_000..600_000 && (operation in setOf(CatalogOperation.COMPARE_MENU,CatalogOperation.BUILD_CART) || timeoutMillis == 150_000L))
        if (graph.catalog.value.busy || job?.isActive == true || manualJob?.isActive == true || graph.session.value.active) { graph.catalog.value=graph.catalog.value.copy(error="توجد جلسة أخرى نشطة؛ أوقفها قبل بدء المقارنة."); return false }
        if (comparisonJob?.isActive != true && operation !in setOf(CatalogOperation.OPEN_PREPARED_CART, CatalogOperation.COMPARE_MENU)) { graph.catalog.value=graph.catalog.value.copy(error="لم تبدأ جلسة المقارنة المصرح بها."); return false }
        provider = ProviderId.HUNGERSTATION
        requestFingerprint = null
        requestAreaUrl = graph.settings.value.catalogArea?.url
        if (!graph.settings.value.catalogConsent || provider !in graph.settings.value.allowed) { graph.catalog.value=graph.catalog.value.copy(error="هنقرستيشن غير مفعّل ضمن التطبيقات المسموح بها للمقارنة."); return false }
        graph.refreshInstallations()
        val installed = graph.installations.value.singleOrNull { it.provider == provider && it.installed } ?: return false
        if (installed.version != HungerStationCatalog.TESTED_VERSION) {
            graph.catalog.value = graph.catalog.value.copy(error = "إصدار هنقرستيشن يحتاج تحققًا قبل تشغيل التنقل.")
            return false
        }
        targetSeen = false
        if (!showStop()) { graph.catalog.value=graph.catalog.value.copy(error="تعذر إظهار زر إيقاف المقارنة؛ أعد ربط خدمة الأخطبوط."); return false }
        val token = ++generation
        val evidence = SessionEvidenceBuffer().also { sessionEvidence = it }
        val stopState = CatalogStopState().also { catalogStop = it }
        this.operation = operation
        priorRecognizedScreen = null
        lastStats = JSONObject()
        observedMenuTitles.clear()
        lastKeetaFoodProduct=null
        val evidenceGeneration = graph.inspectionRecorder.generation()
        graph.catalog.value = graph.catalog.value.copy(busy = true, error = null, message = "جارٍ تنفيذ المقارنة والتحقق من طلبك…")
        stepInterrupted = null
        job = graph.scope.launch(start = CoroutineStart.LAZY) {
            var outcome = "COMPLETED"
            try { withTimeout(timeoutMillis) { block() }; if (returnOnSuccess && comparisonJob?.isActive != true) returnToApp() }
            catch (cancelled: CancellationException) {
                outcome = if (cancelled is TimeoutCancellationException) "TIMEOUT" else "STOPPED"
                if (cancelled is TimeoutCancellationException) {
                    val note = if (operation in setOf(CatalogOperation.BUILD_CART, CatalogOperation.OPEN_PREPARED_CART))
                        "توقفت الإجراءات؛ راجع السلة إن بدأ تجهيزها." else "توقفت القراءة دون إضافة وجبات أو تعديل السلة."
                    graph.catalog.value = graph.catalog.value.copy(error = "انتهت مهلة $phase. $note")
                    stepInterrupted = graph.catalog.value.error
                    comparisonJob?.cancel(CancellationException(stepInterrupted))
                    if (comparisonJob == null && !BuildConfig.VERSION_NAME.endsWith("-device-tools")) returnToApp()
                }
                throw cancelled
            } catch (failure: Exception) {
                val terminal = failure is UserInterrupted || failure is CatalogStoppedException || failure is ScreenChangedBeforeAction
                if (terminal) {
                    stepInterrupted = failure.message ?: "توقفت المقارنة بعد تغير الشاشة أو مقاطعة المستخدم."
                    comparisonJob?.cancel(CancellationException(stepInterrupted))
                }
                outcome = if (failure is CatalogStoppedException) "FINANCIAL_STOP" else "FAILED"
                // Only our own fixed errors are surfaced; no provider exception text or screen dump is logged.
                graph.catalog.value = graph.catalog.value.copy(error = failure.message?.takeIf { it.any { c -> c in '\u0600'..'\u06ff' } }
                    ?: "توقفت العملية عند شاشة غير مدعومة؛ راجع السلة إن بدأ تجهيزها.")
                if (!terminal && comparisonJob?.isActive != true && !BuildConfig.VERSION_NAME.endsWith("-device-tools")) returnToApp()
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
                    if(BuildConfig.VERSION_NAME.endsWith("-device-tools")) lastKeetaFoodProduct?.let { frame -> runCatching {
                        service.filesDir.resolve("last-keeta-public-product.json").writeText(JSONObject()
                            .put("recordedAt",System.currentTimeMillis())
                            .put("frame",JSONObject(com.google.gson.Gson().toJson(frame))).toString(),Charsets.UTF_8)
                    }}
                    if(BuildConfig.VERSION_NAME.endsWith("-device-tools")) runCatching {
                        service.filesDir.resolve("observed-public-menu-titles.json").writeText(JSONObject()
                            .put("recordedAt",System.currentTimeMillis())
                            .put("restaurant",graph.draft.value?.restaurant ?: JSONObject.NULL)
                            .put("completeMenu",false)
                            .put("providers",JSONObject().also { out -> observedMenuTitles.forEach { (id,titles) ->
                                out.put(id.name,JSONArray(titles.toList()))
                            }}).toString(),Charsets.UTF_8)
                    }
                }
                if (generation == token) { sessionEvidence = null; hideStop(); job = null; graph.catalog.value = graph.catalog.value.copy(busy = false) }
            }
        }
        job?.start()
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
            val beforeReturn=frame
            act(frame, if (screen == CatalogScreen.SPIN_PROMO) CatalogAction.CLOSE_SPIN_PROMO else CatalogAction.RETURN_TO_CATALOG,
                { it.index == target.index && it == target })
            // Android can return from one known menu to another known menu. The actual
            // frame must change, but the screen enum need not change; still admit known Back only.
            frame = await {
                it != beforeReturn &&
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
                if(provider==ProviderId.KEETA && (KeetaAvailability.closedNotice(frame) || KeetaAvailability.closedMenu(frame)))
                    throw UserInterrupted("المطعم مغلق حاليًا في كيتا؛ توقفت دون إضافة أخرى أو تأكيد طلب. اختر مطعمًا متاحًا للمقارنة.")
                if (provider == ProviderId.HUNGERSTATION && frame.complete && frame.nodes.size<=30 &&
                    frame.nodes.any{it.label.trim()=="لا يوجد اتصال بالإنترنت"} &&
                    frame.nodes.any{it.label.trim()=="يبدو أن هناك مشكلة في الاتصال بالإنترنت. يرجى التحقق والمحاولة مرة أخرى."})
                    throw UserInterrupted("هنقرستيشن يعرض عدم وجود اتصال بالإنترنت؛ احتُفظ بطلبك دون تكرار الإضافة.")
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
                    val value = if (provider == ProviderId.HUNGERSTATION) CatalogStability.fingerprint(frame)
                        else frame.nodes.joinToString { it.id + it.label + it.parent }
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
        check(movementAllowed) { "التنقل التلقائي متاح أثناء المقارنة أو فتح سلة متحققة فقط." }
        if (graph.settings.value.catalogArea?.url != requestAreaUrl)
            throw UserInterrupted("تغيرت منطقة المطاعم؛ توقفت العملية قبل أي إجراء آخر.")
        requestFingerprint?.let { if (graph.draft.value?.fingerprint != it)
            throw UserInterrupted("تغير طلبك؛ توقفت العملية قبل أي إجراء آخر.") }
        if (!graph.settings.value.catalogConsent || provider !in graph.settings.value.allowed)
            throw UserInterrupted("تغير السماح؛ توقفت العملية.")
        val lock = service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (lock.isKeyguardLocked) throw UserInterrupted("قُفل الجهاز؛ توقفت العملية.")
    }

    private fun capture(root: AccessibilityNodeInfo): CatalogFrame {
        val nodes = mutableListOf<CatalogNode>()
        val requestedBasketLabels = mutableMapOf<Int, String>()
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
            if (node.isPassword) throw UserInterrupted("ظهرت شاشة كلمة مرور؛ توقفت المقارنة.")
            if (android.os.Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive && !private) { sensitiveSkipped++; complete = false; return }
            val index = nodes.size
            val raw = if (private || node.isEditable || disabledFinancial) "" else (node.text ?: node.contentDescription)?.toString().orEmpty()
            if (raw.length > 1000) { complete = false; return }
            val label = if (provider == ProviderId.KEETA) keetaMenu.functionalLabel(raw, id) else raw
            if (provider == ProviderId.KEETA && label.isEmpty())
                KeetaBasketReader.requestedTitleCandidate(raw,keetaBasketTitles)?.let {requestedBasketLabels[index]=it}
            ProviderAccessRestrictions.detect(raw)?.let { throw UserInterrupted(it.userMessage) }
            if (!private && !node.isEditable && !disabledFinancial) {
                ProviderAccessRestrictions.detect(node.contentDescription)?.let { throw UserInterrupted(it.userMessage) }
            }
            if (Regex("تسجيل الدخول|كلمة المرور|رمز التحقق|captcha|card number|verification code|password", RegexOption.IGNORE_CASE).containsMatchIn(raw))
                throw UserInterrupted("ظهرت شاشة دخول أو حماية؛ أكملها بنفسك داخل المنصة.")
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
        val captured = CatalogFrame(nodes, complete)
        val frame = if (provider == ProviderId.KEETA)
            keetaBasket.restoreRequestedRowTitles(captured, requestedBasketLabels) else captured
        firstDisabledFinancialControl?.let {
            if (!CatalogFinancialBoundary.permitsDisabledControls(frame, provider)) catalogStop.stop(it)
        }
        if(BuildConfig.VERSION_NAME.endsWith("-device-tools") && provider==ProviderId.KEETA && frame.complete &&
            frame.nodes.none {it.editable} && frame.nodes.count {it.label=="إضافة إلى السلة"}==1 &&
            frame.nodes.any {it.label==keetaMenu.restaurant} && frame.hasId("card_root")) lastKeetaFoodProduct=frame
        if(BuildConfig.VERSION_NAME.endsWith("-device-tools") && frame.complete) {
            val titles=when(provider) {
                ProviderId.HUNGERSTATION -> if(HungerStationCatalog.screen(frame)==CatalogScreen.MENU)
                    HungerStationCatalog.menuItems(frame).map {it.name} else emptyList()
                ProviderId.KEETA -> if(keetaMenu.menu(frame)) frame.nodes.filter {
                    it.id=="com.sankuai.sailor.afooddelivery:id/product_name" && it.label.isNotBlank()
                }.map {it.label} else emptyList()
            }
            val observed=observedMenuTitles.getOrPut(provider) {linkedSetOf()}
            titles.filter {it.length in 1..300}.take((1000-observed.size).coerceAtLeast(0)).forEach(observed::add)
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
                } }, scroll = { scrollProduct(it) }).also { reading ->
                    if (BuildConfig.DEBUG) {
                        // Fixed counts and booleans only: no provider text, user inputs or account data.
                        val last = reading.lastFrame
                        val checkpoint = JSONObject().put("capturedAt", System.currentTimeMillis())
                            .put("optionsComplete", reading.details.optionsComplete)
                            .put("available", reading.details.product.available)
                            .put("pricePresent", reading.details.product.listedPriceHalala != null)
                            .put("frameComplete", last.complete)
                            .put("nodeCount", last.nodes.size)
                            .put("enteredTextCount", last.nodes.count { it.enteredText })
                            .put("addButtonCount", last.nodes.count { it.id == "flutterAddButton" })
                            .put("addEnabled", last.nodes.singleOrNull { it.id == "flutterAddButton" }?.enabled == true)
                            .put("titleCount", last.nodes.count { !it.clickable && it.label.trim() == item.name })
                            .put("optionsEndObserved", last.nodes.any { it.label.trim() == "غالبًا ما يتم طلبه مع" })
                        withContext(Dispatchers.IO) {
                            runCatching { service.filesDir.resolve("last-hunger-product-reading.json").writeText(checkpoint.toString(), Charsets.UTF_8) }
                        }
                    }
                }
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

    private suspend fun click(screen: CatalogScreen, action: CatalogAction, selector: (CatalogNode) -> Boolean): Boolean {
        // Rotating home banners can invalidate a frame before dispatch. Only retry
        // opening search; never retry cart mutations or an already-dispatched action.
        val attempts = if (screen == CatalogScreen.HOME && action == CatalogAction.OPEN_SEARCH) 4 else 1
        repeat(attempts) { attempt ->
            try { return act(await { HungerStationCatalog.screen(it) == screen }, action, selector) }
            catch (changed: ScreenChangedBeforeAction) {
                if (attempt == attempts - 1) throw changed
                delay(200)
            }
        }
        error("تعذر فتح البحث بعد تغير الشاشة.")
    }

    private suspend fun act(previous: CatalogFrame, action: CatalogAction, selector: (CatalogNode) -> Boolean,
        text: String? = null, allowNoOp: Boolean = false, ridanProgram: RidanCartProgram? = null,
        basketCommand: BasketOpenCommand? = null, quantityCommand: RidanQuantityCommand? = null,
        ownedQuantityCommand:HungerStationOwnedQuantityCommand?=null,
        menuQuantityCommand: HungerStationMenuQuantityCommand? = null,
        productQuantityCommand: HungerStationProductQuantityCommand? = null,
        variantQuantityCommand: HungerStationVariantQuantityCommand? = null, variantTitle:String?=null): Boolean {
        currentCoroutineContext().ensureActive(); checkSession()
        val root = service.rootInActiveWindow ?: throw UserInterrupted("اختفت الشاشة قبل الإجراء؛ توقفت المقارنة.")
        try {
            requireProviderWindow(root)
            val fresh = capture(root)
            if (fresh != previous) throw ScreenChangedBeforeAction()
            val selected = fresh.nodes.filter(selector)
            require(selected.size == 1) { "تعذر تحديد عنصر فريد؛ توقفت العملية." }
            val node = selected.single()
            check(node.enabled) { "العنصر غير متاح؛ توقفت العملية." }
            val allowed = when (action) {
                CatalogAction.CLOSE_HUNGER_VARIANT -> provider==ProviderId.HUNGERSTATION && graph.settings.value.catalogConsent &&
                    graph.installations.value.singleOrNull{it.provider==provider}?.version==HungerStationCatalog.TESTED_VERSION &&
                    variantTitle?.let {HungerStationExistingVariant.read(fresh,it)?.close==node}==true
                CatalogAction.CLOSE_SPIN_PROMO -> SafetyGuard().allowsSpinPromoClose(fresh, node,
                    root.packageName?.toString(), graph.installations.value.singleOrNull { it.provider == provider }?.version,
                    graph.settings.value.catalogConsent, currentCoroutineContext().isActive)
                CatalogAction.ADD_RIDAN -> graph.settings.value.catalogConsent &&
                    ridanProgram?.consumeAdd(fresh, node, System.currentTimeMillis(),graph.draft.value) == true
                CatalogAction.ADJUST_RIDAN_QUANTITY -> graph.settings.value.catalogConsent &&
                    (ownedQuantityCommand?.let { command -> graph.draft.value?.let { request ->
                        command.consume(fresh,node,request,graph.installations.value.singleOrNull {it.provider==provider}?.version,System.currentTimeMillis())
                    }}==true || variantQuantityCommand?.consume(fresh,node,graph.installations.value.singleOrNull{it.provider==provider}?.version,System.currentTimeMillis())==true ||
                        productQuantityCommand?.consume(fresh,node,graph.installations.value.singleOrNull{it.provider==provider}?.version,System.currentTimeMillis()) == true ||
                        quantityCommand?.consume(fresh, node, System.currentTimeMillis()) == true ||
                        menuQuantityCommand?.consume(fresh, node,
                            graph.installations.value.singleOrNull { it.provider == provider }?.version,
                            System.currentTimeMillis()) == true)
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
                if (!(actual.refresh() && actual.isVisibleToUser && actual.isEnabled && !actual.isPassword &&
                    actual.windowId == root.windowId && actual.packageName?.toString() == provider.packageName &&
                    (android.os.Build.VERSION.SDK_INT < 34 || !actual.isAccessibilityDataSensitive))) throw ScreenChangedBeforeAction()
                val id = actual.viewIdResourceName.orEmpty()
                val className = actual.className?.toString().orEmpty()
                check(CatalogFinancialBoundary.classify(id, className, actual.isClickable, actual.isEditable,
                    provider) == CatalogFinancialBoundary.Decision.NORMAL) { "العنصر المالي غير مسموح." }
                val label = if (privateId(id) || actual.isEditable) ""
                    else (actual.text ?: actual.contentDescription)?.toString().orEmpty()
                val observed = expected.copy(id = id, label = label, className = className,
                    clickable = actual.isClickable, editable = actual.isEditable, scrollable = actual.isScrollable,
                    enabled = actual.isEnabled)
                if (!CatalogTargetGuard.matches(expected, observed, provider)) throw ScreenChangedBeforeAction()
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
                verifyActiveFrame(fresh, root.windowId)
                currentCoroutineContext().ensureActive(); checkSession()
                if (action == CatalogAction.ADD_RIDAN ||
                    (action == CatalogAction.ADJUST_RIDAN_QUANTITY && productQuantityCommand == null)) {
                    recordBasketMutation()
                    verifyActiveFrame(fresh, root.windowId)
                    currentCoroutineContext().ensureActive(); checkSession()
                }
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
    private fun showManualControls(): Boolean = try {
        val controls = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            addView(Button(service).apply { text = "احفظ وارجع للأخطبوط"; setOnClickListener { captureManualCatalog() } })
            addView(Button(service).apply { text = "إيقاف القراءة ×"; setOnClickListener { endManualCatalog() } })
        }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 30 }
        (service.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(controls, params)
        overlay = controls
        true
    } catch (_: Exception) { false }
    private fun showStop(): Boolean = try {
        val button = Button(service).apply { text = "إيقاف المقارنة ×"; setOnClickListener { stop() } }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 30 }
        (service.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(button, params)
        overlay = button; true
    } catch (failure: Exception) {
        if (BuildConfig.VERSION_NAME.endsWith("-device-tools")) runCatching {
            service.filesDir.resolve("live-overlay-error.json").writeText(
                JSONObject().put("exceptionType", failure.javaClass.name)
                    .put("mainThread", android.os.Looper.myLooper() == android.os.Looper.getMainLooper()).toString())
        }
        false
    }
    private fun hideStop() { overlay?.let { runCatching { (service.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) } }; overlay = null }
}
