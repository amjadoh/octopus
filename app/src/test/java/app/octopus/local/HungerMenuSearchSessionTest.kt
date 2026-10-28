package app.octopus.local
import app.octopus.local.automation.*
import app.octopus.local.providers.*
import app.octopus.local.domain.*
import app.octopus.local.safety.SafetyGuard
import org.junit.Assert.*
import org.junit.Test
class HungerMenuSearchSessionTest {
    private val cart=CanonicalCart("براون كوفي",null,listOf(CanonicalItem("بلو لاقون",null,emptyList(),1)))
    private val menu=CatalogFrame(listOf(
        CatalogNode(0,-1,"menuBackButton","","android.widget.ImageView",true,false,false),
        CatalogNode(1,-1,"menuSearchButton","","android.widget.ImageView",true,false,false),
        CatalogNode(2,-1,"","براون كوفي","android.widget.TextView",false,false,false)),true)
    private val search=CatalogFrame(listOf(
        CatalogNode(0,-1,"menuSearchCloseButton","","android.widget.ImageView",true,false,false),
        CatalogNode(1,-1,"menuSearchInput","","android.widget.EditText",true,true,false)),true)
    private val version=HungerStationCatalog.TESTED_VERSION
    private fun session()=HungerMenuSearchSession.create(menu,cart,"بلو لاقون",version,1000)!!
    @Test fun resumedMenuSearchHasOnlyItsKnownCloseTarget() {
        assertEquals(CatalogScreen.SEARCH, HungerStationCatalog.screen(search))
        assertEquals(search.nodes[0], CatalogCommands.backTarget(search))
        assertNull(CatalogCommands.backTarget(search.copy(complete=false)))
        assertNull(HungerMenuSearchScreen.closeTarget(search.copy(nodes=search.nodes + search.nodes[1].copy(index=2))))
        assertNull(HungerMenuSearchScreen.closeTarget(search.copy(nodes=search.nodes.map { if(it.id=="menuSearchCloseButton") it.copy(enabled=false) else it })))
    }
    @Test fun actualHighestRatedAlbaikImageCardIsExactFoodCandidateNotItsSpicyVariant() {
        val request=CanonicalCart("البيك",null,listOf(CanonicalItem("بيج بيك",null,emptyList(),2)))
        val base=menu.copy(nodes=menu.nodes.map {if(it.label=="براون كوفي") it.copy(label="البيك") else it})
        val session=checkNotNull(HungerMenuSearchSession.create(base,request,"بيج بيك",version,1000))
        assertTrue(session.consume(HungerMenuSearchAction.OPEN,base,base.nodes[1],request,version,1100))
        assertTrue(session.consume(HungerMenuSearchAction.TYPE,search,search.nodes[1],request,version,1200))
        val f=com.google.gson.Gson().fromJson(javaClass.getResource("/recorded-hs/hs-8.0.303-in-menu-search-albaik-redacted.json")!!.readText(),CatalogFrame::class.java)
        val candidate=session.candidates(f,request,version,1300).single()
        assertEquals("android.widget.ImageView",candidate.node.className)
        assertEquals("بيج بيك",candidate.item.name)
        assertEquals(1750L,candidate.item.listedPrice)
        assertNotEquals("بيج بيك حرّاق",candidate.item.name)
        assertTrue(session.candidates(f.copy(complete=false),request,version,1300).isEmpty())
    }

