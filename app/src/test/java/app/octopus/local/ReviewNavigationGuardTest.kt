package app.octopus.local
import app.octopus.local.domain.*
import app.octopus.local.providers.*
import app.octopus.local.safety.ReviewNavigationGuard
import org.junit.Assert.*
import org.junit.Test

class ReviewNavigationGuardTest {
    private val cart = CanonicalCart("مطعم", null, listOf(CanonicalItem("وجبة",null,emptyList(),3)))
    private fun node(i:Int, parent:Int, id:String, text:String, click:Boolean=false) =
        CatalogNode(i,parent,id,text,"android.view.View",click,false,false)
    private fun frame() = CatalogFrame(listOf(
        node(0,-1,"FlutterCart_title","السلة\nمطعم"),
        node(1,-1,"FlutterCart_scroll_view","ملخص الطلب\nالمجموع\n30.00\n§"),
        node(2,1,"FlutterCart_cartItem","وجبة\nتعديل\n30\n§",true),
        node(3,2,"menuItemActionDualButtonValue","3"),
        node(4,-1,"FlutterCart_footer_main_action_button","اذهب للدفع",true)),true)
    @Test fun onlyRecordedNavigationFromExactBasketIsAllowed() {
        assertNotNull(ReviewNavigationGuard.target(ProviderId.HUNGERSTATION,frame(),cart,HungerStationCatalog.TESTED_VERSION))
        assertNull(ReviewNavigationGuard.target(ProviderId.HUNGERSTATION,frame(),cart.copy(restaurant="آخر"),HungerStationCatalog.TESTED_VERSION))
        assertNull(ReviewNavigationGuard.target(ProviderId.HUNGERSTATION,frame(),cart,"unknown"))
    }
    @Test fun noOrderConfirmationOrIncompleteFrameCanBecomeReviewNavigation() {
        for(label in listOf("تأكيد الطلب","ادفع الآن","تأكيد الدفع","Place order")) {
            val changed = frame().copy(nodes=frame().nodes.map { if(it.index==4) it.copy(label=label) else it })
            assertNull(ReviewNavigationGuard.target(ProviderId.HUNGERSTATION,changed,cart,HungerStationCatalog.TESTED_VERSION))
        }
        assertNull(ReviewNavigationGuard.target(ProviderId.HUNGERSTATION,frame().copy(complete=false),cart,HungerStationCatalog.TESTED_VERSION))
        assertNull(ReviewNavigationGuard.target(ProviderId.KEETA,frame(),cart,"3.13.203"))
    }
}
