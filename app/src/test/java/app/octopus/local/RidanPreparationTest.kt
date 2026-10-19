package app.octopus.local

import app.octopus.local.automation.*
import app.octopus.local.domain.*
import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test

class RidanPreparationTest {
    private val now = 1000L
    private fun node(i: Int, id: String = "", text: String = "", click: Boolean = false, cls: String = "android.view.View", parent: Int = -1) =
        CatalogNode(i, parent, id, text, cls, click, false, false)
    private val add = node(4, "flutterAddButton", "إضافة\n§\n30", true, "android.widget.Button")
    private val product = CatalogFrame(listOf(node(0,"pdpCloseButton",click=true), node(1,text=RidanCartProgram.TITLE),
        node(2,text="غالبًا ما يتم طلبه مع"), node(3,text="1"), add), true)
    private val emptyMenu = CatalogFrame(listOf(node(0,"menuBackButton"),node(1,"menuSearchButton"), node(2,text="ريدان")), true)
    private fun details(frame: CatalogFrame = product, restaurant: String = "ريدان"): ConfiguredProduct {
        val reader = HungerStationProductReader(LiveRestaurant(restaurant,null,restaurant), LiveMenuItem(RidanCartProgram.TITLE,3000,"item"))
        reader.observe(frame)
        return reader.finish(now)
    }
    private val selection = ProductSelection("item", emptyMap(), 1)
    @Test fun fixedMealHasNoRequiredOptionsAndCanBeSelected() {
        assertTrue(details().optionsComplete)
        assertTrue(RidanCartProgram.supports(details(),selection,now))
        assertFalse(details(restaurant="مطعم آخر").optionsComplete)
    }
    @Test fun missingBottomOrNewRequiredGroupStaysUnsupported() {
        assertFalse(details(product.copy(nodes=product.nodes.filterNot { it.index==2 })).optionsComplete)
        val changed=product.copy(nodes=listOf(product.nodes[0],product.nodes[1],node(5,text="اختياري"))+product.nodes.drop(2))
        assertFalse(details(changed).optionsComplete)
    }
    @Test fun quantityAndUnknownOptionsCannotBeAdded() {
        assertFalse(RidanCartProgram.supports(details(),selection.copy(quantity=2),now))
        assertFalse(RidanCartProgram.supports(details(),selection.copy(choices=mapOf("fake" to setOf("fake"))),now))
    }
    @Test fun addIsSingleUseIncludingFailedDispatch() {
        val program=checkNotNull(RidanCartProgram.create(details(),selection,emptyMenu,now))
        assertTrue(program.consumeAdd(product,add,now))
        assertFalse(program.consumeAdd(product,add,now))
    }
    @Test fun existingCartAndUnknownContextAreNotReplaced() {
        assertNull(RidanCartProgram.create(details(),selection,emptyMenu.copy(nodes=emptyMenu.nodes+node(8,"FlutterMenu_goToCartButton")),now))
        assertNull(RidanCartProgram.create(details(),selection,emptyMenu.copy(complete=false),now))
        assertNull(RidanCartProgram.create(details(),selection,emptyMenu.copy(nodes=emptyMenu.nodes.map { if(it.index==2) it.copy(label="آخر") else it }),now))
    }
    @Test fun paymentAndChangedProductAndQuantityAreDenied() {
        val program=checkNotNull(RidanCartProgram.create(details(),selection,emptyMenu,now))
        assertFalse(program.consumeAdd(product,add.copy(label="تأكيد الطلب"),now))
        assertFalse(program.consumeAdd(product.copy(nodes=product.nodes.map { if(it.index==3) it.copy(label="2") else it }),add,now))
        assertFalse(program.consumeAdd(product.copy(complete=false),add,now))
        assertFalse(program.consumeAdd(product,add,now+120001))
    }
    @Test fun plainBasketRowParsesWithoutInventingChoices() {
        val cart=CatalogFrame(listOf(node(0,"FlutterCart_title","السلة\nريدان"),
            node(1,"FlutterCart_cartItem","نصف دجاج مضغوط\n30\n§"),
            node(2,"menuItemActionDualButtonValue","1",parent=1)),true)
        val result=BasketEvidenceReader.hungerStation(cart,false)
        assertEquals(1,result.lines.single().quantity)
        assertEquals(3000L,result.lines.single().lineAmount)
        assertTrue(result.lines.single().choices.isEmpty())
        assertFalse(result.complete)
        assertFalse(result.recognizedOrderReview)
    }
}
