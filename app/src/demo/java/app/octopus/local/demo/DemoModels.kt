package app.octopus.local.demo

import app.octopus.local.domain.ProviderId

/** Demonstration data is compiled exclusively into the demo flavor. */
enum class JourneyPage { RESTAURANTS, MENU, ITEM, CART, PROVIDERS, COMPARING, RESULTS, READY, HANDOFF, MORE, ADDRESSES }
data class DemoRestaurant(val id: String, val name: String, val category: String, val symbol: String, val description: String)
data class DemoOption(val id: String, val name: String, val priceHalala: Long)
data class DemoMenuItem(
    val id: String, val restaurantId: String, val name: String, val description: String,
    val symbol: String, val baseHalala: Long,
    val sizes: List<DemoOption> = emptyList(), val drinks: List<DemoOption> = emptyList(), val extras: List<DemoOption> = emptyList(),
)
data class DemoLine(
    val id: Long, val itemId: String, val sizeId: String?, val drinkId: String?,
    val extras: Set<String>, val quantity: Int,
)
data class DemoDraft(
    val itemId: String, val sizeId: String?, val drinkId: String? = null,
    val extras: Set<String> = emptySet(), val quantity: Int = 1, val editingId: Long? = null,
)
enum class DemoAddress(val title: String) { HOME("المنزل • عنوان توضيحي"), WORK("العمل • عنوان توضيحي") }
data class DemoQuote(
    val provider: ProviderId, val items: Long, val delivery: Long, val service: Long,
    val smallOrder: Long, val discount: Long, val orderCost: Long, val wallet: Long,
) { val dueNow get() = orderCost - wallet }
data class DemoJourneyState(
    val page: JourneyPage = JourneyPage.RESTAURANTS,
    val restaurantId: String? = null,
    val cart: List<DemoLine> = emptyList(),
    val draft: DemoDraft? = null,
    val allowed: Set<ProviderId> = ProviderId.entries.toSet(),
    val address: DemoAddress = DemoAddress.HOME,
    val quotes: List<DemoQuote> = emptyList(),
    val selected: ProviderId? = null,
    val progress: Int = 0,
    val epoch: Long = 0,
    val pendingRestaurant: String? = null,
    val message: String? = null,
    val search: String = "",
    val category: String = "الكل",
)

object DemoCatalog {
    private val sizes = listOf(DemoOption("regular", "عادي", 0), DemoOption("large", "كبير", 700))
    private val drinks = listOf(DemoOption("cola", "مشروب غازي", 0), DemoOption("lemon", "ليمون بالنعناع", 400), DemoOption("water", "ماء", 0))
    private val burgerExtras = listOf(DemoOption("cheese", "جبن إضافي", 200), DemoOption("crispy", "بصل مقرمش", 300))
    private val pizzaSizes = listOf(DemoOption("regular", "وسط", 0), DemoOption("large", "كبير", 1000))
    private val pizzaExtras = listOf(DemoOption("cheese", "جبن إضافي", 300), DemoOption("mushroom", "فطر", 200))
    val restaurants = listOf(
        DemoRestaurant("garden", "برجر الحديقة", "برجر", "🍔", "وجبات البرجر والإضافات على ذوقك"),
        DemoRestaurant("oven", "فرن الحارة", "بيتزا", "🍕", "بيتزا وأطباق جانبية للمشاركة"),
        DemoRestaurant("house", "مطبخ الدار", "مشويات", "🍗", "مشويات ووجبات كاملة"),
    )
    val items = listOf(
        DemoMenuItem("beef", "garden", "وجبة برجر لحم", "برجر لحم مع بطاطس ومشروب تختاره", "🍔", 2600, sizes, drinks, burgerExtras),
        DemoMenuItem("chicken", "garden", "وجبة دجاج مقرمش", "دجاج مقرمش مع بطاطس ومشروب", "🍗", 2400, sizes, drinks, burgerExtras),
        DemoMenuItem("fries", "garden", "بطاطس", "طبق جانبي • اختر الحجم", "🍟", 800,
            listOf(DemoOption("regular", "عادي", 0), DemoOption("large", "كبير", 400)), extras = listOf(DemoOption("cheese", "صوص جبن", 200))),
        DemoMenuItem("water", "garden", "ماء", "عبوة ماء", "💧", 300),
        DemoMenuItem("margherita", "oven", "بيتزا مارغريتا", "جبن موزاريلا وصلصة الطماطم", "🍕", 3200, pizzaSizes, extras = pizzaExtras),
        DemoMenuItem("pepperoni", "oven", "بيتزا بيبروني", "بيبروني وجبن موزاريلا", "🍕", 3600, pizzaSizes, extras = pizzaExtras),
        DemoMenuItem("wedges", "oven", "بطاطس ودجز", "بطاطس متبلة", "🥔", 1000),
        DemoMenuItem("cola", "oven", "مشروب غازي", "عبوة مشروب غازي", "🥤", 500),
        DemoMenuItem("grill", "house", "وجبة دجاج مشوي", "دجاج مشوي مع الأرز والسلطة", "🍗", 2900,
            listOf(DemoOption("regular", "نصف دجاجة", 0), DemoOption("large", "دجاجة كاملة", 2200)),
            extras = listOf(DemoOption("rice", "أرز إضافي", 600), DemoOption("sauce", "صوص إضافي", 200))),
        DemoMenuItem("kebab", "house", "وجبة كباب", "كباب مع الخبز والسلطة", "🍢", 3400,
            listOf(DemoOption("regular", "عادي", 0), DemoOption("large", "كبير", 1200)), extras = listOf(DemoOption("rice", "أرز إضافي", 600))),
        DemoMenuItem("salad", "house", "سلطة خضراء", "خضار طازجة", "🥗", 1200),
    )
    fun item(id: String) = items.first { it.id == id }
    fun restaurant(id: String?) = restaurants.firstOrNull { it.id == id }
}

