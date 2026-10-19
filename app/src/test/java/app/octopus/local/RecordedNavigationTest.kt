package app.octopus.local

import app.octopus.local.automation.*
import app.octopus.local.providers.*
import app.octopus.local.safety.*
import app.octopus.local.domain.ProviderId
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/** Replay of redacted saved UI structure, not a new handset or provider integration test. */
class RecordedNavigationTest {
    private fun frame(name: String): CatalogFrame {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val input = checkNotNull(javaClass.getResourceAsStream("/recorded-hs/$name.xml"))
        val document = input.use { factory.newDocumentBuilder().parse(it) }
        val nodes = mutableListOf<CatalogNode>()
        fun children(element: Element, parent: Int) {
            val list = element.childNodes
            for (i in 0 until list.length) {
                val child = list.item(i) as? Element ?: continue
                val index = nodes.size
                val cls = child.getAttribute("class")
                nodes += CatalogNode(index, parent, child.getAttribute("resource-id"), child.getAttribute("label"),
                    cls, child.getAttribute("clickable") == "true", cls.contains("EditText"),
                    child.getAttribute("scrollable") == "true", child.getAttribute("enabled") == "true")
                children(child, index)
            }
        }
        children(document.documentElement, -1)
        return CatalogFrame(nodes, true)
    }

    @Test fun savedProductPagesAreReadAcrossTheRecordedScrollPositions() {
        val reader = HungerStationProductReader(LiveRestaurant("المضياف العربي", null, "المضياف"),
            LiveMenuItem("دجاج مظبي", null, "gridMenuCell-0-0"))
        listOf("hs-item-options", "hs-item-options-scrolled", "hs-item-options-bottom").forEach { name ->
            val screen = frame(name)
            assertEquals(CatalogScreen.PRODUCT, HungerStationCatalog.screen(screen))
            reader.observe(screen)
        }
        val product = reader.finish(1000)
        assertTrue(product.optionsComplete)
        assertEquals(listOf("اختر الحجم", "إضافات"), product.product.groups.map { it.title })
        assertTrue(product.product.groups.last().choices.any { it.title == "رز بشاور" })
    }

    @Test fun productScrollCannotDispatchClickOrSetText() {
        assertEquals(ElementOperation.SCROLL_FORWARD, CatalogAction.SCROLL_PRODUCT.operation())
        assertEquals(ElementOperation.SCROLL_FORWARD, CatalogAction.SCROLL_MENU.operation())
        assertEquals(ElementOperation.SET_TEXT, CatalogAction.SET_QUERY.operation())
        val pdp = frame("hs-item-options-scrolled")
        assertEquals(1, pdp.nodes.count { it.scrollable && it.className == "android.widget.ScrollView" })
    }

    @Test fun savedSearchBackControlResolvesItsClickableChildNotTheEntireHeader() {
        val search = frame("hs-search")
        val target = checkNotNull(CatalogCommands.backTarget(search))
        assertTrue(target.clickable)
        assertEquals("android.widget.ImageView", target.className)
        assertEquals("UniversalSearch_back_button", search.nodes.single { it.index == target.parent }.id)
        assertFalse(target.editable)
    }

    @Test fun returnPathUsesOnlyRecordedBackControls() {
        val expected = mapOf("hs-item-options" to "pdpCloseButton", "hs-menu" to "menuBackButton",
            "hs-prepared-cart" to "FlutterCart_back_button", "hs-vendor-results" to "com.hungerstation.android.web:id/backButton")
        expected.forEach { (file, id) -> assertEquals(id, CatalogCommands.backTarget(frame(file))?.id) }
        assertNull(CatalogCommands.backTarget(frame("hs-prepared-cart").copy(complete = false)))
        assertNull(CatalogCommands.backTarget(CatalogFrame(emptyList(), true)))
    }

    @Test fun ambiguousDisabledOrUnknownBackTargetsNeverAuthorizeNavigation() {
        val search = frame("hs-search")
        val target = checkNotNull(CatalogCommands.backTarget(search))
        assertNull(CatalogCommands.backTarget(search.copy(nodes = search.nodes + target.copy(index = 999))))
        assertNull(CatalogCommands.backTarget(search.copy(nodes = search.nodes.map {
            if (it.index == target.index) it.copy(enabled = false) else it
        })))
        val guard = SafetyGuard()
        assertFalse(guard.allowsCatalogAction(CatalogAction.RETURN_TO_CATALOG, CatalogScreen.UNKNOWN,
            ProviderId.HUNGERSTATION.packageName, true, true, true, ""))
        assertFalse(guard.allowsCatalogAction(CatalogAction.RETURN_TO_CATALOG, CatalogScreen.CART,
            ProviderId.HUNGERSTATION.packageName, true, true, true, "تأكيد الطلب"))
    }
}
