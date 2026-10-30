package app.octopus.local.providers

import app.octopus.local.domain.ConfiguredProduct
import app.octopus.local.domain.IdentityText
import app.octopus.local.domain.ProviderId

data class CachedRestaurant(val restaurant: LiveRestaurant, val providerVersion: String, val capturedAtMillis: Long) {
    init { require(providerVersion.isNotBlank() && capturedAtMillis >= 0 && restaurant.name.isNotBlank()) }
}

/** Each retained item keeps its actual observation time; opening the library never refreshes it. */
data class CachedMenu(
    val menu: LiveMenu,
    val providerVersion: String,
    val itemCapturedAtMillis: Map<String, Long>,
    val lastObservedAtMillis: Long = menu.capturedAtMillis,
) {
    init {
        require(providerVersion.isNotBlank() && lastObservedAtMillis >= 0 && !menu.reachedEnd)
        require(menu.items.map { it.selector }.distinct().size == menu.items.size)
        require(menu.items.map { IdentityText.clean(it.name) }.distinct().size == menu.items.size)
        require(itemCapturedAtMillis.keys == menu.items.map { it.selector }.toSet())
        require(itemCapturedAtMillis.values.all { it in 0..lastObservedAtMillis })
        require(menu.capturedAtMillis == (itemCapturedAtMillis.values.minOrNull() ?: lastObservedAtMillis))
    }
}

