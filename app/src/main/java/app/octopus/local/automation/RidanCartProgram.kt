package app.octopus.local.automation

import app.octopus.local.domain.*
import app.octopus.local.providers.*

/** Selected meal using the observed option-free layout, one item, no checkout command and no replacement of existing carts. */
class RidanCartProgram private constructor(private val issuedAt: Long, private val restaurant: String, private val title: String, private val expectedUnit: Long, private val productId: String) {
    private var attempted = false
    fun consumeAdd(frame: CatalogFrame, node: CatalogNode, now: Long): Boolean {
        if (attempted || now < issuedAt || now - issuedAt > 120_000 || !frame.complete ||
            HungerStationCatalog.screen(frame) != CatalogScreen.PRODUCT ||
            frame.nodes.count { !it.clickable && IdentityText.clean(it.label) == IdentityText.clean(title) } != 1 ||
            frame.nodes.count { !it.clickable && it.label.trim() in setOf("1", "١") } != 1 ||
            frame.nodes.any { it.editable } || !plainOptionsVerified(frame, now) ||
            frame.nodes.singleOrNull { it.id == "flutterAddButton" } != node ||
            !node.enabled || !node.clickable || node.className != "android.widget.Button" ||
            !HungerStationProductProgram.isAddLabel(node.label) ||
            app.octopus.local.pricing.MoneyParser.parse(node.label.lineSequence().last().trim()) != expectedUnit) return false
        // Acknowledgement is deliberately not needed: even a failed dispatch cannot be repeated.
        attempted = true
        return true
    }
    private fun plainOptionsVerified(frame: CatalogFrame, now: Long): Boolean {
        return runCatching {
            val reader = HungerStationProductReader(LiveRestaurant(restaurant, null, restaurant), LiveMenuItem(title, expectedUnit, productId))
            reader.observe(frame)
            val details = reader.finish(now)
            if (!details.optionsComplete ||
                details.product.groups.any { it.minimum != 0 || it.maximum != 1 }) return@runCatching false
            if (details.product.groups.isEmpty()) return@runCatching true
            // Only the observed paid optional section can be omitted. A selected paid choice
            // changes the add-button total, which consumeAdd separately compares with the base.
            // Free/unknown-price options cannot use this permit because price cannot prove omission.
            details.product.groups.all { group -> group.choices.all choices@ { choice ->
                val row = frame.nodes.singleOrNull { it.clickable && IdentityText.clean(it.label.lineSequence().first()) == IdentityText.clean(choice.title) }
                    ?: return@choices false
                val text = row.label.replace(Regex("[\\u200e\\u200f\\u061c\\u202a-\\u202e\\u2066-\\u2069]"), "").trim()
                val amount = Regex("\\(§\\s*\\n([0-9٠-٩]+(?:[.,٫][0-9٠-٩]{1,2})?)\\s*\\n\\+\\)$").find(text)?.groupValues?.get(1)
                amount?.let(app.octopus.local.pricing.MoneyParser::parse)?.let { it > 0 } == true
            } }
        }.getOrDefault(false)
    }
    companion object {
        const val RESTAURANT = "ريدان"
        const val TITLE = "نصف دجاج مضغوط"
        fun supports(details: ConfiguredProduct, selection: ProductSelection, now: Long): Boolean =
            details.restaurant.isNotBlank() && details.product.title.isNotBlank() &&
                details.source.provider == ProviderId.HUNGERSTATION &&
                details.source.providerVersion == HungerStationCatalog.TESTED_VERSION &&
                details.product.groups.all { it.minimum == 0 && it.maximum == 1 } &&
                details.product.listedPriceHalala != null && selection.quantity == 1 && selection.choices.values.all { it.isEmpty() } &&
                MenuSelectionValidator().selectConfigured(details, selection, now) is SelectionResult.Accepted
        fun create(details: ConfiguredProduct, selection: ProductSelection, emptyMenu: CatalogFrame, now: Long): RidanCartProgram? {
            if (!supports(details, selection, now) || !emptyMenu.complete ||
                HungerStationCatalog.screen(emptyMenu) != CatalogScreen.MENU ||
                emptyMenu.nodes.none { !it.clickable && it.label.lineSequence().any { line -> IdentityText.clean(line) == IdentityText.clean(details.restaurant) } } ||
                emptyMenu.nodes.any { it.id in setOf("FlutterMenu_goToCartButton", "FlutterMenu_goToCartQuantity", "menuItemActionDualButtonValue") }) return null
            return RidanCartProgram(now, details.restaurant, details.product.title, details.product.listedPriceHalala!!, details.product.id)
        }
    }
}
