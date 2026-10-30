package app.octopus.local

import app.octopus.local.automation.NativeCartImport
import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test

class NativeCartImportTest {
    private fun node(i: Int, parent: Int, id: String = "", label: String = "", click: Boolean = false,
        cls: String = "android.view.View") = CatalogNode(i, parent, id, label, cls, click, false, false)
    private fun menu() = CatalogFrame(listOf(
        node(0,-1,"menuBackButton"), node(1,-1,"menuSearchButton"),
        node(2,-1,label="البيك"), node(3,-1,"FlutterMenu_goToCartButton"),
        node(4,3,label="عرض السلة\n20\n§",click=true,cls="android.widget.Button"),
        node(5,4,"FlutterMenu_goToCartQuantity","2")),true)
    private fun cart(qty: String = "2", amount: String = "20", options: String? = null) = CatalogFrame(listOf(
        node(0,-1,"FlutterCart_title","السلة\nالبيك"),
        node(1,-1,"FlutterCart_scroll_view","المجموع\n20\n§"),
        node(2,1,"FlutterCart_cartItem",if(options == null) "بيج بيك\n$amount\n§" else "بيج بيك\n$options\nتعديل\n$amount\n§",true),
        node(3,2,"menuItemActionDualButtonValue",qty)),true)
    private fun session() = NativeCartImport().apply { observe(menu(),1000); observe(cart(),1500) }
    private fun NativeCartImport.read(frame: CatalogFrame = cart(), now: Long = 2000, version: String? = HungerStationCatalog.TESTED_VERSION) =
        read(frame,version,now)

    @Test fun importsExactVisibleNamesAndQuantitiesWithoutInventingAddressOrTotals() {
        val result = session().read()!!
        assertEquals("البيك",result.first.restaurant)
        assertEquals("بيج بيك",result.first.items.single().name)
        assertEquals(2,result.first.items.single().quantity)
        assertNull(result.first.branch)
        assertNull(result.first.addressReference)
        assertFalse(result.second.recognizedOrderReview)
    }
    @Test fun missingRowsAndMoneyDisagreementAreRejected() {
        assertNull(session().read(cart(qty="1")))
        assertNull(session().read(cart(amount="10")))
        assertNull(session().read(cart().copy(complete=false)))
    }
    @Test fun optionsAndUnknownQuantitiesAreNotSilentlyDiscarded() {
        assertNull(session().read(cart(options="1x جبن")))
        assertNull(session().read(cart(qty="?")))
        assertNull(session().read(cart().copy(nodes=cart().nodes + node(4,-1).copy(enteredText=true))))
    }
    @Test fun staleClockReversalAndUnsupportedVersionAreRejected() {
        assertNull(session().read(now=31_001))
        assertNull(session().read(now=999))
        assertNull(session().read(version="future"))
    }
    @Test fun missingMenuOrInterruptedContinuityNeverProvesCompleteness() {
        assertNull(NativeCartImport().read())
        val s = session()
        s.observe(CatalogFrame(emptyList(),true),1600)
        assertNull(s.read())
    }
    @Test fun foreignRestaurantAndDuplicateRowsAreRejected() {
        val wrong = cart().copy(nodes=cart().nodes.map { if(it.id=="FlutterCart_title")it.copy(label="السلة\nمطعم آخر") else it })
        assertNull(session().read(wrong))
        val duplicate = cart().copy(nodes=cart().nodes+listOf(
            node(4,1,"FlutterCart_cartItem","بيج بيك\n0\n§",true),node(5,4,"menuItemActionDualButtonValue","1")))
        assertNull(session().read(duplicate))
    }
    @Test fun resetPreventsCrossSessionReuse() {
        val s=session(); s.reset(); assertNull(s.read())
    }
}
