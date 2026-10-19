package app.octopus.local.automation

import app.octopus.local.domain.*
import app.octopus.local.providers.*

/** One observed fixed meal, one item, no checkout command and no replacement of existing carts. */
class RidanCartProgram private constructor(private val issuedAt: Long) {
    private var attempted = false
    fun consumeAdd(frame: CatalogFrame, node: CatalogNode, now: Long): Boolean {
        if (attempted || now < issuedAt || now - issuedAt > 120_000 || !frame.complete ||
            HungerStationCatalog.screen(frame) != CatalogScreen.PRODUCT ||
            frame.nodes.count { !it.clickable && IdentityText.clean(it.label) == TITLE } != 1 ||
            frame.nodes.count { !it.clickable && it.label.trim() in setOf("1", "١") } != 1 ||
            frame.nodes.any { it.editable } ||
            frame.nodes.singleOrNull { it.id == "flutterAddButton" } != node ||
            !node.enabled || !node.clickable || node.className != "android.widget.Button" ||
            !HungerStationProductProgram.isAddLabel(node.label)) return false
        // Acknowledgement is deliberately not needed: even a failed dispatch cannot be repeated.
        attempted = true
        return true
    }
    companion object {
        const val RESTAURANT = "ريدان"
        const val TITLE = "نصف دجاج مضغوط"
        fun supports(details: ConfiguredProduct, selection: ProductSelection, now: Long): Boolean =
            details.restaurant == RESTAURANT && details.product.title == TITLE &&
                details.source.provider == ProviderId.HUNGERSTATION &&
                details.source.providerVersion == HungerStationCatalog.TESTED_VERSION &&
                details.product.groups.isEmpty() && selection.quantity == 1 && selection.choices.isEmpty() &&
                MenuSelectionValidator().selectConfigured(details, selection, now) is SelectionResult.Accepted
        fun create(details: ConfiguredProduct, selection: ProductSelection, emptyMenu: CatalogFrame, now: Long): RidanCartProgram? {
            if (!supports(details, selection, now) || !emptyMenu.complete ||
                HungerStationCatalog.screen(emptyMenu) != CatalogScreen.MENU ||
                emptyMenu.nodes.none { !it.clickable && it.label.lineSequence().any { line -> IdentityText.clean(line) == RESTAURANT } } ||
                emptyMenu.nodes.any { it.id in setOf("FlutterMenu_goToCartButton", "FlutterMenu_goToCartQuantity", "menuItemActionDualButtonValue") }) return null
            return RidanCartProgram(now)
        }
    }
}
