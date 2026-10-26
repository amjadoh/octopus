package app.octopus.local
import app.octopus.local.automation.*
import app.octopus.local.domain.*
import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test

class PlainBasketSeedTest {
    private val a=CanonicalItem("بطاطس وسط",null,emptyList(),3)
    private val b=CanonicalItem("جبنة الناتشوز",null,emptyList(),2)
    private val request=CanonicalCart("كودو",null,listOf(a,b))
    private val version=HungerStationCatalog.TESTED_VERSION
    private fun node(i:Int,id:String,label:String="")=CatalogNode(i,-1,id,label,"android.view.View",false,false,false)
    private fun menu()=CatalogFrame(listOf(node(0,"menuBackButton"),node(1,"menuSearchButton"),node(2,"","كودو")),true)
    private fun seed(f:CatalogFrame=menu())=PlainBasketSeed.create(request,a,ProviderId.HUNGERSTATION,version,900,f,1000)
    private fun basket()=BasketEvidence(ProviderId.HUNGERSTATION,"كودو",listOf(BasketLineEvidence(a.name,emptyList(),3,2700)),2700,true)
    @Test fun firstAddPreservesFullRequestAndRequiresCompleteActualAcknowledgement() {
        val s=seed()!!
        assertFalse(s.consumeAdd(request.copy(items=listOf(a)),version,1001))
        assertTrue(s.consumeAdd(request,version,1002))
        assertFalse(s.consumeAdd(request,version,1003))
        assertTrue(s.acknowledge(request,basket(),version,1100))
        assertFalse(s.acknowledge(request,basket(),version,1101))
        assertNotNull(PlainBasketPreparationSession.create(request,basket(),version))
    }
    @Test fun oldOrUnconsumedAddCannotBecomeOwnedBasket() {
        assertFalse(seed()!!.acknowledge(request,basket(),version,1100))
        assertFalse(seed()!!.consumeAdd(request,version,121001))
        assertFalse(seed()!!.consumeAdd(request,"other",1100))
        assertFalse(seed()!!.consumeAdd(request,version,999))
    }
    @Test fun incompleteWrongQuantityUnknownExtrasAndWrongAmountRejectAcknowledgement() {
        val bad=listOf(basket().copy(complete=false),basket().copy(subtotal=2701),
            basket().copy(lines=listOf(basket().lines.single().copy(quantity=2))),
            basket().copy(lines=basket().lines+BasketLineEvidence(b.name,emptyList(),1,1300)),
            basket().copy(lines=listOf(basket().lines.single().copy(choices=listOf(BasketChoiceEvidence("ملح",1))))))
        bad.forEach {actual->val s=seed()!!;assertTrue(s.consumeAdd(request,version,1001));assertFalse(s.acknowledge(request,actual,version,1100))}
    }
    @Test fun occupiedUnknownOrDifferentRestaurantMenuCannotAuthorizeSeed() {
        assertNull(seed(menu().copy(complete=false)))
        assertNull(seed(menu().copy(nodes=menu().nodes+node(3,"FlutterMenu_goToCartQuantity","1"))))
        assertNull(seed(menu().copy(nodes=menu().nodes+node(3,"FlutterMenu_goToCartButton"))))
        assertNull(seed(menu().copy(nodes=menu().nodes.map {if(it.index==2)it.copy(label="آخر") else it})))
        assertNull(PlainBasketSeed.create(request,a,ProviderId.HUNGERSTATION,"other",900,menu(),1000))
        assertNull(PlainBasketSeed.create(request,b.copy(name="آخر"),ProviderId.HUNGERSTATION,version,900,menu(),1000))
    }
    @Test fun keetaSeedRequiresObservedEmptyNativeMenuAndAcknowledgesOnlyOneInitialUnit() {
        val f=CatalogFrame(listOf(
            CatalogNode(0,-1,"","كودو","android.widget.TextView",false,false,false),
            CatalogNode(1,-1,"","","androidx.recyclerview.widget.RecyclerView",false,false,true),
            CatalogNode(2,1,"","","android.widget.FrameLayout",true,false,false),
            CatalogNode(3,2,"com.sankuai.sailor.afooddelivery:id/card_root","","android.view.ViewGroup",false,false,false),
            CatalogNode(4,3,"com.sankuai.sailor.afooddelivery:id/product_name",a.name,"android.widget.TextView",false,false,false)),true)
        fun token(frame:CatalogFrame=f)=PlainBasketSeed.create(request,a,ProviderId.KEETA,"3.13.203",900,frame,1000)
        val seed=token()!!;assertEquals(1,seed.initialQuantity)
        assertTrue(seed.consumeAdd(request,"3.13.203",1001))
        val after=BasketEvidence(ProviderId.KEETA,"كودو",listOf(BasketLineEvidence(a.name,emptyList(),1,900)),900,true)
        assertTrue(seed.acknowledge(request,after,"3.13.203",1100))
        assertNull(token(f.copy(nodes=f.nodes+node(5,"","إتمام الدفع"))))
        assertNull(token(f.copy(nodes=f.nodes+CatalogNode(5,3,"","3","android.widget.TextView",false,false,false))))
    }

}
