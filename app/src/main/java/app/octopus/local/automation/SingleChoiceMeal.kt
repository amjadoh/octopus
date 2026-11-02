package app.octopus.local.automation

import app.octopus.local.domain.*
import app.octopus.local.providers.*

/** A missing count is resolved only from a fresh, complete, single-choice product schema.
 * Several meals are allowed when each is a distinct cart row of quantity one. */
class SingleChoiceMeal private constructor(
    val cart: CanonicalCart,
    val source: BasketEvidence,
    private val at: Long,
) {
    /** Required choices per meal row, keyed by the exact cart row name. */
    val valuesByTitle: Map<String, Set<String>> = cart.items.associate { item -> item.name to item.options.map { it.value }.toSet() }
    /** Every requested choice across all meals; used only to retain screen labels. */
    val values: Set<String> = valuesByTitle.values.flatten().toSet()

    /** The first [count] meals, used to verify the target cart between single adds. */
    fun prefix(count: Int): SingleChoiceMeal {
        require(count in 1..cart.items.size)
        return if (count == cart.items.size) this else SingleChoiceMeal(cart.copy(items = cart.items.take(count)), source, at)
    }

    fun resolve(evidence: BasketEvidence, now: Long): BasketEvidence? {
        if (now < at || now - at > 1_200_000 || !evidence.complete ||
            IdentityText.clean(evidence.restaurant.orEmpty()) != IdentityText.clean(cart.restaurant) ||
            evidence.lines.size != cart.items.size || evidence.lines.map { it.name }.toSet() != valuesByTitle.keys) return null
        for (row in evidence.lines) {
            val wanted = valuesByTitle.getValue(row.name)
            if (row.quantity != 1 || row.lineAmount == null || row.lineAmount < 0 || row.choices.size != wanted.size ||
                row.choices.map { it.value }.toSet() != wanted || row.choices.any { it.quantity != null && it.quantity != 1 }) return null
        }
        val sum = runCatching { evidence.lines.fold(0L) { total, row -> Math.addExact(total, row.lineAmount!!) } }.getOrNull()
        if (sum == null || evidence.subtotal != sum) return null
        return evidence.copy(lines = evidence.lines.map { row -> row.copy(choices = row.choices.map { it.copy(quantity = 1) }) })
    }

    companion object {
        const val MAX_MEALS = 4

        fun create(evidence: BasketEvidence, product: ConfiguredProduct, now: Long): SingleChoiceMeal? {
            val row = evidence.lines.singleOrNull() ?: return null
            return create(evidence, mapOf(row.name to product), now)
        }

        /** [products] maps every cart row name to the schema read from that row's own edit page. */
        fun create(evidence: BasketEvidence, products: Map<String, ConfiguredProduct>, now: Long): SingleChoiceMeal? {
            if (evidence.provider != ProviderId.HUNGERSTATION || !evidence.complete || evidence.lines.size !in 1..MAX_MEALS ||
                evidence.lines.map { it.name }.distinct().size != evidence.lines.size ||
                evidence.lines.map { it.name }.toSet() != products.keys) return null
            val restaurant = evidence.restaurant ?: return null
            // Meals are read one after another from the unchanged cart; each read gets its own 30 s window.
            val window = 45_000L * evidence.lines.size
            val items = evidence.lines.map { row -> item(row, products.getValue(row.name), restaurant, now, window) ?: return null }
            val sum = runCatching { evidence.lines.fold(0L) { total, row -> Math.addExact(total, row.lineAmount!!) } }.getOrNull()
            if (sum == null || evidence.subtotal != sum) return null
            val cart = CanonicalCart(restaurant, null, items)
            return SingleChoiceMeal(cart, evidence, now).takeIf { it.resolve(evidence, now) != null }
        }

        private fun item(row: BasketLineEvidence, product: ConfiguredProduct, restaurant: String, now: Long, window: Long): CanonicalItem? {
            if (!product.optionsComplete || product.product.size != null ||
                product.source.provider != ProviderId.HUNGERSTATION ||
                product.source.providerVersion != HungerStationCatalog.TESTED_VERSION || !product.source.fresh(now, window) ||
                restaurant != product.restaurant) return null
            val allGroups = product.product.groups
            val variant = if (row.name == product.product.title) null else allGroups.singleOrNull { g ->
                g.title == "اختر العنصر" && g.minimum == 1 && g.maximum == 1 &&
                    row.name.startsWith(product.product.title + " - ") && g.choices.count { it.available && it.title == row.name } == 1
            } ?: if (row.name != product.product.title) return null else null
            val groups = allGroups.filter { it != variant && it.minimum > 0 }
            if (row.quantity != 1 || row.lineAmount == null || row.lineAmount < 0 ||
                groups.isEmpty() || groups.size > 12 ||
                groups.any { it.minimum != 1 || it.maximum != 1 } || row.choices.size != groups.size ||
                row.choices.any { it.quantity != null && it.quantity != 1 } ||
                row.choices.map { it.value }.distinct().size != row.choices.size) return null
            val used = mutableSetOf<String>()
            val options = row.choices.map { choice ->
                val group = allGroups.singleOrNull { g -> g.choices.count { it.available && it.title == choice.value } == 1 }
                    ?: return null
                if (group !in groups) return null // Optional selections need a separate multiplicity proof.
                if (!used.add(group.id)) return null
                CartOption(group.title, choice.value)
            }
            if (used.size != groups.size) return null
            return CanonicalItem(row.name, null, options, 1)
        }
    }
}
