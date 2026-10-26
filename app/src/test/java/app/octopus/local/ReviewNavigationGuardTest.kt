package app.octopus.local
import app.octopus.local.domain.*
import app.octopus.local.providers.*
import app.octopus.local.safety.ReviewNavigationGuard
import org.junit.Assert.*
import org.junit.Test

class ReviewNavigationGuardTest {
    private fun keetaHeader() = CatalogFrame(listOf(
        node(0,-1,"","").copy(className="android.view.ViewGroup"),
        node(1,0,"","",true).copy(className="android.view.ViewGroup"),
        node(2,1,"","").copy(className="android.widget.ImageView"),
        node(3,0,"","").copy(className="android.view.ViewGroup"),
        node(4,3,"","إتمام الدفع").copy(className="android.widget.TextView")),true)
    @Test fun keetaReviewRecoveryOnlySelectsObservedHeaderBackArrow() {
        val f = keetaHeader()
        assertEquals(f.nodes[1], ReviewNavigationGuard.keetaReviewBackTarget(f,"3.13.203"))
        assertNull(ReviewNavigationGuard.keetaReviewBackTarget(f,"unknown"))
        assertNull(ReviewNavigationGuard.keetaReviewBackTarget(f.copy(complete=false),"3.13.203"))
        for (bad in listOf(
            f.copy(nodes=f.nodes.filter {it.index != 2}),
            f.copy(nodes=f.nodes + node(5,0,"","")),
            f.copy(nodes=f.nodes.map {if(it.index==1) it.copy(label="تأكيد الطلب") else it}),
            f.copy(nodes=f.nodes.map {if(it.index==1) it.copy(enabled=false) else it}),
            f.copy(nodes=f.nodes.map {if(it.index==4) it.copy(label="تأكيد الدفع") else it}),
            f.copy(nodes=f.nodes.map {if(it.index==4) it.copy(omittedText=true) else it}),
            f.copy(nodes=f.nodes.map {if(it.index==2) it.copy(className="android.view.ViewGroup") else it}),
            f.copy(nodes=f.nodes + f.nodes[4].copy(index=5))
        )) assertNull(ReviewNavigationGuard.keetaReviewBackTarget(bad,"3.13.203"))
    }
    @Test fun groupedReviewHeadingMustNameTheRequestedRestaurantExactly() {
        assertTrue(ReviewNavigationGuard.hungerReviewHeading("مراجعة الطلب\nكودو","كودو"))
        assertFalse(ReviewNavigationGuard.hungerReviewHeading("مراجعة الطلب\nآخر","كودو"))
        assertFalse(ReviewNavigationGuard.hungerReviewHeading("السلة\nكودو","كودو"))
        assertFalse(ReviewNavigationGuard.hungerReviewHeading("مراجعة الطلب\nكودو\nتأكيد","كودو"))
        assertFalse(ReviewNavigationGuard.hungerReviewHeading("مراجعة الطلب\nكودو",null))
    }
    @Test fun optionalRecommendationsCanOnlyContinueToReview() {
        val next=node(3,0,"","اذهب للدفع",true).copy(className="android.widget.Button")
        val observed=CatalogFrame(listOf(node(0,-1,"",""),
            node(1,0,"","لا تفوت الفرصة!\nعادةً ما يضيف الأشخاص هذه العناصر").copy(className="android.widget.ImageView"),
            node(2,0,"menuItemActionSingleButtonAdd",""),next),true)
        assertEquals(next,ReviewNavigationGuard.hungerRecommendationsTarget(observed,HungerStationCatalog.TESTED_VERSION))
        assertNull(ReviewNavigationGuard.hungerRecommendationsTarget(observed.copy(complete=false),HungerStationCatalog.TESTED_VERSION))
        assertNull(ReviewNavigationGuard.hungerRecommendationsTarget(observed.copy(nodes=observed.nodes.dropLast(1)+next.copy(label="تأكيد الطلب")),HungerStationCatalog.TESTED_VERSION))
        assertNull(ReviewNavigationGuard.hungerRecommendationsTarget(observed.copy(nodes=observed.nodes.filter {it.index!=1}),HungerStationCatalog.TESTED_VERSION))
        assertNull(ReviewNavigationGuard.hungerRecommendationsTarget(observed.copy(nodes=observed.nodes+next.copy(index=4)),HungerStationCatalog.TESTED_VERSION))
    }
    private val cart = CanonicalCart("مطعم", null, listOf(CanonicalItem("وجبة",null,emptyList(),3)))
    private fun node(i:Int, parent:Int, id:String, text:String, click:Boolean=false) =
        CatalogNode(i,parent,id,text,"android.view.View",click,false,false)
    private fun frame() = CatalogFrame(listOf(
        node(0,-1,"FlutterCart_title","السلة\nمطعم"),
        node(1,-1,"FlutterCart_scroll_view","ملخص الطلب\nالمجموع\n30.00\n§"),
        node(2,1,"FlutterCart_cartItem","وجبة\nتعديل\n30\n§",true),
        node(3,2,"menuItemActionDualButtonValue","3"),
        node(4,-1,"FlutterCart_footer_main_action_button","اذهب للدفع",true)),true)
    private fun twoItemFrame()=frame().copy(nodes=frame().nodes.map {if(it.id=="FlutterCart_scroll_view")it.copy(label="ملخص الطلب\nالمجموع\n40.00\n§") else it}+
        listOf(node(8,1,"FlutterCart_cartItem","صنف ثان\nتعديل\n10\n§",true),node(9,8,"menuItemActionDualButtonValue","1")))
    private val twoItemCart get()=cart.copy(items=cart.items+CanonicalItem("صنف ثان",null,emptyList(),1))
    private fun record()=PreparedBasketRecord.create(twoItemCart,BasketEvidenceReader.hungerStation(twoItemFrame(),true),HungerStationCatalog.TESTED_VERSION,1000)!!
    @Test fun fullMultiRowReviewNeedsFreshCompleteAdmissionAndCanOnlyDispatchOnce() {
        val source=twoItemFrame()
        assertNull(ReviewNavigationGuard.target(ProviderId.HUNGERSTATION,source,twoItemCart,HungerStationCatalog.TESTED_VERSION))
        val admission=app.octopus.local.safety.PreparedReviewAdmission.create(ProviderId.HUNGERSTATION,twoItemCart,record(),source,HungerStationCatalog.TESTED_VERSION,1000)!!
        val target=ReviewNavigationGuard.target(ProviderId.HUNGERSTATION,source,twoItemCart,HungerStationCatalog.TESTED_VERSION,admission,1001)!!
        assertTrue(admission.consume(source,target,ProviderId.HUNGERSTATION,twoItemCart,HungerStationCatalog.TESTED_VERSION,1002))
        assertFalse(admission.consume(source,target,ProviderId.HUNGERSTATION,twoItemCart,HungerStationCatalog.TESTED_VERSION,1003))
        assertNull(ReviewNavigationGuard.target(ProviderId.HUNGERSTATION,source,twoItemCart,HungerStationCatalog.TESTED_VERSION,admission,1004))
    }
    @Test fun changedFrameRequestVersionExpiryOrConfirmationCannotUseMultiReviewAdmission() {
        val source=twoItemFrame()
        fun admission()=app.octopus.local.safety.PreparedReviewAdmission.create(ProviderId.HUNGERSTATION,twoItemCart,record(),source,HungerStationCatalog.TESTED_VERSION,1000)!!
        val changed=source.copy(nodes=source.nodes.map {if(it.index==9)it.copy(label="2") else it})
        assertNull(ReviewNavigationGuard.target(ProviderId.HUNGERSTATION,changed,twoItemCart,HungerStationCatalog.TESTED_VERSION,admission(),1001))
        assertNull(ReviewNavigationGuard.target(ProviderId.HUNGERSTATION,source,twoItemCart.copy(restaurant="آخر"),HungerStationCatalog.TESTED_VERSION,admission(),1001))
        assertNull(ReviewNavigationGuard.target(ProviderId.HUNGERSTATION,source,twoItemCart,"other",admission(),1001))
        assertNull(ReviewNavigationGuard.target(ProviderId.HUNGERSTATION,source,twoItemCart,HungerStationCatalog.TESTED_VERSION,admission(),16001))
        assertNull(app.octopus.local.safety.PreparedReviewAdmission.create(ProviderId.HUNGERSTATION,twoItemCart,record(),changed,HungerStationCatalog.TESTED_VERSION,1000))
        for(label in listOf("تأكيد الطلب","ادفع الآن","تأكيد الدفع")) {
            val bad=source.copy(nodes=source.nodes.map {if(it.index==4)it.copy(label=label) else it})
            val permit=app.octopus.local.safety.PreparedReviewAdmission.create(ProviderId.HUNGERSTATION,twoItemCart,record(),bad,HungerStationCatalog.TESTED_VERSION,1000)!!
            assertNull(ReviewNavigationGuard.target(ProviderId.HUNGERSTATION,bad,twoItemCart,HungerStationCatalog.TESTED_VERSION,permit,1001))
        }
    }
    @Test fun onlyRecordedNavigationFromExactBasketIsAllowed() {
        assertNotNull(ReviewNavigationGuard.target(ProviderId.HUNGERSTATION,frame(),cart,HungerStationCatalog.TESTED_VERSION))
        assertNull(ReviewNavigationGuard.target(ProviderId.HUNGERSTATION,frame(),cart.copy(restaurant="آخر"),HungerStationCatalog.TESTED_VERSION))
        assertNull(ReviewNavigationGuard.target(ProviderId.HUNGERSTATION,frame(),cart,"unknown"))
    }
    @Test fun observedEnabledWrapperAllowsOnlyItsSingleReviewButton() {
        val button = node(5,4,"","اذهب للدفع",true).copy(className="android.widget.Button")
        val observed = frame().copy(nodes=frame().nodes.map {
            if(it.index==4) it.copy(label="enabled",clickable=false) else it
        } + button)
        assertEquals(button,ReviewNavigationGuard.target(ProviderId.HUNGERSTATION,observed,cart,HungerStationCatalog.TESTED_VERSION))
        for (bad in listOf(button.copy(label="تأكيد الطلب"),button.copy(enabled=false),button.copy(parent=1))) {
            assertNull(ReviewNavigationGuard.target(ProviderId.HUNGERSTATION,observed.copy(nodes=observed.nodes.dropLast(1)+bad),cart,HungerStationCatalog.TESTED_VERSION))
        }
        assertNull(ReviewNavigationGuard.target(ProviderId.HUNGERSTATION,observed.copy(nodes=observed.nodes+button.copy(index=6)),cart,HungerStationCatalog.TESTED_VERSION))
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
