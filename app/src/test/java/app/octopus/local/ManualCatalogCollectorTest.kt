package app.octopus.local

import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test

/** Shapes reuse recorded HS anchors; this proves parsing boundaries, not a live provider run. */
class ManualCatalogCollectorTest {
    private val restaurant = LiveRestaurant("مدور", null, "مدور")
    private val other = LiveRestaurant("مطعم آخر", null, "مطعم آخر")
    private val item = LiveMenuItem("فطيرة جبن فيتا", 700, "gridMenuCell-0-0")
    private val second = LiveMenuItem("فطيرة زعتر", 600, "gridMenuCell-0-1")
    private fun node(index: Int, id: String = "", label: String = "", click: Boolean = false, parent: Int = -1,
        editable: Boolean = false) = CatalogNode(index, parent, id, label, "android.view.View", click, editable, false)

    private fun browse(vararg names: String): CatalogFrame = CatalogFrame(listOf(
        node(0, "test:id/vendorsRecycler"), node(1, "test:id/hsBackButton")) +
        names.mapIndexed { i, name -> node(i + 2, "test:id/vendor_name", name, parent = 0) }, true)

    private fun menu(name: String = restaurant.name, items: List<LiveMenuItem> = listOf(item), titleClickable: Boolean = false) =
        CatalogFrame(listOf(node(0, "menuBackButton"), node(1, "menuSearchButton"), node(2, label = name, click = titleClickable)) +
            items.mapIndexed { i, value -> node(i + 3, value.selector, "${value.name}\n§\n${value.listedPrice!! / 100}", click = true) }, true)

    private fun product(top: Boolean, bottom: Boolean, title: String = item.name, required: Boolean = false,
        secondGroup: Boolean = false): CatalogFrame {
        val rows = mutableListOf(node(0, "pdpCloseButton", click = true))
        if (top) rows += node(1, "pdpZoomButton", click = true)
        rows += node(2, label = title)
        val labels = if (required) mutableListOf(
            (if (secondGroup) "الأرز" else "اختر الحجم") to false,
            "مطلوب" to false, "اختيار واحد" to false,
            (if (secondGroup) "رز بشاور\n(§\n5\n+)" else "نص\n720+ طلب") to true)
        else mutableListOf("يمكنك اضافة" to false, "اختيار واحد" to false, "بطاطس\n(§\n6\n+)" to true)
        if (bottom) labels += "غالبًا ما يتم طلبه مع" to false
        rows += labels.mapIndexed { i, (label, clickable) -> node(i + 3, label = label, click = clickable) }
        rows += node(labels.size + 3, "flutterAddButton", "إضافة\n§\n7", click = true)
        return CatalogFrame(rows, true)
    }

    private fun prime(collector: ManualCatalogCollector, name: String = restaurant.name) {
        collector.observe(browse(name), 1_000)
        assertNotNull(collector.observe(menu(name), 2_000).menu)
    }

    @Test fun unknownRestaurantOrClickableNameCannotBecomeAMenuIdentity() {
        val collector = ManualCatalogCollector()
        assertNull(collector.observe(menu(), 1_000).menu)
        assertNull(collector.observe(menu(titleClickable = true), 1_001, listOf(restaurant)).menu)
        assertNull(collector.observe(menu(name = "مدور الجديد"), 1_002, listOf(restaurant)).menu)
        assertNotNull(collector.observe(menu(), 1_003, listOf(restaurant)).menu)
    }

    @Test fun observedResultCardsCanSeedIdentityWithoutReadingSearchInputOrInventingARestaurant() {
        val collector = ManualCatalogCollector()
        val results = CatalogFrame(listOf(node(0, "test:id/searchVendorsRecycler"),
            node(1, click = true, parent = 0), node(2, "test:id/title", restaurant.name, parent = 1),
            node(3, "test:id/description", "فطائر", parent = 1),
            node(4, "test:id/product_name", "اسم وجبة", parent = 1)), true)
        val rows = collector.observe(results, 1_000).restaurants
        assertEquals(listOf(restaurant.name), rows.map { it.name })
        assertEquals(restaurant.name, rows.single().searchQuery)
        assertNotNull(collector.observe(menu(), 2_000).menu)
    }

