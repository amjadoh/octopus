package app.octopus.local

import app.octopus.local.providers.*
import app.octopus.local.automation.*
import app.octopus.local.safety.*
import app.octopus.local.domain.ProviderId
import org.junit.Assert.*
import org.junit.Test

class RestaurantBrowseTest {
    private fun n(i: Int, parent: Int = -1, id: String = "", text: String = "", click: Boolean = false,
        cls: String = "android.view.ViewGroup") = CatalogNode(i,parent,
        if (id.isEmpty()) "" else "com.hungerstation.android.web:id/$id",text,cls,click,false,false)
    private fun home() = CatalogFrame(listOf(n(0,id="new_search_card_view"),n(1,id="input_csc"),
        n(2,id="home_modules_parent")) + (0 until 7).flatMap { k ->
        listOf(n(3+k*2,2,click=true),n(4+k*2,3+k*2,"banner_image",cls="android.widget.ImageView")) },true)
    @Test fun observedGridHasOneReadOnlyEntryAndRejectsChangedGrid() {
        assertEquals(3,HungerStationCatalog.browseEntry(home())?.index)
        assertNull(HungerStationCatalog.browseEntry(home().copy(complete=false)))
        assertNull(HungerStationCatalog.browseEntry(home().copy(nodes=home().nodes.dropLast(2))))
    }
    @Test fun listingReadsOnlyRestaurantNamesAndGenresInsideItsRecycler() {
        val frame=CatalogFrame(listOf(n(0,id="vendorsRecycler"),n(1,id="hsBackButton"),
            n(2,0),n(3,2,"vendor_name","الطازج"),n(4,2,"vendor_genre","مشويات"),
            n(5,id="vendor_name",text="خارج القائمة")),true)
        assertEquals(CatalogScreen.BROWSE,HungerStationCatalog.screen(frame))
        assertEquals(listOf(LiveRestaurant("الطازج","مشويات","الطازج")),HungerStationCatalog.browsedRestaurants(frame))
        assertTrue(HungerStationCatalog.browsedRestaurants(frame.copy(complete=false)).isEmpty())
        assertEquals("com.hungerstation.android.web:id/hsBackButton",CatalogCommands.backTarget(
            frame.copy(nodes=frame.nodes.map { if(it.index==1) it.copy(clickable=true) else it }))?.id)
    }
    @Test fun promoIsSeparateFromRestaurantListingAndScrollNeverClicks() {
        val promo=CatalogFrame(listOf(n(0,id="vendor_list"),n(1,id="close_button",click=true)),true)
        assertEquals(CatalogScreen.BROWSE_PROMO,HungerStationCatalog.screen(promo))
        assertTrue(HungerStationCatalog.browsedRestaurants(promo).isEmpty())
        assertEquals(ElementOperation.SCROLL_FORWARD,CatalogAction.SCROLL_RESTAURANTS.operation())
        assertFalse(SafetyGuard().allowsCatalogAction(CatalogAction.SCROLL_RESTAURANTS,CatalogScreen.CART,
            ProviderId.HUNGERSTATION.packageName,true,true,true,""))
    }
    @Test fun initialAndCollapsedListingHeadersAreRecognizedWithoutAddressChildren() {
        val listing = CatalogFrame(listOf(n(0,id="vendorsRecycler"), n(1,id="new_bar_top"),
            n(2,id="vendors_title"), n(3,id="new_search_card_view")),true)
        assertEquals(CatalogScreen.BROWSE,HungerStationCatalog.screen(listing))
        assertEquals(CatalogScreen.BROWSE,HungerStationCatalog.screen(listing.copy(nodes=listing.nodes.take(2))))
        assertEquals(CatalogScreen.UNKNOWN,HungerStationCatalog.screen(listing.copy(nodes=listing.nodes.take(1))))
    }
}
