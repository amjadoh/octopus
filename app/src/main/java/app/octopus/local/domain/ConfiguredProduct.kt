package app.octopus.local.domain

/** A read product configuration is a draft source, never proof that a provider basket exists. */
data class ConfiguredProduct(
    val restaurant: String,
    val product: MenuProduct,
    val source: CatalogProvenance,
    val optionsComplete: Boolean,
    val sizeGroupId: String? = null,
) {
    init { require(restaurant.isNotBlank()); require(sizeGroupId == null || product.groups.any { it.id == sizeGroupId }) }
}

sealed interface DraftChange {
    data class Changed(val cart: CanonicalCart?) : DraftChange
    data object RestaurantReplacementRequired : DraftChange
    data class Rejected(val reason: String) : DraftChange
}

/** Changes only the local intended order; no provider edits or approvals are implied. */
object DraftEditor {
    fun add(current: CanonicalCart?, restaurant: String, item: CanonicalItem, replaceRestaurant: Boolean = false): DraftChange {
        if (current != null && IdentityText.clean(current.restaurant) != IdentityText.clean(restaurant)) {
            if (!replaceRestaurant) return DraftChange.RestaurantReplacementRequired
            return DraftChange.Changed(CanonicalCart(restaurant, null, listOf(item)))
        }
        if (current == null) return DraftChange.Changed(CanonicalCart(restaurant, null, listOf(item)))
        val key = CartIdentity.itemKey(item)
        val existing = current.items.filter { CartIdentity.itemKey(it) == key }.sumOf { it.quantity }
        if (existing + item.quantity > 99) return DraftChange.Rejected("تجاوزت كمية الصنف الحد المتاح.")
        val items = current.items.filterNot { CartIdentity.itemKey(it) == key } + item.copy(quantity = existing + item.quantity)
        if (items.size > 100) return DraftChange.Rejected("وصل الطلب إلى الحد الأقصى للأصناف.")
        return DraftChange.Changed(current.copy(items = items))
    }

    fun quantity(current: CanonicalCart?, key: String, quantity: Int): DraftChange {
        if (current == null || current.items.none { CartIdentity.itemKey(it) == key }) return DraftChange.Rejected("تغير الطلب؛ حدّث العرض.")
        if (quantity !in 0..99) return DraftChange.Rejected("الكمية خارج الحد المتاح.")
        val items = current.items.filterNot { CartIdentity.itemKey(it) == key } +
            if (quantity == 0) emptyList() else listOf(current.items.first { CartIdentity.itemKey(it) == key }.copy(quantity = quantity))
        return DraftChange.Changed(if (items.isEmpty()) null else current.copy(items = items))
    }
}
