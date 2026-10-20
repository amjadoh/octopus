package app.octopus.local

import app.octopus.local.domain.ProviderId
import app.octopus.local.providers.CatalogFrame
import app.octopus.local.providers.CatalogNode
import app.octopus.local.safety.CatalogAction
import app.octopus.local.safety.CatalogFinancialBoundary
import app.octopus.local.safety.CatalogTargetGuard
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class CatalogTargetGuardTest {
    private val provider = ProviderId.HUNGERSTATION
    private fun node(index: Int, parent: Int = -1, id: String = "", label: String = "",
        clickable: Boolean = true, cls: String = "android.widget.Button") =
        CatalogNode(index, parent, id, label, cls, clickable, false, false)

    @Test fun financialSubtreeDoesNotShiftTheIndexOfTheFollowingAllowedControl() {
        val root = node(0, clickable = false, cls = "android.view.View")
        val financial = node(1, 0, "FlutterCart_checkoutButton")
        val hiddenChild = node(2, 1, label = "child that must never be visited")
        val allowed = node(3, 0, "FlutterCart_back_button", label = "رجوع")
        val raw = listOf(root, financial, hiddenChild, allowed)
        val visited = mutableListOf<CatalogNode>()
        fun visit(current: CatalogNode, parent: Int) {
            val index = visited.size
            visited += current.copy(index = index, parent = parent)
            if (CatalogFinancialBoundary.permitsChildren(current.id, current.className,
                    current.clickable, current.editable, provider))
                raw.filter { it.parent == current.index }.forEach { visit(it, index) }
        }
        visit(root, -1)
        assertEquals(listOf("", "FlutterCart_checkoutButton", "FlutterCart_back_button"), visited.map { it.id })
        val expected = visited[2]
        assertTrue(CatalogTargetGuard.matches(expected, allowed, provider))
        // An unfiltered lookup would resolve index 2 to the financial button's child.
        assertFalse(CatalogTargetGuard.matches(expected, raw[2], provider))
    }

    @Test fun protectedAndEditableBranchesAreNeverTraversed() {
        listOf("payment_container", "card_number", "checkoutButton").forEach {
            assertFalse(CatalogFinancialBoundary.permitsChildren(it, "android.widget.Button", true, false, provider))
        }
        assertFalse(CatalogFinancialBoundary.permitsChildren("", "android.widget.EditText", true, true, provider))
        assertTrue(CatalogFinancialBoundary.permitsChildren("menuBackButton", "android.widget.Button", true, false, provider))
    }

    @Test fun resolvedTargetMustStillMatchIdentityAndSupportedProperties() {
        val expected = node(0, id = "menuBackButton", label = "رجوع")
        assertTrue(CatalogTargetGuard.matches(expected, expected, provider))
        listOf(expected.copy(id = "other"), expected.copy(label = "آخر"), expected.copy(className = "android.view.View"),
            expected.copy(clickable = false), expected.copy(editable = true), expected.copy(scrollable = true),
            expected.copy(enabled = false)).forEach {
            assertFalse(CatalogTargetGuard.matches(expected, it, provider))
        }
    }

    @Test fun financialTargetIsDeniedEvenWhenItsLabelIsBlankAndIdentityMatches() {
        val financial = node(0, id = "FlutterCart_checkoutButton")
        assertFalse(CatalogTargetGuard.matches(financial, financial, provider))
        assertNull(CatalogTargetGuard.clickTarget(CatalogFrame(listOf(financial), true), financial,
            CatalogAction.RETURN_TO_CATALOG, provider))
    }

    @Test fun alreadyClickableControlKeepsItsOwnIdentity() {
        val target = node(0, id = "menuBackButton")
        assertEquals(target, CatalogTargetGuard.clickTarget(CatalogFrame(listOf(target), true),
            target, CatalogAction.RETURN_TO_CATALOG, provider))
        assertNull(CatalogTargetGuard.clickTarget(CatalogFrame(listOf(target), false),
            target, CatalogAction.RETURN_TO_CATALOG, provider))
    }

    @Test fun ordinaryNonclickableNodesDoNotAuthorizeClickableAncestors() {
        val parent = node(0)
        val child = node(1, 0, "menuBackButton", clickable = false)
        assertNull(CatalogTargetGuard.clickTarget(CatalogFrame(listOf(parent, child), true),
            child, CatalogAction.RETURN_TO_CATALOG, provider))
    }

    @Test fun syntheticTitleInRecordedRestaurantCardResolvesOnlyThatCard() {
        val frame = recordedResults()
        val title = frame.nodes.first { it.id.endsWith(":id/title") && it.label.isNotBlank() }
        val target = checkNotNull(CatalogTargetGuard.clickTarget(frame, title, CatalogAction.OPEN_RESTAURANT, provider))
        assertEquals("android.widget.RelativeLayout", target.className)
        assertTrue(frame.nodes.single { it.index == target.parent }.id.endsWith(":id/searchVendorsRecycler"))
        assertNull(CatalogTargetGuard.clickTarget(frame, title, CatalogAction.OPEN_PRODUCT, provider))
        assertNull(CatalogTargetGuard.clickTarget(frame, title, CatalogAction.OPEN_RESTAURANT, ProviderId.KEETA))
    }

    @Test fun changedFinancialOrAmbiguousRestaurantAncestorsAreRejected() {
        val frame = recordedResults()
        val title = frame.nodes.first { it.id.endsWith(":id/title") && it.label.isNotBlank() }
        val target = checkNotNull(CatalogTargetGuard.clickTarget(frame, title, CatalogAction.OPEN_RESTAURANT, provider))
        listOf(target.copy(id = "checkoutButton"), target.copy(className = "android.widget.FrameLayout"),
            target.copy(enabled = false)).forEach { changed ->
            assertNull(CatalogTargetGuard.clickTarget(frame.copy(nodes = frame.nodes.map {
                if (it.index == target.index) changed else it
            }), title, CatalogAction.OPEN_RESTAURANT, provider))
        }
        assertNull(CatalogTargetGuard.clickTarget(frame.copy(nodes = frame.nodes +
            title.copy(index = frame.nodes.size, parent = target.index, label = "مطعم آخر")),
            title, CatalogAction.OPEN_RESTAURANT, provider))
    }

    private fun recordedResults(): CatalogFrame {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val document = checkNotNull(javaClass.getResourceAsStream("/recorded-hs/hs-vendor-results.xml")).use {
            factory.newDocumentBuilder().parse(it)
        }
        val nodes = mutableListOf<CatalogNode>()
        fun walk(element: Element, parent: Int) {
            for (i in 0 until element.childNodes.length) {
                val child = element.childNodes.item(i) as? Element ?: continue
                val index = nodes.size
                val cls = child.getAttribute("class")
                nodes += CatalogNode(index, parent, child.getAttribute("resource-id"), child.getAttribute("label"), cls,
                    child.getAttribute("clickable") == "true", cls.contains("EditText"),
                    child.getAttribute("scrollable") == "true", child.getAttribute("enabled") == "true")
                walk(child, index)
            }
        }
        walk(document.documentElement, -1)
        // The saved fixture deliberately removed arbitrary restaurant titles and their IDs.
        // Restore a synthetic title on its retained card structure for this policy test.
        val listing = nodes.single { it.id.endsWith(":id/searchVendorsRecycler") }
        val card = nodes.first { it.parent == listing.index && it.className == "android.widget.RelativeLayout" && it.clickable }
        val group = nodes.first { it.parent == card.index && it.className == "android.view.ViewGroup" }
        val title = nodes.first { it.parent == group.index && it.className == "android.widget.TextView" }
        nodes[title.index] = title.copy(id = "com.hungerstation.android.web:id/title", label = "مطعم اختباري")
        return CatalogFrame(nodes, true)
    }
}
