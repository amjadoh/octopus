package app.octopus.local.safety

import app.octopus.local.domain.ProviderId
import app.octopus.local.providers.CatalogFrame
import app.octopus.local.providers.CatalogNode
import app.octopus.local.providers.CatalogScreen
import app.octopus.local.providers.HungerStationCatalog

/** Resolves only an observed action target; an arbitrary clickable ancestor is never sufficient. */
object CatalogTargetGuard {
    private fun ordinary(node: CatalogNode, provider: ProviderId): Boolean =
        node.enabled && CatalogFinancialBoundary.classify(node.id, node.className, node.clickable,
            node.editable, provider) == CatalogFinancialBoundary.Decision.NORMAL

    fun matches(expected: CatalogNode, actual: CatalogNode, provider: ProviderId): Boolean =
        ordinary(expected, provider) && ordinary(actual, provider) &&
            expected.id == actual.id && expected.label == actual.label &&
            expected.className == actual.className && expected.clickable == actual.clickable &&
            expected.editable == actual.editable && expected.scrollable == actual.scrollable

    fun clickTarget(frame: CatalogFrame, selected: CatalogNode, action: CatalogAction,
        provider: ProviderId): CatalogNode? {
        if (!frame.complete || frame.nodes.singleOrNull { it.index == selected.index } != selected ||
            !ordinary(selected, provider) || selected.editable) return null
        if (selected.clickable) return selected
        // The observed search result title is a TextView inside a clickable RelativeLayout card.
        if (provider != ProviderId.HUNGERSTATION || action != CatalogAction.OPEN_RESTAURANT ||
            HungerStationCatalog.screen(frame) != CatalogScreen.RESULTS ||
            !selected.id.endsWith(":id/title") || selected.className != "android.widget.TextView" ||
            selected.label.isBlank()) return null
        val visited = mutableSetOf(selected.index)
        var current = selected
        while (current.parent >= 0) {
            if (!visited.add(current.parent)) return null
            current = frame.nodes.singleOrNull { it.index == current.parent } ?: return null
            if (!ordinary(current, provider) || current.editable || current.scrollable) return null
            if (current.clickable) {
                val listing = frame.nodes.singleOrNull { it.index == current.parent } ?: return null
                return current.takeIf {
                    it.id.isEmpty() && it.className == "android.widget.RelativeLayout" &&
                        listing.id.endsWith(":id/searchVendorsRecycler") &&
                        frame.descendants(it.index).count { child ->
                            child.id.endsWith(":id/title") && child.label.isNotBlank()
                        } == 1
                }
            }
        }
        return null
    }
}
