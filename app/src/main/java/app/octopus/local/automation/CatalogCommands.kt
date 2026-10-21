package app.octopus.local.automation

import app.octopus.local.providers.*
import app.octopus.local.safety.CatalogAction

enum class ElementOperation { CLICK, SET_TEXT, SCROLL_FORWARD }

/** Exhaustive mapping: adding an action can never silently turn a scroll into a click. */
fun CatalogAction.operation(): ElementOperation = when (this) {
    CatalogAction.SET_QUERY -> ElementOperation.SET_TEXT
    CatalogAction.SCROLL_RESTAURANTS, CatalogAction.SCROLL_MENU, CatalogAction.SCROLL_PRODUCT -> ElementOperation.SCROLL_FORWARD
    CatalogAction.OPEN_BROWSE, CatalogAction.CLOSE_BROWSE_PROMO, CatalogAction.OPEN_BASKET, CatalogAction.ADD_RIDAN, CatalogAction.ADJUST_RIDAN_QUANTITY, CatalogAction.OPEN_SEARCH, CatalogAction.SELECT_SUGGESTION, CatalogAction.OPEN_RESTAURANT,
    CatalogAction.VIEW_MENU_NOTICE, CatalogAction.OPEN_PRODUCT, CatalogAction.RETURN_TO_CATALOG,
    CatalogAction.CLOSE_SPIN_PROMO -> ElementOperation.CLICK
}

object CatalogCommands {
    /** Back controls and the search control's child are present in the saved device observations. */
    fun backTarget(frame: CatalogFrame): CatalogNode? {
        HungerStationSpinPromo.closeTarget(frame)?.let { return it }
        val id = when (HungerStationCatalog.screen(frame)) {
            CatalogScreen.PRODUCT -> "pdpCloseButton"
            CatalogScreen.CART -> "FlutterCart_back_button"
            CatalogScreen.MENU -> "menuBackButton"
            CatalogScreen.BROWSE -> "com.hungerstation.android.web:id/hsBackButton"
            CatalogScreen.RESULTS -> "com.hungerstation.android.web:id/backButton"
            CatalogScreen.SEARCH -> "UniversalSearch_back_button"
            else -> return null
        }
        val anchor = frame.nodes.singleOrNull { it.id == id && it.enabled } ?: return null
        if (anchor.clickable) return anchor
        if (id != "UniversalSearch_back_button") return null
        return frame.descendants(anchor.index).filter {
            it.parent == anchor.index && it.clickable && it.enabled && !it.editable &&
                it.id.isEmpty() && it.className == "android.widget.ImageView"
        }.singleOrNull()
    }
}
