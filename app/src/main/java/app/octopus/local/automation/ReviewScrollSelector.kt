package app.octopus.local.automation

import app.octopus.local.diagnostics.InspectionRedactor
import app.octopus.local.providers.CatalogFrame
import app.octopus.local.providers.CatalogNode

/** Selects a scroll container only. Does not recognize a review or authorize any click. */
object ReviewScrollSelector {
    fun target(frame: CatalogFrame, recognizedReview: Boolean): CatalogNode? {
        if (!recognizedReview || !frame.complete || frame.nodes.size > 2500) return null
        val byIndex = frame.nodes.associateBy { it.index }
        if (byIndex.size != frame.nodes.size) return null
        fun ancestry(node: CatalogNode): List<CatalogNode>? {
            val result = mutableListOf<CatalogNode>()
            val visited = mutableSetOf(node.index)
            var parent = node.parent
            while (parent != -1) {
                if (!visited.add(parent)) return null
                val ancestor = byIndex[parent] ?: return null
                result += ancestor
                parent = ancestor.parent
            }
            return result
        }
        fun safe(node: CatalogNode) = !node.editable && !node.enteredText && node.enabled &&
            !InspectionRedactor.privateSubtree(node.id)
        val candidates = frame.nodes.filter { node ->
            node.scrollable && node.className in setOf("android.view.View", "android.widget.ScrollView") &&
                safe(node) && ancestry(node)?.all(::safe) == true
        }
        val candidateIndices = candidates.mapTo(mutableSetOf()) { it.index }
        // Observed HS review has an outer vertical body and a nested carousel. Only
        // the outer body can reveal more price rows; sibling bodies remain ambiguous.
        return candidates.singleOrNull { node ->
            ancestry(node)?.none { it.index in candidateIndices } == true
        }
    }
}
