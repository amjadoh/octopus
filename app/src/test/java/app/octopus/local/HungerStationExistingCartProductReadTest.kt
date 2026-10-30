package app.octopus.local

import app.octopus.local.automation.HungerStationProductReadSession
import app.octopus.local.providers.*
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Actual 2026-10-27 read-only captures with fries x3 already in the cart.
 * This later readable PDP does not recreate the earlier intermittent failed UI run.
 */
class HungerStationExistingCartProductReadTest {
    private val restaurant = LiveRestaurant("كودو", null, "كودو")
    private fun load(name: String) = Gson().fromJson(javaClass.getResource(
        "/recorded-hs/hs-8.0.303-fries-$name-existing-three-redacted.json")!!.readText(), CatalogFrame::class.java)
    private val search = load("search")
    private val pdp = load("pdp")
    private val item = checkNotNull(HungerStationCatalog.menuItem(search.nodes.single { it.index == 14 }))

    @Test fun actualExistingCartSearchRetainsTheUnitPriceAndSeparateCartQuantity() {
        assertEquals("بطاطس وسط", item.name)
        assertEquals(900L, item.listedPrice)
        assertEquals("3", search.nodes.single { it.id == "menuItemActionDualButtonValue" }.label)
        assertEquals(1, search.nodes.mapNotNull(HungerStationCatalog::menuItem).count { it.name == item.name })
    }

    @Test fun actualPdpReadsCompleteAvailablePlainProductWithoutScrollingOrAdding() = runBlocking {
        var captures = 0
        val read = HungerStationProductReadSession().read(restaurant, item,
            frame = { captures++; pdp }, scroll = { error("Actual options end is already visible") }, now = { 1000 })
        assertEquals(1, captures)
        assertTrue(read.details.optionsComplete)
        assertTrue(read.details.product.available)
        assertEquals(900L, read.details.product.listedPriceHalala)
        assertTrue(read.details.product.groups.isEmpty())
        assertEquals(item.name, read.details.product.title)
        assertEquals(pdp, read.lastFrame)
    }

    @Test fun missingSearchPriceIsNotInferredFromThePdpAddAmount() = runBlocking {
        val read = HungerStationProductReadSession().read(restaurant, item.copy(listedPrice = null),
            frame = { pdp }, scroll = { error("Do not scroll past options end") }, now = { 1000 })
        assertTrue(read.details.optionsComplete)
        assertTrue(read.details.product.available)
        assertNull(read.details.product.listedPriceHalala)
    }

    @Test fun disabledFooterAndMissingEndRemainDistinctRejectedFacts() = runBlocking {
        val disabled = pdp.copy(nodes = pdp.nodes.map { if (it.id == "flutterAddButton") it.copy(enabled = false) else it })
        val unavailable = HungerStationProductReadSession().read(restaurant, item,
            frame = { disabled }, scroll = { error("End visible") }, now = { 1000 }).details
        assertTrue(unavailable.optionsComplete)
        assertFalse(unavailable.product.available)
        val withoutEnd = pdp.copy(nodes = pdp.nodes.map { if (it.index == 26) it.copy(label = "") else it })
        val incomplete = HungerStationProductReadSession(1).read(restaurant, item,
            frame = { withoutEnd }, scroll = { error("Single-page limit") }, now = { 1000 }).details
        assertFalse(incomplete.optionsComplete)
        assertTrue(incomplete.product.available)
    }
}
