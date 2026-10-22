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
        assertTrue(details(restaurant="مطعم آخر").optionsComplete)
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
    @Test fun providerBidiFormattingDoesNotChangeTheValidatedAddAmount() {
        val formatted = product.copy(nodes=product.nodes.map {
            if(it.id=="flutterAddButton") it.copy(label="إضافة\n\u202a§\n30\u202c") else it
        })
        val program=checkNotNull(RidanCartProgram.create(details(formatted),selection,emptyMenu,now))
        assertTrue(program.consumeAdd(formatted,formatted.nodes.last(),now))
        assertNull(app.octopus.local.pricing.MoneyParser.parse("7\u202c 6"))
    }
    private fun optionalProduct(extraPrice: String = "6", total: String = "30"): CatalogFrame {
        val changedAdd = add.copy(label = "إضافة\n§\n$total")
        return product.copy(nodes = listOf(product.nodes[0], product.nodes[1],
            node(10,text="يمكنك اضافة"), node(11,text="اختيار واحد"),
            node(12,text="بطاطس\n(§\n$extraPrice\n+)",click=true), product.nodes[2], product.nodes[3], changedAdd))
    }
    @Test fun observedPaidOptionalChoiceCanBeOmittedOnlyWhenAddTotalStaysAtBasePrice() {
        val frame = optionalProduct()
        val program = checkNotNull(RidanCartProgram.create(details(frame),selection,emptyMenu,now))
        assertTrue(program.consumeAdd(frame,frame.nodes.last(),now))
        val selected = optionalProduct(total="36")
        val denied = checkNotNull(RidanCartProgram.create(details(frame),selection,emptyMenu,now))
        assertFalse(denied.consumeAdd(selected,selected.nodes.last(),now))
    }
    @Test fun freeOrUnknownOptionalSurchargeCannotUseThePlainItemPermit() {
        for (price in listOf("0", "مجاني", "غير معروف")) {
            val frame = optionalProduct(price)
            val program = checkNotNull(RidanCartProgram.create(details(frame),selection,emptyMenu,now))
            assertFalse(program.consumeAdd(frame,frame.nodes.last(),now))
        }
    }
    @Test fun recordedPastryExtrasAndNutritionMetadataAllowOnlyUnselectedPaidExtras() {
        val frame = optionalProduct().let { original -> original.copy(nodes =
            listOf(node(20, text="الحقائق الغذائية", click=true, cls="android.widget.ImageView")) +
                original.nodes.map { if(it.label == "يمكنك اضافة") it.copy(label="اضافات فطاير") else it }) }
        val parsed = details(frame)
        assertTrue(parsed.optionsComplete)
        assertEquals(0, parsed.product.groups.single().minimum)
        val permit = checkNotNull(RidanCartProgram.create(parsed,selection,emptyMenu,now))
        assertTrue(permit.consumeAdd(frame,frame.nodes.last(),now))
        val required = frame.copy(nodes=frame.nodes.flatMap {
            if(it.label=="اضافات فطاير") listOf(it,node(21,text="مطلوب")) else listOf(it)
        })
        assertFalse(RidanCartProgram.supports(details(required),selection,now))
        val unknown = frame.copy(nodes=frame.nodes.map { if(it.label=="اضافات فطاير") it.copy(label="خيارات غير معروفة") else it })
        assertFalse(details(unknown).optionsComplete)
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
    @Test fun actualNoModificationsLabelIsNotAnAddedChoice() {
        val cart=CatalogFrame(listOf(node(0,"FlutterCart_title","السلة\nمدور"),
            node(1,"FlutterCart_cartItem","فطيرة جبن فيتا\nلايوجد تعديلات أو إضافات\nتعديل\n7\n§"),
            node(2,"menuItemActionDualButtonValue","1",parent=1)),true)
        val actual=BasketEvidenceReader.hungerStation(cart,true)
        assertTrue(actual.complete)
        assertTrue(actual.lines.single().choices.isEmpty())
        assertEquals(700L,actual.lines.single().lineAmount)
        val changed=cart.copy(nodes=cart.nodes.map { if(it.index==1)it.copy(label=it.label.replace("لايوجد تعديلات أو إضافات","شيبس")) else it })
        assertEquals("شيبس",BasketEvidenceReader.hungerStation(changed,true).lines.single().choices.single().value)
    }
    @Test fun otherRestaurantAndMealUseOwnOneShotPermit() {
        val name="مدور"; val title="فطيرة شكشوكة بالجبن"
        val frame=product.copy(nodes=product.nodes.map { if(it.label==RidanCartProgram.TITLE)it.copy(label=title) else it })
        val reader=HungerStationProductReader(LiveRestaurant(name,null,name),LiveMenuItem(title,3000,"item"))
        reader.observe(frame);val details=reader.finish(now)
        val menu=emptyMenu.copy(nodes=emptyMenu.nodes.map { if(it.label=="ريدان")it.copy(label=name) else it })
        assertTrue(details.optionsComplete)
        val program=checkNotNull(RidanCartProgram.create(details,selection,menu,now))
        assertFalse(program.consumeAdd(product,add,now))
        assertTrue(program.consumeAdd(frame,add,now))
        assertFalse(program.consumeAdd(frame,add,now))
        assertNull(RidanCartProgram.create(details,selection,emptyMenu,now))
    }
    @Test fun unknownChoiceRowsAndIncompleteFramesCannotBecomePlainMeals() {
        val unknown=product.copy(nodes=listOf(product.nodes[0],product.nodes[1],node(9,text="كبير",click=true))+product.nodes.drop(2))
        assertFalse(details(unknown).optionsComplete)
        assertThrows(IllegalArgumentException::class.java) { details(product.copy(complete=false)) }
    }
    @Test fun changedAddPriceCannotConsumeAValidPermit() {
        val program=checkNotNull(RidanCartProgram.create(details(),selection,emptyMenu,now))
        val changed=add.copy(label="إضافة\n§\n31")
        assertFalse(program.consumeAdd(product.copy(nodes=product.nodes.map { if(it.id==changed.id)changed else it }),changed,now))
        assertTrue(program.consumeAdd(product,add,now))
    }
}