object DemoPricing {
    fun valid(draft: DemoDraft): Boolean {
        val item = DemoCatalog.items.firstOrNull { it.id == draft.itemId } ?: return false
        return draft.quantity in 1..10 &&
            (if (item.sizes.isEmpty()) draft.sizeId == null else item.sizes.any { it.id == draft.sizeId }) &&
            (if (item.drinks.isEmpty()) draft.drinkId == null else item.drinks.any { it.id == draft.drinkId }) &&
            draft.extras.all { selected -> item.extras.any { it.id == selected } }
    }
    fun unit(itemId: String, sizeId: String?, drinkId: String?, extras: Set<String>, provider: ProviderId = ProviderId.KEETA): Long {
        val item = DemoCatalog.item(itemId)
        val providerDifference = if (provider == ProviderId.HUNGERSTATION && item.baseHalala >= 2000) 200L else 0L
        return item.baseHalala + providerDifference + (item.sizes.firstOrNull { it.id == sizeId }?.priceHalala ?: 0) +
            (item.drinks.firstOrNull { it.id == drinkId }?.priceHalala ?: 0) + item.extras.filter { it.id in extras }.sumOf { it.priceHalala }
    }
    fun unit(line: DemoLine, provider: ProviderId = ProviderId.KEETA) = unit(line.itemId, line.sizeId, line.drinkId, line.extras, provider)
    fun unit(draft: DemoDraft) = unit(draft.itemId, draft.sizeId, draft.drinkId, draft.extras)
    fun subtotal(cart: List<DemoLine>, provider: ProviderId = ProviderId.KEETA) = cart.sumOf { unit(it, provider) * it.quantity }
    fun quote(cart: List<DemoLine>, provider: ProviderId): DemoQuote {
        require(cart.isNotEmpty())
        val items = subtotal(cart, provider)
        val delivery = if (provider == ProviderId.KEETA) 800L else 500L
        val service = if (provider == ProviderId.KEETA) 250L else 350L
        val small = if (items < 3500) 500L else 0L
        val discount = if (provider == ProviderId.KEETA) (if (items >= 5000) 1000L else 300L) else (if (items >= 5000) 700L else 200L)
        val total = items + delivery + service + small - discount
        val wallet = if (provider == ProviderId.HUNGERSTATION) minOf(1500L, total) else 0L
        return DemoQuote(provider, items, delivery, service, small, discount, total, wallet)
    }
    fun summary(line: DemoLine): String {
        val item = DemoCatalog.item(line.itemId)
        return listOfNotNull(item.sizes.firstOrNull { it.id == line.sizeId }?.name,
            item.drinks.firstOrNull { it.id == line.drinkId }?.name).plus(item.extras.filter { it.id in line.extras }.map { it.name })
            .joinToString(" • ").ifEmpty { "دون تخصيصات" }
    }
}
