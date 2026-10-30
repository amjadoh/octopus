package app.octopus.local.providers

import app.octopus.local.domain.ConfiguredProduct
import app.octopus.local.domain.ProviderId
import java.security.MessageDigest

/** A delta of public catalog data. Repeated observations never renew its capture time. */
data class ManualCatalogUpdate(
    val screen: CatalogScreen,
    val restaurants: List<LiveRestaurant> = emptyList(),
    val menu: LiveMenu? = null,
    val product: ConfiguredProduct? = null,
    val reason: String? = null,
) {
    val hasData: Boolean get() = restaurants.isNotEmpty() || menu != null || product != null
}

/**
 * Reads screens reached by the user. This class has no Android/action dependency.
 * A cached menu is not evidence of the currently open restaurant: its title must be
 * observed first. Unknown/partial screens break that continuity.
 */
class ManualCatalogCollector {
    private val restaurants = linkedMapOf<String, LiveRestaurant>()
    private val menus = linkedMapOf<String, LiveMenu>()
    private val observedMenuPages = linkedMapOf<String, List<LiveMenuItem>>()
    private var provenMenu: LiveMenu? = null
    private var productKey: Pair<String, String>? = null
    private var productReader: HungerStationProductReader? = null
    private var productFrameHashes = linkedSetOf<String>()
    private var productStartedAtTop = false
    private var lastScreen: CatalogScreen? = null

    fun reset() {
        resetContext()
        restaurants.clear()
        menus.clear()
        observedMenuPages.clear()
    }

    private fun resetContext() {
        provenMenu = null
        clearProduct()
        lastScreen = null
    }

    fun observe(
        frame: CatalogFrame,
        now: Long,
        knownRestaurants: List<LiveRestaurant> = emptyList(),
        knownMenus: List<LiveMenu> = emptyList(),
    ): ManualCatalogUpdate {
        require(now >= 0)
        seed(knownRestaurants, knownMenus)
        if (!frame.complete) {
            resetContext()
            return ManualCatalogUpdate(CatalogScreen.UNKNOWN, reason = "قراءة الشاشة غير مكتملة؛ لم تُربط ببيانات مطعم أو وجبة.")
        }
        val safe = publicFrame(frame)
        val screen = HungerStationCatalog.screen(safe)
        if (screen != CatalogScreen.PRODUCT) clearProduct()
        val result = when (screen) {
            CatalogScreen.BROWSE, CatalogScreen.RESULTS -> readRestaurants(safe, screen)
            CatalogScreen.MENU -> readMenu(safe, now)
            CatalogScreen.PRODUCT -> readProduct(safe, now)
            else -> {
                provenMenu = null
                clearProduct()
                ManualCatalogUpdate(screen, reason = "هذه الشاشة ليست قائمة مطاعم أو قائمة وجبات أو خيارات وجبة معروفة.")
            }
        }
        lastScreen = screen
        return result
    }

    private fun seed(knownRestaurants: List<LiveRestaurant>, knownMenus: List<LiveMenu>) {
        knownRestaurants.filter { it.source == ProviderId.HUNGERSTATION }.take(500).forEach {
            if (it.name !in restaurants) putBounded(restaurants, it.name, it, 500)
        }
        knownMenus.filter { it.restaurant.source == ProviderId.HUNGERSTATION }.take(100).forEach {
            if (it.restaurant.name !in restaurants) putBounded(restaurants, it.restaurant.name, it.restaurant, 500)
            if (it.restaurant.name !in menus) putBounded(menus, it.restaurant.name, it, 100)
        }
        observedMenuPages.keys.retainAll(menus.keys)
    }

    private fun readRestaurants(frame: CatalogFrame, screen: CatalogScreen): ManualCatalogUpdate {
        provenMenu = null
        val rows = (if (screen == CatalogScreen.BROWSE) HungerStationCatalog.browsedRestaurants(frame)
            else HungerStationCatalog.restaurants(frame, "")).take(500).map { it.copy(searchQuery = it.name) }
        if (rows.isEmpty()) return ManualCatalogUpdate(screen, reason = "لم تظهر أسماء مطاعم قابلة للقراءة في هذه الشاشة.")
        val changed = rows.filter { restaurants[it.name] != it }
        changed.forEach { putBounded(restaurants, it.name, it, 500) }
        return ManualCatalogUpdate(screen, restaurants = changed)
    }

    private fun readMenu(frame: CatalogFrame, now: Long): ManualCatalogUpdate {
        // A unique exact line in a noninteractive title is needed; a default or
        // selected draft restaurant must never stand in for visible identity.
        val titleMatches = frame.nodes.filter { !it.editable && !it.clickable }.flatMap { node ->
            node.label.lineSequence().map(::clean).filter { it in restaurants }.map { node.index to it }.toList()
        }
        if (titleMatches.size != 1) {
            provenMenu = null
            return ManualCatalogUpdate(CatalogScreen.MENU, reason = if (titleMatches.isEmpty())
                "اسم المطعم غير متحقق في عنوان القائمة؛ احفظ شاشة المطاعم ثم افتح قائمة المطعم."
                else "ظهر أكثر من عنوان مطعم محتمل؛ لم تُنسب القائمة إلى مطعم بالتخمين.")
        }
        val restaurant = restaurants.getValue(titleMatches.single().second)
        val visible = HungerStationCatalog.menuItems(frame)
        if (visible.isEmpty()) {
            provenMenu = null
            return ManualCatalogUpdate(CatalogScreen.MENU, reason = "اسم المطعم معروف لكن أصناف القائمة غير مقروءة في هذه الشاشة.")
        }
        if (visible.groupBy { it.selector }.any { (_, rows) -> rows.distinct().size > 1 }) {
            provenMenu = null
            return ManualCatalogUpdate(CatalogScreen.MENU, reason = "ظهرت أصناف بمعرّف متكرر؛ لم تُدمج وجبات غير قابلة للتمييز.")
        }
        val previous = menus[restaurant.name]
        val merged = linkedMapOf<String, LiveMenuItem>()
        previous?.items?.forEach { merged[it.selector] = it }
        visible.forEach { merged[it.selector] = it }
        val items = merged.values.take(1_000)
        val same = observedMenuPages[restaurant.name] == visible
        val menu = if (same && previous != null) previous else LiveMenu(restaurant, items, now, reachedEnd = false)
        putBounded(menus, restaurant.name, menu, 100)
        putBounded(observedMenuPages, restaurant.name, visible, 100)
        observedMenuPages.keys.retainAll(menus.keys)
        provenMenu = menu
        // Only rows actually visible in this observation receive this timestamp.
        // The accumulated menu above is internal identity context, not a cache delta.
        return ManualCatalogUpdate(CatalogScreen.MENU,
            menu = if (same) null else LiveMenu(restaurant, visible.distinct(), now, reachedEnd = false))
    }

