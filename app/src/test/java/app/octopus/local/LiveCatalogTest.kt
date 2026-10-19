package app.octopus.local

import app.octopus.local.providers.*
import app.octopus.local.safety.*
import app.octopus.local.domain.ProviderId
import org.junit.Assert.*
import org.junit.Test

class LiveCatalogTest {
    private fun node(i: Int, parent: Int, id: String, label: String = "", click: Boolean = false) =
        CatalogNode(i, parent, id, label, "android.view.View", click, false, false)
    private val pkg = ProviderId.HUNGERSTATION.packageName
    @Test fun listMenuRowsAndDiscountBadgesDoNotTurnIntoInventedProducts() {
        val frame=CatalogFrame(listOf(node(0,-1,"menuBackButton"),node(1,-1,"menuSearchButton"),
            node(2,-1,"","فول جرة\nوصف الوجبة\n300 سعرة\n§\n12",true),
            node(3,-1,"gridMenuCell-0-0","%20\n4,260+ طلب\nفول صاج\n§\n9",true),
            node(4,-1,"couponViewBottomTitle_1","تطبيق تلقائي\n10 §",true)),true)
        assertEquals(listOf("فول جرة","فول صاج"),HungerStationCatalog.menuItems(frame).map { it.name })
        assertEquals(listOf(1200L,900L),HungerStationCatalog.menuItems(frame).map { it.listedPrice })
    }
    @Test fun viewOnlyNoticeRequiresAllObservedControlsAndCannotAuthorizeOtherButtons() {
        val frame=CatalogFrame(listOf(node(0,-1,"","عرض قائمة المطعم",true),node(1,-1,"","اكتشف المطاعم",true),node(2,-1,"","رفض",true)),true)
        assertEquals(CatalogScreen.RESTAURANT_NOTICE,HungerStationCatalog.screen(frame))
        val guard=SafetyGuard()
        assertTrue(guard.allowsCatalogAction(CatalogAction.VIEW_MENU_NOTICE,CatalogScreen.RESTAURANT_NOTICE,pkg,true,true,true,"عرض قائمة المطعم"))
        assertFalse(guard.allowsCatalogAction(CatalogAction.VIEW_MENU_NOTICE,CatalogScreen.RESTAURANT_NOTICE,pkg,true,true,true,"اكتشف المطاعم"))
        assertFalse(guard.allowsCatalogAction(CatalogAction.VIEW_MENU_NOTICE,CatalogScreen.UNKNOWN,pkg,true,true,true,"عرض قائمة المطعم"))
    }
    @Test fun recentAndStaticSuggestionsMustBelongToRestaurants() {
        val frame=CatalogFrame(listOf(
            node(0,-1,"UniversalSearch_autocomplete_recent_cell_0","مطعم, مطعم\nفي المطاعم",true),
            node(1,-1,"UniversalSearch_static_vertical_cell_2","مطعم, مطعم\nفي المطاعم",true),
            node(2,-1,"UniversalSearch_static_vertical_cell_3","مطعم, مطعم\nفي مقاضي",true),
            node(3,-1,"unrecognized","مطعم, مطعم\nفي المطاعم",true)),true)
        assertEquals(listOf(0,1),HungerStationCatalog.restaurantSuggestions(frame).map { it.index })
    }
    @Test fun nativeInputIsPreferredToFlutterSemanticsWrapper() {
        val wrapper = CatalogNode(0,-1,"UniversalSearch_search_bar_text_field","","android.view.View",true,true,false)
        val native = wrapper.copy(index=1,parent=0,id="",className="android.widget.EditText")
        val frame = CatalogFrame(listOf(wrapper,native),true)
        assertEquals(listOf(native),HungerStationCatalog.searchInputs(frame))
        assertEquals(CatalogScreen.SEARCH,HungerStationCatalog.screen(frame))
    }
    @Test fun flutterSearchUsesEditableCapabilityRatherThanNativeWidgetClass() {
        val field = CatalogNode(1, -1, "UniversalSearch_search_bar_text_field", "", "android.view.View", true, true, false)
        assertEquals(CatalogScreen.SEARCH, HungerStationCatalog.screen(CatalogFrame(listOf(field), true)))
        val ambiguous = CatalogFrame(listOf(field, field.copy(index=2)), true)
        assertEquals(2, HungerStationCatalog.searchInputs(ambiguous).size)
        assertEquals(CatalogScreen.SEARCH, HungerStationCatalog.screen(ambiguous))
    }
    @Test fun onlyObservedScreenAnchorsAreRecognized() {
        assertEquals(CatalogScreen.HOME, HungerStationCatalog.screen(CatalogFrame(listOf(node(0,-1,"$pkg:id/new_search_card_view"), node(1,-1,"$pkg:id/input_csc")),true)))
        assertEquals(CatalogScreen.UNKNOWN, HungerStationCatalog.screen(CatalogFrame(listOf(node(0,-1,"unrelated", "بحث")),true)))
    }
    @Test fun partialTreeAndProductOverlaysCannotBeTreatedAsMenu() {
        val nodes=listOf(node(0,-1,"menuBackButton"),node(1,-1,"menuSearchButton"))
        assertEquals(CatalogScreen.UNKNOWN,HungerStationCatalog.screen(CatalogFrame(nodes,false)))
        assertEquals(CatalogScreen.PRODUCT,HungerStationCatalog.screen(CatalogFrame(nodes+node(2,-1,"pdpCloseButton"),true)))
    }
    @Test fun extractsRealCardNameAndDescriptionWithoutAdvertisementsOrProductNames() {
        val frame=CatalogFrame(listOf(node(0,-1,"$pkg:id/searchVendorsRecycler"),node(1,0,"",click=true),
            node(2,1,"$pkg:id/title","مطعم اختبار"),node(3,1,"$pkg:id/description","سعودي"),
            node(4,1,"$pkg:id/product_name","اسم وجبة")),true)
        val result=HungerStationCatalog.restaurants(frame,"اختبار")
        assertEquals(listOf(LiveRestaurant("مطعم اختبار","سعودي","اختبار")),result)
    }
    @Test fun sameNamedBranchesAreExcludedInsteadOfGuessing() {
        val frame=CatalogFrame(listOf(node(0,-1,"$pkg:id/searchVendorsRecycler"),node(1,0,"",click=true),
            node(2,1,"$pkg:id/title","مطعم"),node(3,0,"",click=true),node(4,3,"$pkg:id/title","مطعم")),true)
        assertTrue(HungerStationCatalog.restaurants(frame,"مطعم").isEmpty())
    }
    @Test fun menuCellPriceUsesTheObservedCurrencySeparatedFormat() {
        val frame=CatalogFrame(listOf(node(0,-1,"menuBackButton"),node(1,-1,"menuSearchButton"),
            node(2,-1,"gridMenuCell-0-0","وجبة\n§\n33",true)),true)
        assertEquals(LiveMenuItem("وجبة",3300,"gridMenuCell-0-0"),HungerStationCatalog.menuItems(frame).single())
    }
    @Test fun absentMenuPriceRemainsUnknown() {
        val frame=CatalogFrame(listOf(node(0,-1,"menuBackButton"),node(1,-1,"menuSearchButton"),
            node(2,-1,"gridMenuCell-0-0","وجبة",true)),true)
        assertNull(HungerStationCatalog.menuItems(frame).single().listedPrice)
    }
    @Test fun wrongScreenOrPackageAndMissingConsentDenyNavigation() {
        val guard=SafetyGuard()
        assertFalse(guard.allowsCatalogAction(CatalogAction.OPEN_SEARCH,CatalogScreen.UNKNOWN,pkg,true,true,true,""))
        assertFalse(guard.allowsCatalogAction(CatalogAction.OPEN_SEARCH,CatalogScreen.HOME,ProviderId.KEETA.packageName,true,true,true,""))
        assertFalse(guard.allowsCatalogAction(CatalogAction.OPEN_SEARCH,CatalogScreen.HOME,pkg,false,true,true,""))
        assertFalse(guard.allowsCatalogAction(CatalogAction.OPEN_SEARCH,CatalogScreen.HOME,pkg,true,false,true,""))
    }
    @Test fun financialButtonsRemainForbiddenDuringCatalogNavigation() {
        val guard=SafetyGuard()
        assertTrue(guard.allowsCatalogAction(CatalogAction.OPEN_RESTAURANT,CatalogScreen.RESULTS,pkg,true,true,true,"مطعم"))
        listOf("إتمام الدفع","تأكيد الطلب","ادفع الآن","اشتراك","place order").forEach {
            assertFalse(guard.allowsCatalogAction(CatalogAction.OPEN_RESTAURANT,CatalogScreen.RESULTS,pkg,true,true,true,it))
        }
    }
}
