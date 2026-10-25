package app.octopus.local

import app.octopus.local.automation.ReviewScrollSelector
import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test

class ReviewScrollSelectorTest {
    private fun node(index: Int, parent: Int, scroll: Boolean = false, id: String = "", cls: String = "android.view.View") =
        CatalogNode(index, parent, id, "", cls, false, false, scroll)
    private fun recordedFrame() = CatalogFrame(listOf(
        // Relationships preserved from hs-review-grouped-pricing.json; no account text.
        node(7, -1), node(11, 7), node(12, 11, true),
        node(15, 12), node(16, 15, true), node(26, 12)), true)

    @Test fun recordedNestedScrollerDoesNotPreventScrollingMainReviewBody() {
        assertEquals(12, ReviewScrollSelector.target(recordedFrame(), true)?.index)
        assertEquals(12, ReviewScrollSelector.target(recordedFrame().copy(nodes = recordedFrame().nodes.reversed()), true)?.index)
    }

    @Test fun incompleteUnknownAndAmbiguousSiblingBodiesAreRejected() {
        assertNull(ReviewScrollSelector.target(recordedFrame(), false))
        assertNull(ReviewScrollSelector.target(recordedFrame().copy(complete = false), true))
        assertNull(ReviewScrollSelector.target(recordedFrame().copy(nodes = recordedFrame().nodes + node(30, 7, true)), true))
    }

    @Test fun horizontalCarouselNeverBecomesTheReviewScrollTarget() {
        val frame = recordedFrame().copy(nodes = recordedFrame().nodes.map {
            if (it.index == 16) it.copy(className = "android.widget.HorizontalScrollView") else it
        })
        assertEquals(12, ReviewScrollSelector.target(frame, true)?.index)
        assertNull(ReviewScrollSelector.target(CatalogFrame(listOf(node(0, -1, true, cls = "android.widget.HorizontalScrollView")), true), true))
    }

    @Test fun privateEditableOrDisabledContainersAreNotScrolled() {
        val frame = recordedFrame()
        for (blocked in listOf(
            frame.nodes.single { it.index == 12 }.copy(id = "checkout_credit_card_number"),
            frame.nodes.single { it.index == 12 }.copy(editable = true),
            frame.nodes.single { it.index == 12 }.copy(enabled = false),
        )) {
            assertNull(ReviewScrollSelector.target(frame.copy(nodes = frame.nodes.map { if (it.index == 12) blocked else it }), true))
        }
    }

    @Test fun malformedAncestryDoesNotSelectAnArbitraryScroller() {
        val frame = recordedFrame()
        assertNull(ReviewScrollSelector.target(frame.copy(nodes = frame.nodes + frame.nodes.first()), true))
        assertNull(ReviewScrollSelector.target(CatalogFrame(listOf(node(0, 1, true), node(1, 0)), true), true))
        assertNull(ReviewScrollSelector.target(CatalogFrame(listOf(node(0, 10, true)), true), true))
    }
}
