package app.octopus.local.automation

import app.octopus.local.domain.*
import app.octopus.local.providers.*

/** A missing count is resolved only from a fresh, complete, single-choice product schema. */
class SingleChoiceMeal private constructor(
    val cart: CanonicalCart,
    val source: BasketEvidence,
    private val at: Long,
) {
    val values: Set<String> = cart.items.single().options.map { it.value }.toSet()

    fun resolve(evidence: BasketEvidence, now: Long): BasketEvidence? {
        if (now < at || now - at > 600_000 || !evidence.complete ||
            IdentityText.clean(evidence.restaurant.orEmpty()) != IdentityText.clean(cart.restaurant)) return null
        val row = evidence.lines.singleOrNull() ?: return null
        if (row.name != cart.items.single().name || row.quantity != 1 || row.lineAmount == null || row.lineAmount < 0 ||
            evidence.subtotal != row.lineAmount || row.choices.size != values.size ||
            row.choices.map { it.value }.toSet() != values || row.choices.any { it.quantity != null && it.quantity != 1 }) return null
        return evidence.copy(lines = listOf(row.copy(choices = row.choices.map { it.copy(quantity = 1) })))
    }

    companion object {
        fun create(evidence: BasketEvidence, product: ConfiguredProduct, now: Long): SingleChoiceMeal? {
            if (evidence.provider != ProviderId.HUNGERSTATION || !evidence.complete ||
                !product.optionsComplete || product.product.size != null ||
                product.source.provider != ProviderId.HUNGERSTATION ||
                product.source.providerVersion != HungerStationCatalog.TESTED_VERSION || !product.source.fresh(now, 30_000) ||
                evidence.restaurant != product.restaurant) return null
            val row = evidence.lines.singleOrNull() ?: return null
            val groups = product.product.groups
            if (row.quantity != 1 || row.name != product.product.title || row.lineAmount == null || row.lineAmount < 0 ||
                evidence.subtotal != row.lineAmount || groups.isEmpty() || groups.size > 12 ||
                groups.any { it.minimum != 1 || it.maximum != 1 } || row.choices.size != groups.size ||
                row.choices.any { it.quantity != null && it.quantity != 1 } ||
                row.choices.map { it.value }.distinct().size != row.choices.size) return null
            val used = mutableSetOf<String>()
            val options = row.choices.map { choice ->
                val group = groups.singleOrNull { g -> g.choices.count { it.available && it.title == choice.value } == 1 }
                    ?: return null
                if (!used.add(group.id)) return null
                CartOption(group.title, choice.value)
            }
            if (used.size != groups.size) return null
            val cart = CanonicalCart(evidence.restaurant ?: return null, null,
                listOf(CanonicalItem(row.name, null, options, 1)))
            return SingleChoiceMeal(cart, evidence, now).takeIf { it.resolve(evidence, now) != null }
        }
    }
}