    @Test fun knownMenuStillNeedsItsCurrentVisibleRestaurantTitle() {
        val saved = LiveMenu(restaurant, listOf(item), 100, false)
        val collector = ManualCatalogCollector()
        assertNull(collector.observe(menu(name = "اسم غير معروف"), 1_000, knownMenus = listOf(saved)).menu)
        assertNull(collector.observe(product(top = true, bottom = true), 1_001, knownMenus = listOf(saved)).product)
    }

    @Test fun menuAccumulatesVisibleRowsButNeverClaimsItsEndOrRefreshesIdenticalData() {
        val collector = ManualCatalogCollector()
        assertEquals(listOf(restaurant), collector.observe(browse(restaurant.name), 1_000).restaurants)
        assertFalse(collector.observe(browse(restaurant.name), 1_001).hasData)
        val first = collector.observe(menu(), 2_000).menu!!
        assertEquals(2_000L, first.capturedAtMillis)
        assertFalse(first.reachedEnd)
        assertFalse(collector.observe(menu(), 9_000).hasData)
        val next = collector.observe(menu(items = listOf(second)), 10_000).menu!!
        assertEquals(listOf(second), next.items)
        assertEquals(10_000L, next.capturedAtMillis)
        assertFalse(next.reachedEnd)
        // The earlier row remains usable as internal identity context, but is
        // not emitted with the newly observed row's timestamp.
        assertNotNull(collector.observe(product(top = true, bottom = true), 11_000).product)
    }

    @Test fun optionsNeedAProvenMenuThenTopAndEndAndAreNeverInferredFromAListingPrice() {
        val collector = ManualCatalogCollector()
        prime(collector)
        val first = collector.observe(product(top = true, bottom = false), 3_000).product!!
        assertFalse(first.optionsComplete)
        val complete = collector.observe(product(top = false, bottom = true), 4_000).product!!
        assertTrue(complete.optionsComplete)
        assertEquals(0, complete.product.groups.single().minimum)
        assertEquals("بطاطس", complete.product.groups.single().choices.single().title)
        assertEquals(4_000L, complete.source.capturedAtMillis)
        assertFalse(collector.observe(product(top = false, bottom = true), 90_000).hasData)
        assertEquals(4_000L, complete.source.capturedAtMillis)
    }

    @Test fun initialBottomIsPartialAndGoingToTopRestartsCoverageBeforeNewEnd() {
        val collector = ManualCatalogCollector()
        prime(collector)
        val initialBottom = collector.observe(product(top = false, bottom = true), 3_000)
        assertFalse(initialBottom.product!!.optionsComplete)
        assertNotNull(initialBottom.reason)
        assertFalse(collector.observe(product(top = true, bottom = false), 4_000).product!!.optionsComplete)
        assertTrue(collector.observe(product(top = false, bottom = true), 5_000).product!!.optionsComplete)
    }

    @Test fun requiredGroupsAccumulateAcrossUserScrolledFrames() {
        val collector = ManualCatalogCollector()
        prime(collector)
        collector.observe(product(top = true, bottom = false, required = true), 3_000)
        val result = collector.observe(product(top = false, bottom = true, required = true, secondGroup = true), 4_000).product!!
        assertTrue(result.optionsComplete)
        assertEquals(listOf("اختر الحجم", "الأرز"), result.product.groups.map { it.title })
        assertEquals(listOf(1, 1), result.product.groups.map { it.minimum })
    }

