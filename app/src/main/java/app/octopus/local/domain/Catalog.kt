package app.octopus.local.domain

data class CatalogProvenance(
    val provider: ProviderId,
    val providerVersion: String,
    val capturedAtMillis: Long,
    val screenFingerprint: String,
) {
    init { require(providerVersion.isNotBlank() && capturedAtMillis >= 0 && screenFingerprint.isNotBlank()) }
    fun fresh(now: Long, maxAgeMillis: Long): Boolean = maxAgeMillis >= 0 &&
        capturedAtMillis <= now && now - capturedAtMillis <= maxAgeMillis
}

data class MenuChoice(val id: String, val title: String, val available: Boolean) {
    init { require(id.isNotBlank() && title.isNotBlank()) }
}
data class MenuChoiceGroup(val id: String, val title: String, val minimum: Int, val maximum: Int, val choices: List<MenuChoice>) {
    init {
        require(id.isNotBlank() && title.isNotBlank() && minimum >= 0 && maximum >= minimum)
        require(maximum <= choices.size && choices.map { it.id }.distinct().size == choices.size)
    }
}
data class MenuProduct(
    val id: String,
    val title: String,
    val size: String?,
    val available: Boolean,
    val listedPriceHalala: Long?,
    val groups: List<MenuChoiceGroup>,
) {
    init {
        require(id.isNotBlank() && title.isNotBlank() && (listedPriceHalala == null || listedPriceHalala >= 0))
        require(groups.map { it.id }.distinct().size == groups.size)
    }
}
data class RestaurantMenu(
    val restaurantId: String,
    val restaurant: String,
    val branch: String?,
    val addressReference: String,
    val products: List<MenuProduct>,
    val source: CatalogProvenance,
    val complete: Boolean,
) {
    init {
        require(restaurantId.isNotBlank() && restaurant.isNotBlank() && addressReference.isNotBlank())
        require(products.map { it.id }.distinct().size == products.size)
    }
}
data class ProductSelection(val productId: String, val choices: Map<String, Set<String>>, val quantity: Int)
enum class SelectionError { STALE_MENU, PARTIAL_MENU, PRODUCT_MISSING, UNAVAILABLE, INVALID_QUANTITY, UNKNOWN_GROUP, UNKNOWN_CHOICE, REQUIRED_CHOICES, AMBIGUOUS_CHOICES, INVALID_METADATA }
sealed interface SelectionResult {
    data class Accepted(val item: CanonicalItem) : SelectionResult
    data class Rejected(val reason: SelectionError) : SelectionResult
}

/** All user selections are validated against the source menu; unknown prices remain unknown. */
class MenuSelectionValidator(private val maxAgeMillis: Long = 300_000) {
    fun selectConfigured(details: ConfiguredProduct, selection: ProductSelection, now: Long): SelectionResult {
        // Product option coverage is independent of whether the restaurant's entire catalog was read.
        if (!details.source.fresh(now, maxAgeMillis)) return SelectionResult.Rejected(SelectionError.STALE_MENU)
        if (!details.optionsComplete) return SelectionResult.Rejected(SelectionError.PARTIAL_MENU)
        if (details.sizeGroupId != null && details.product.size != null) return SelectionResult.Rejected(SelectionError.INVALID_METADATA)
        if (selection.productId != details.product.id) return SelectionResult.Rejected(SelectionError.PRODUCT_MISSING)
        val result = configure(details.product, selection)
        if (result !is SelectionResult.Accepted || details.sizeGroupId == null) return result
        val group = details.product.groups.single { it.id == details.sizeGroupId }
        val choice = selection.choices[group.id].orEmpty().singleOrNull()
            ?: return SelectionResult.Rejected(SelectionError.REQUIRED_CHOICES)
        val size = group.choices.single { it.id == choice }.title
        return SelectionResult.Accepted(result.item.copy(size = size, options = result.item.options.filterNot { it.group == group.title }))
    }

    fun select(menu: RestaurantMenu, selection: ProductSelection, now: Long): SelectionResult {
        fun reject(reason: SelectionError) = SelectionResult.Rejected(reason)
        if (!menu.source.fresh(now, maxAgeMillis)) return reject(SelectionError.STALE_MENU)
        if (!menu.complete) return reject(SelectionError.PARTIAL_MENU)
        if (selection.quantity !in 1..99) return reject(SelectionError.INVALID_QUANTITY)
        val product = menu.products.singleOrNull { it.id == selection.productId } ?: return reject(SelectionError.PRODUCT_MISSING)
        return configure(product, selection)
    }

    private fun configure(product: MenuProduct, selection: ProductSelection): SelectionResult {
        fun reject(reason: SelectionError) = SelectionResult.Rejected(reason)
        if (IdentityText.clean(product.title).isBlank() || product.title.length > 512 ||
            product.size?.let { IdentityText.clean(it).isBlank() || it.length > 300 } == true ||
            product.groups.any { group -> IdentityText.clean(group.title).isBlank() || group.title.length > 300 ||
                group.choices.any { IdentityText.clean(it.title).isBlank() || it.title.length > 300 } })
            return reject(SelectionError.INVALID_METADATA)
        if (selection.quantity !in 1..99) return reject(SelectionError.INVALID_QUANTITY)
        if (!product.available) return reject(SelectionError.UNAVAILABLE)
        if (selection.choices.keys.any { key -> product.groups.none { it.id == key } }) return reject(SelectionError.UNKNOWN_GROUP)
        val options = mutableListOf<CartOption>()
        product.groups.forEach { group ->
            val selected = selection.choices[group.id].orEmpty()
            if (selected.size !in group.minimum..group.maximum) return reject(SelectionError.REQUIRED_CHOICES)
            selected.sorted().forEach { id ->
                val choice = group.choices.singleOrNull { it.id == id } ?: return reject(SelectionError.UNKNOWN_CHOICE)
                if (!choice.available) return reject(SelectionError.UNAVAILABLE)
                options += CartOption(group.title, choice.title)
            }
        }
        if (options.map { IdentityText.clean(it.group) to IdentityText.clean(it.value) }.distinct().size != options.size)
            return reject(SelectionError.AMBIGUOUS_CHOICES)
        return SelectionResult.Accepted(CanonicalItem(product.title, product.size, options, selection.quantity))
    }
}
