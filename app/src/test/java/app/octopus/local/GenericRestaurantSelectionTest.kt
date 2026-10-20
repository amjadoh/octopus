package app.octopus.local

import app.octopus.local.domain.*
import app.octopus.local.providers.*
import app.octopus.local.safety.SafetyGuard
import org.junit.Assert.*
import org.junit.Test

/** Synthetic identities on observed layout grammar. Not proof of live support for these restaurants. */
class GenericRestaurantSelectionTest {
    private fun n(i:Int, p:Int=-1, label:String="", id:String="", cls:String="android.view.View", click:Boolean=false, edit:Boolean=false) =
        CatalogNode(i,p,id,label,cls,click,edit,false)

    @Test fun requiredSingleChoiceGrammarWorksWithoutANameWhitelist() {
        val reader=HungerStationProductReader(LiveRestaurant("مطعم مختار",null,"مطعم مختار"),LiveMenuItem("فطيرة",800,"meal"))
        reader.observe(CatalogFrame(listOf(n(0,id="pdpCloseButton"),n(1,label="فطيرة"),
            n(2,label="نوع العجين"),n(3,label="مطلوب"),n(4,label="اختيار واحد"),
            n(5,label="أسمر\n§\n1",click=true),n(6,label="غالبًا ما يتم طلبه مع"),
            n(7,label="إضافة\n§\n8",id="flutterAddButton",cls="android.widget.Button",click=true)),true))
        val details=reader.finish(1000)
        assertTrue(details.optionsComplete)
        assertEquals("نوع العجين",details.product.groups.single().title)
        assertEquals(SelectionResult.Rejected(SelectionError.REQUIRED_CHOICES),
            MenuSelectionValidator().selectConfigured(details,ProductSelection("meal",emptyMap(),1),1000))
        val selected=MenuSelectionValidator().selectConfigured(details,ProductSelection("meal",mapOf("نوع العجين" to setOf("أسمر")),2),1000)
        assertTrue(selected is SelectionResult.Accepted)
        assertEquals("أسمر",(selected as SelectionResult.Accepted).item.options.single().value)
    }

    @Test fun automaticSimpleMealFlowDoesNotSilentlyDropOptionsOrOtherItems() {
        val simple=CanonicalCart("مطعم مختار",null,listOf(CanonicalItem("صنف",null,emptyList(),1)))
        assertTrue(PreviewEvidence.supports(simple))
        assertFalse(PreviewEvidence.supports(simple.copy(items=listOf(simple.items.single().copy(options=listOf(CartOption("حجم","كبير")))))))
        assertFalse(PreviewEvidence.supports(simple.copy(items=simple.items+simple.items.single().copy(name="صنف آخر"))))
    }

    @Test fun searchControlsAreUniqueAndBoundToTheirObservedParents() {
        val reader=KeetaMenuReader("مدور","فطيرة")
        val home=CatalogFrame(listOf(n(0,id="com.sankuai.sailor.afooddelivery:id/search_bar_container"),
            n(1,0,"homepage header searchbar",cls="android.widget.FrameLayout",click=true)),true)
        assertEquals(1,reader.homeSearch(home)?.index)
        assertNull(reader.homeSearch(home.copy(nodes=home.nodes.map { if(it.index==1)it.copy(parent=-1) else it })))
        val search=CatalogFrame(listOf(n(0,label="search page box",cls="android.widget.FrameLayout"),
            n(1,0,cls="android.widget.EditText",edit=true),
            n(2,label="search page btn",cls="android.view.ViewGroup",click=true)),true)
        assertEquals(1,reader.searchInput(search)?.index)
        assertEquals(2,reader.searchSubmit(search)?.index)
        assertNull(reader.searchInput(search.copy(nodes=search.nodes+search.nodes[1].copy(index=3))))
        assertNull(reader.searchSubmit(search.copy(complete=false)))
        assertEquals("",reader.functionalLabel("معلومات عنوان خاصة"))
    }

    @Test fun aSearchQueryIsNotARecognizedRestaurantMenu() {
        val reader=KeetaMenuReader("مدور","فطيرة")
        val query=CatalogFrame(listOf(n(0,label="مدور",id="x:id/tv_keyword",cls="android.widget.TextView",click=true),
            n(1,id="x:id/titlebar"),n(2,id="x:id/flash_list_view"),
            n(3,cls="androidx.recyclerview.widget.RecyclerView").copy(scrollable=true)),true)
        assertNotNull(reader.editSearchQuery(query))
        assertFalse(reader.menu(query))
        assertNull(reader.searchCandidate(query))
    }
    @Test fun searchRequiresConsentActiveSessionExpectedVersionAndExactNode() {
        val reader=KeetaMenuReader("مدور","فطيرة")
        val frame=CatalogFrame(listOf(n(0,label="search page box",cls="android.widget.FrameLayout"),
            n(1,0,cls="android.widget.EditText",edit=true)),true)
        val target=checkNotNull(reader.searchInput(frame))
        val guard=SafetyGuard()
        fun allowed(consent:Boolean=true,active:Boolean=true,version:String=reader.VERSION,node:CatalogNode=target)=
            guard.allowsKeetaSearch(KeetaSearchAction.ENTER_QUERY,reader,frame,node,ProviderId.KEETA.packageName,version,consent,active)
        assertTrue(allowed())
        assertFalse(allowed(consent=false));assertFalse(allowed(active=false));assertFalse(allowed(version="unknown"))
        assertFalse(allowed(node=target.copy(index=9)))
        assertFalse(guard.allowsKeetaSearch(KeetaSearchAction.SUBMIT_QUERY,reader,frame,target,ProviderId.KEETA.packageName,reader.VERSION,true,true))
    }
}