    @Test fun unknownAndIncompleteScreensBreakMenuProductContinuity() {
        val collector = ManualCatalogCollector()
        prime(collector)
        collector.observe(product(top = true, bottom = false), 3_000)
        collector.observe(CatalogFrame(emptyList(), true), 4_000)
        assertNull(collector.observe(product(top = false, bottom = true), 5_000).product)
        collector.observe(menu(), 6_000)
        collector.observe(menu().copy(complete = false), 7_000)
        assertNull(collector.observe(product(top = true, bottom = true), 8_000).product)
    }

    @Test fun anotherRestaurantCannotInheritPreviousRestaurantsOptionCoverage() {
        val collector = ManualCatalogCollector()
        collector.observe(browse(restaurant.name, other.name), 1_000)
        collector.observe(menu(), 2_000)
        collector.observe(product(top = true, bottom = false, required = true), 3_000)
        collector.observe(menu(name = other.name), 4_000)
        val result = collector.observe(product(top = false, bottom = true), 5_000).product!!
        assertEquals(other.name, result.restaurant)
        assertFalse(result.optionsComplete)
        assertFalse(result.product.groups.any { it.title == "اختر الحجم" })
    }

    @Test fun ambiguousRestaurantAndProductTitlesFailClosed() {
        val collector = ManualCatalogCollector()
        val ambiguous = menu().let { it.copy(nodes = it.nodes + node(40, label = other.name)) }
        assertNull(collector.observe(ambiguous, 1_000, listOf(restaurant, other)).menu)
        collector.observe(menu(items = listOf(item, second)), 2_000)
        val mixed = product(top = true, bottom = true).let { it.copy(nodes = it.nodes + node(40, label = second.name)) }
        assertNull(collector.observe(mixed, 3_000).product)
        assertNull(collector.observe(product(top = true, bottom = true), 4_000).product)
    }

    @Test fun privateSubtreeAndEditableTextDoNotEnterProductBodiesOrRenewTimestamps() {
        val collector = ManualCatalogCollector()
        prime(collector)
        fun privateFrame(value: String) = product(top = true, bottom = true).let {
            it.copy(nodes = it.nodes + listOf(node(50, "delivery_address", value),
                node(51, label = value, parent = 50), node(52, "notes", value, editable = true)))
        }
        val first = collector.observe(privateFrame("private-first"), 3_000).product!!
        assertTrue(first.optionsComplete)
        assertFalse(first.product.groups.any { group -> group.title.contains("private") || group.choices.any { it.title.contains("private") } })
        assertFalse(collector.observe(privateFrame("private-second"), 9_000).hasData)
    }

    @Test fun unsupportedOptionGrammarNeverBecomesComplete() {
        val collector = ManualCatalogCollector()
        prime(collector)
        val unsupported = product(top = true, bottom = false).let {
            it.copy(nodes = it.nodes + node(40, label = "اختيار اثنين") + node(41, label = "غالبًا ما يتم طلبه مع"))
        }
        val result = collector.observe(unsupported, 3_000)
        assertFalse(result.product!!.optionsComplete)
        assertNotNull(result.reason)
    }

    @Test fun resetRemovesLearnedIdentityAndDoesNotResurrectDeletedCache() {
        val collector = ManualCatalogCollector()
        prime(collector)
        collector.reset()
        val result = collector.observe(menu(), 5_000)
        assertNull(result.menu)
        assertNotNull(result.reason)
        assertNull(collector.observe(product(top = true, bottom = true), 6_000).product)
    }

    @Test fun firstObservedSeededPageEmitsOnlyVisibleRowsAndRepeatedPollDoesNotRenewIt() {
        val saved = LiveMenu(restaurant, listOf(item, second), 100, false)
        val collector = ManualCatalogCollector()
        val first = collector.observe(menu(items = listOf(second)), 5_000, knownMenus = listOf(saved)).menu!!
        assertEquals(listOf(second), first.items)
        assertEquals(5_000L, first.capturedAtMillis)
        assertFalse(collector.observe(menu(items = listOf(second)), 8_000, knownMenus = listOf(saved)).hasData)
    }
}