    @Test fun observedSearchControlsAreBoundToFullRequestAndConsumedOnce() {
        val s=session();val open=s.target(HungerMenuSearchAction.OPEN,menu,cart,version,1100)!!
        assertTrue(s.consume(HungerMenuSearchAction.OPEN,menu,open,cart,version,1100))
        assertNull(s.target(HungerMenuSearchAction.OPEN,menu,cart,version,1101))
        val input=s.target(HungerMenuSearchAction.TYPE,search,cart,version,1200)!!
        assertTrue(s.consume(HungerMenuSearchAction.TYPE,search,input,cart,version,1200))
        assertNull(s.target(HungerMenuSearchAction.TYPE,search,cart,version,1201))
        assertTrue(s.results(search,cart,version,1300))
        val close=s.target(HungerMenuSearchAction.CLOSE,search,cart,version,1400)!!
        assertTrue(s.consume(HungerMenuSearchAction.CLOSE,search,close,cart,version,1400))
        assertFalse(s.results(search,cart,version,1401))
    }
    @Test fun otherRestaurantQueryStaleVersionChangedRequestAndMissingHeaderNeverStart() {
        assertNull(HungerMenuSearchSession.create(menu,cart,"وجبة أخرى",version,1000))
        assertNull(HungerMenuSearchSession.create(menu.copy(complete=false),cart,"بلو لاقون",version,1000))
        assertNull(HungerMenuSearchSession.create(menu,cart,"بلو لاقون","unknown",1000))
        assertNull(session().target(HungerMenuSearchAction.OPEN,menu,cart,version,91_001))
        assertNull(session().target(HungerMenuSearchAction.OPEN,menu,cart.copy(items=cart.items.map {it.copy(quantity=2)}),version,1100))
        assertNull(HungerMenuSearchSession.create(menu.copy(nodes=menu.nodes.dropLast(1)),cart,"بلو لاقون",version,1000))
    }
    @Test fun secondEditorAndPaymentOrAuthenticationFieldCannotBecomeQueryTarget() {
        for(id in listOf("password","checkout_button","otp","payment")) {
            val s=session();s.consume(HungerMenuSearchAction.OPEN,menu,menu.nodes[1],cart,version,1100)
            val extra=CatalogNode(3,-1,id,"","android.widget.EditText",true,true,false)
            assertNull(s.target(HungerMenuSearchAction.TYPE,search.copy(nodes=search.nodes+extra),cart,version,1200))
        }
    }
    @Test fun sharedGuardRequiresConsentActiveSessionAndActualProvider() {
        val s=session();val n=menu.nodes[1];val guard=SafetyGuard()
        assertTrue(guard.allowsHungerMenuSearch(HungerMenuSearchAction.OPEN,s,menu,n,cart,ProviderId.HUNGERSTATION.packageName,version,true,true,1100))
        assertFalse(guard.allowsHungerMenuSearch(HungerMenuSearchAction.OPEN,s,menu,n,cart,ProviderId.KEETA.packageName,version,true,true,1100))
        assertFalse(guard.allowsHungerMenuSearch(HungerMenuSearchAction.OPEN,s,menu,n,cart,ProviderId.HUNGERSTATION.packageName,version,false,true,1100))
        assertFalse(guard.allowsHungerMenuSearch(HungerMenuSearchAction.OPEN,s,menu,n,cart,ProviderId.HUNGERSTATION.packageName,version,true,false,1100))
    }
    @Test fun actualTwentyNodeResultContainsOneFoodCandidateAndNeverItsAddChild() {
        val frame=com.google.gson.Gson().fromJson(javaClass.getResource("/recorded-hs/hs-8.0.303-in-menu-search-blue-redacted.json")!!.readText(),CatalogFrame::class.java)
        val s=session();s.consume(HungerMenuSearchAction.OPEN,menu,menu.nodes[1],cart,version,1100)
        s.consume(HungerMenuSearchAction.TYPE,search,search.nodes[1],cart,version,1200)
        val found=s.candidates(frame,cart,version,1300).single()
        assertEquals("بلو لاقون",found.item.name)
        assertEquals(2000L,found.item.listedPrice)
        assertEquals("android.view.View",found.node.className)
        assertTrue(found.node.clickable)
        assertNotEquals("menuItemActionSingleButtonAdd",found.node.id)
        assertTrue(s.candidates(frame.copy(complete=false),cart,version,1300).isEmpty())
        assertTrue(s.candidates(frame,cart.copy(items=cart.items.map {it.copy(quantity=2)}),version,1300).isEmpty())
        assertNotNull(s.target(HungerMenuSearchAction.CLOSE,frame,cart,version,1300))
    }
    private fun actualResults()=com.google.gson.Gson().fromJson(javaClass.getResource("/recorded-hs/hs-8.0.303-in-menu-search-blue-redacted.json")!!.readText(),CatalogFrame::class.java)
    private fun typedSession():HungerMenuSearchSession=session().also {
        assertTrue(it.consume(HungerMenuSearchAction.OPEN,menu,menu.nodes[1],cart,version,1100))
        assertTrue(it.consume(HungerMenuSearchAction.TYPE,search,search.nodes[1],cart,version,1200))
    }
    @Test fun uniqueFoodOpenIsConsumedOnceAndSearchCloseRemainsScoped() {
        val s=typedSession();val result=actualResults()
        val food=s.target(HungerMenuSearchAction.OPEN_RESULT,result,cart,version,1300)!!
        assertEquals(15,food.index)
        assertTrue(s.consume(HungerMenuSearchAction.OPEN_RESULT,result,food,cart,version,1300))
        assertNull(s.target(HungerMenuSearchAction.OPEN_RESULT,result,cart,version,1400))
        assertFalse(s.results(result,cart,version,1400))
        val close=s.target(HungerMenuSearchAction.CLOSE,result,cart,version,1500)!!
        assertTrue(s.consume(HungerMenuSearchAction.CLOSE,result,close,cart,version,1500))
        assertNull(s.target(HungerMenuSearchAction.CLOSE,result,cart,version,1600))
    }
    @Test fun duplicateNameChangedRequestStaleAndNestedAddCannotOpen() {
        val result=actualResults();val s=typedSession()
        val food=s.candidates(result,cart,version,1300).single().node
        assertNull(s.target(HungerMenuSearchAction.OPEN_RESULT,result.copy(nodes=result.nodes+food.copy(index=30)),cart,version,1300))
        assertFalse(s.consume(HungerMenuSearchAction.OPEN_RESULT,result,result.nodes.single{it.id=="menuItemActionSingleButtonAdd"},cart,version,1300))
        assertNull(s.target(HungerMenuSearchAction.OPEN_RESULT,result,cart.copy(items=cart.items.map{it.copy(quantity=2)}),version,1300))
        assertNull(s.target(HungerMenuSearchAction.OPEN_RESULT,result,cart,version,91_001))
        assertNotNull(s.target(HungerMenuSearchAction.OPEN_RESULT,result,cart,version,1400))
    }

}