    private fun readProduct(frame: CatalogFrame, now: Long): ManualCatalogUpdate {
        val menu = provenMenu
        if (menu == null || lastScreen !in setOf(CatalogScreen.MENU, CatalogScreen.PRODUCT)) {
            clearProduct()
            return ManualCatalogUpdate(CatalogScreen.PRODUCT, reason = "افتح قائمة المطعم أولًا حتى تُربط خيارات الوجبة بالمطعم الصحيح.")
        }
        val candidates = frame.nodes.filter { !it.clickable && !it.editable }.flatMap { node ->
            menu.items.filter { clean(node.label) == it.name }.map { node.index to it }
        }
        if (candidates.size != 1) {
            clearProduct()
            provenMenu = null
            return ManualCatalogUpdate(CatalogScreen.PRODUCT, reason = if (candidates.isEmpty())
                "اسم الوجبة غير ظاهر أو غير موجود في القائمة المقروءة؛ لم تُحفظ خيارات لوجبة مجهولة."
                else "لم يمكن تمييز عنوان وجبة واحد؛ لم تُدمج خيارات أصناف مختلفة.")
        }
        val item = candidates.single().second
        val key = menu.restaurant.name to item.selector
        if (productKey != key) {
            clearProduct()
            productKey = key
            productReader = HungerStationProductReader(menu.restaurant, item)
        }
        // Recorded HS top options expose pdpZoomButton; the scrolled bottom does
        // not. An initial bottom frame cannot prove that required groups above
        // it were read. Starting later at the top starts a new accumulation.
        if (!productStartedAtTop && frame.hasId("pdpZoomButton")) {
            productStartedAtTop = true
            productReader = HungerStationProductReader(menu.restaurant, item)
            productFrameHashes.clear()
        }
        val signature = fingerprint(frame)
        if (signature in productFrameHashes) return ManualCatalogUpdate(CatalogScreen.PRODUCT)
        if (productFrameHashes.size >= 50) {
            clearProduct()
            provenMenu = null
            return ManualCatalogUpdate(CatalogScreen.PRODUCT, reason = "بلغت قراءة خيارات الوجبة حدها؛ لم تُعتمد خيارات ناقصة.")
        }
        productFrameHashes += signature
        val reader = checkNotNull(productReader)
        return try {
            reader.observe(frame)
            val parsed = reader.finish(now)
            val details = parsed.copy(optionsComplete = parsed.optionsComplete && productStartedAtTop)
            ManualCatalogUpdate(CatalogScreen.PRODUCT, product = details, reason = if (details.optionsComplete) null
                else if (!productStartedAtTop) "ارجع إلى أعلى خيارات الوجبة أولًا ثم مرّر بنفسك حتى نهايتها؛ لم تُعتمد الخيارات كاملة."
                else "خيارات الوجبة لم تكتمل؛ مرّرها بنفسك حتى نهاية الخيارات قبل حفظ الاختيار.")
        } catch (_: IllegalArgumentException) {
            clearProduct()
            provenMenu = null
            ManualCatalogUpdate(CatalogScreen.PRODUCT, reason = "بنية خيارات الوجبة غير معروفة؛ لم تُعتمد خيارات أو أسعار بديلة.")
        }
    }

    private fun clearProduct() {
        productKey = null
        productReader = null
        productFrameHashes.clear()
        productStartedAtTop = false
    }

    private fun <T> putBounded(map: LinkedHashMap<String, T>, key: String, value: T, limit: Int) {
        map.remove(key)
        map[key] = value
        while (map.size > limit) map.remove(map.keys.first())
    }

    /** The caller normally supplies an already-redacted frame; defend that boundary here too. */
    private fun publicFrame(frame: CatalogFrame): CatalogFrame {
        val private = mutableSetOf<Int>()
        val privateId = Regex("address|location|welcome|wallet|phone|email|password|otp|verification|recipient|contact", RegexOption.IGNORE_CASE)
        val nodes = frame.nodes.map { node ->
            if (node.parent in private || privateId.containsMatchIn(node.id)) private += node.index
            if (node.index in private || node.editable) node.copy(label = "") else node
        }
        return CatalogFrame(nodes, frame.complete)
    }

    private fun fingerprint(frame: CatalogFrame): String = MessageDigest.getInstance("SHA-256")
        .digest(frame.nodes.joinToString("\u001e") {
            "${it.id}\u001f${clean(it.label)}\u001f${it.clickable}\u001f${it.enabled}\u001f${it.enteredText}"
        }.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun clean(text: String): String = text.replace(Regex("[\\u200e\\u200f\\u061c\\u202a-\\u202e\\u2066-\\u2069]"), "").trim()
}