/** Only locally observed catalog data. A cached item is order intent, never a current quote. */
data class CatalogLibrary(
    val restaurants: List<CachedRestaurant> = emptyList(),
    val menus: List<CachedMenu> = emptyList(),
    val products: List<ConfiguredProduct> = emptyList(),
) {
    init {
        require(restaurants.size <= MAX_RESTAURANTS && menus.size <= MAX_MENUS && products.size <= MAX_PRODUCTS)
        require(restaurants.map { key(it.restaurant.source, it.restaurant.name) }.distinct().size == restaurants.size)
        require(menus.map { key(it.menu.restaurant.source, it.menu.restaurant.name) }.distinct().size == menus.size)
        require(products.map { productKey(it) }.distinct().size == products.size)
        require(menus.all { it.menu.items.size <= MAX_ITEMS_PER_MENU })
    }

    fun menu(provider: ProviderId, restaurant: String): LiveMenu? =
        menus.singleOrNull { key(it.menu.restaurant.source, it.menu.restaurant.name) == key(provider, restaurant) }?.menu

    fun configuredProduct(provider: ProviderId, restaurant: String, itemId: String): ConfiguredProduct? =
        products.singleOrNull { it.source.provider == provider &&
            IdentityText.clean(it.restaurant) == IdentityText.clean(restaurant) && it.product.id == itemId }

    fun mergeRestaurants(observed: List<LiveRestaurant>, providerVersion: String, observedAtMillis: Long): CatalogLibrary {
        require(providerVersion.isNotBlank() && observedAtMillis >= 0)
        val groups = observed.filter(::validRestaurant).groupBy { key(it.source, it.name) }
        // Same-name branches in one observation are ambiguous even if their descriptions match.
        val ambiguous = groups.filterValues { it.size != 1 }.keys
        val incoming = groups.filterValues { it.size == 1 }.mapValues { (_, values) ->
            CachedRestaurant(values.single(), providerVersion, observedAtMillis)
        }
        val retained = restaurants.filterNot { key(it.restaurant.source, it.restaurant.name) in ambiguous }
            .associateBy { key(it.restaurant.source, it.restaurant.name) }.toMutableMap()
        val acceptedVersions = mutableMapOf<Pair<ProviderId, String>, String>()
        incoming.forEach { (entryKey, value) ->
            val prior = retained[entryKey]
            val canReplace = prior == null || value.capturedAtMillis > prior.capturedAtMillis ||
                (value.capturedAtMillis == prior.capturedAtMillis && value.providerVersion == prior.providerVersion)
            if (canReplace) {
                retained[entryKey] = value
                acceptedVersions[entryKey] = value.providerVersion
            }
        }
        return copy(
            restaurants = retained.values.sortedByDescending { it.capturedAtMillis }.take(MAX_RESTAURANTS),
            menus = menus.filterNot {
                val entryKey = key(it.menu.restaurant.source, it.menu.restaurant.name)
                entryKey in ambiguous || (it.lastObservedAtMillis <= observedAtMillis &&
                    acceptedVersions[entryKey]?.let { version -> version != it.providerVersion } == true)
            },
            products = products.filterNot {
                val entryKey = key(it.source.provider, it.restaurant)
                entryKey in ambiguous || (it.source.capturedAtMillis <= observedAtMillis &&
                    acceptedVersions[entryKey]?.let { version -> version != it.source.providerVersion } == true)
            },
        )
    }

    fun mergeMenu(observed: LiveMenu, providerVersion: String): CatalogLibrary {
        require(validRestaurant(observed.restaurant) && providerVersion.isNotBlank() && observed.capturedAtMillis >= 0)
        val restaurantKey = key(observed.restaurant.source, observed.restaurant.name)
        val knownRestaurant = restaurants.singleOrNull { key(it.restaurant.source, it.restaurant.name) == restaurantKey }
        if (knownRestaurant != null && knownRestaurant.providerVersion != providerVersion &&
            knownRestaurant.capturedAtMillis >= observed.capturedAtMillis) return this
        val old = menus.singleOrNull { key(it.menu.restaurant.source, it.menu.restaurant.name) == restaurantKey }
        val sameVersion = old?.providerVersion == providerVersion
        if (old != null && !sameVersion && old.lastObservedAtMillis > observed.capturedAtMillis) return this
        val rows = observed.items.filter(::validItem)
        val priorItems = if (sameVersion) old!!.menu.items else emptyList()
        fun hasNewerPrior(name: String? = null, id: String? = null): Boolean = priorItems.any {
            (IdentityText.clean(it.name) == name || it.selector == id) &&
                old!!.itemCapturedAtMillis.getValue(it.selector) > observed.capturedAtMillis
        }
        val duplicateNames = rows.groupBy { IdentityText.clean(it.name) }.filterValues { it.size != 1 }.keys
            .filterNot { hasNewerPrior(name=it) }.toSet()
        val duplicateIds = rows.groupBy { it.selector }.filterValues { it.size != 1 }.keys
            .filterNot { hasNewerPrior(id=it) }.toSet()
        val allDuplicateNames = rows.groupBy { IdentityText.clean(it.name) }.filterValues { it.size != 1 }.keys
        val allDuplicateIds = rows.groupBy { it.selector }.filterValues { it.size != 1 }.keys
        val unambiguous = rows.filterNot { IdentityText.clean(it.name) in allDuplicateNames || it.selector in allDuplicateIds ||
            hasNewerPrior(IdentityText.clean(it.name),it.selector) }
        val incomingNames = unambiguous.map { IdentityText.clean(it.name) }.toSet()
        val incomingIds = unambiguous.map { it.selector }.toSet()
        val retained = priorItems.filterNot {
            IdentityText.clean(it.name) in duplicateNames || it.selector in duplicateIds ||
                IdentityText.clean(it.name) in incomingNames || it.selector in incomingIds
        }
        val items = (unambiguous + retained).take(MAX_ITEMS_PER_MENU)
        val times = items.associate { item -> item.selector to
            if (item.selector in incomingIds) observed.capturedAtMillis else old!!.itemCapturedAtMillis.getValue(item.selector) }
        val lastObserved = maxOf(observed.capturedAtMillis, if (sameVersion) old!!.lastObservedAtMillis else 0)
        val cached = CachedMenu(observed.copy(items = items,
            capturedAtMillis = times.values.minOrNull() ?: lastObserved, reachedEnd = false),
            providerVersion, times, lastObserved)
        val visibleById = unambiguous.associateBy { it.selector }
        val base = mergeRestaurants(listOf(observed.restaurant), providerVersion, observed.capturedAtMillis)
        return base.copy(
            menus = (listOf(cached) + base.menus.filterNot {
                key(it.menu.restaurant.source, it.menu.restaurant.name) == restaurantKey
            }).take(MAX_MENUS),
            products = base.products.filterNot { product ->
                key(product.source.provider, product.restaurant) == restaurantKey && (
                    product.source.providerVersion != providerVersion ||
                        IdentityText.clean(product.product.title) in duplicateNames || product.product.id in duplicateIds ||
                        (IdentityText.clean(product.product.title) in incomingNames && product.product.id !in incomingIds) ||
                        visibleById[product.product.id]?.let { visible ->
                            visible.name != product.product.title || visible.listedPrice != product.product.listedPriceHalala
                        } == true)
            },
        )
    }

    fun mergeProduct(observed: ConfiguredProduct): CatalogLibrary {
        val existing = products.singleOrNull { productKey(it) == productKey(observed) }
        if (existing != null && existing.source.capturedAtMillis > observed.source.capturedAtMillis) return this
        val restaurantKey = key(observed.source.provider, observed.restaurant)
        val knownRestaurant = restaurants.singleOrNull { key(it.restaurant.source, it.restaurant.name) == restaurantKey }
        if (knownRestaurant != null && knownRestaurant.providerVersion != observed.source.providerVersion &&
            knownRestaurant.capturedAtMillis >= observed.source.capturedAtMillis) return this
        val menu = menus.singleOrNull { key(it.menu.restaurant.source, it.menu.restaurant.name) ==
            restaurantKey }
        // Product source time describes the options screen. Its price was inherited from a
        // separate menu read and cannot refresh that menu or establish a new menu version.
        if (menu != null && menu.providerVersion != observed.source.providerVersion) return this
        val row = menu?.menu?.items?.singleOrNull { it.selector == observed.product.id }
        if (row != null && (row.name != observed.product.title || row.listedPrice != observed.product.listedPriceHalala)) return this
        if (menu?.menu?.items?.any { IdentityText.clean(it.name) == IdentityText.clean(observed.product.title) &&
                it.selector != observed.product.id } == true) return this
        val restaurant = menu?.menu?.restaurant ?: LiveRestaurant(observed.restaurant, null,
            observed.restaurant, observed.source.provider)
        val base = mergeRestaurants(listOf(restaurant), observed.source.providerVersion, observed.source.capturedAtMillis)
        // Keep the entire existing menu unchanged. Missing rows stay missing until observed in
        // a menu; options may be retained separately but never create a listed price.
        return base.copy(products = (listOf(observed) + base.products.filterNot { productKey(it) == productKey(observed) })
            .take(MAX_PRODUCTS))
    }

    companion object {
        const val MAX_RESTAURANTS = 500
        const val MAX_MENUS = 100
        const val MAX_PRODUCTS = 1000
        const val MAX_ITEMS_PER_MENU = 500
        private fun key(provider: ProviderId, restaurant: String) = provider to IdentityText.clean(restaurant)
        private fun productKey(value: ConfiguredProduct) = Triple(value.source.provider,
            IdentityText.clean(value.restaurant), value.product.id)
        private fun validRestaurant(value: LiveRestaurant): Boolean = IdentityText.clean(value.name).isNotBlank() &&
            value.name.length <= 512 && value.searchQuery.length <= 512 && (value.description?.length ?: 0) <= 2000
        private fun validItem(value: LiveMenuItem): Boolean = IdentityText.clean(value.name).isNotBlank() &&
            value.name.length <= 512 && value.selector.isNotBlank() && value.selector.length <= 1024 &&
            (value.listedPrice == null || value.listedPrice >= 0)
    }
}
