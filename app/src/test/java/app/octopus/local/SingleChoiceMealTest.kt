package app.octopus.local

import app.octopus.local.automation.SingleChoiceMeal
import app.octopus.local.domain.*
import app.octopus.local.providers.*
import app.octopus.local.safety.*
import org.junit.Assert.*
import org.junit.Test

class SingleChoiceMealTest {
    private val groups = listOf(
        MenuChoiceGroup("side", "الجانب", 1, 1, listOf(MenuChoice("a", "جانب أ", true), MenuChoice("b", "جانب ب", true))),
        MenuChoiceGroup("drink", "المشروب", 1, 1, listOf(MenuChoice("c", "مشروب أ", true), MenuChoice("d", "مشروب ب", true))))
    private val product = ConfiguredProduct("مطعم تجريبي", MenuProduct("meal", "وجبة تجريبية", null, false, 2000, groups),
        CatalogProvenance(ProviderId.HUNGERSTATION, HungerStationCatalog.TESTED_VERSION, 1000, "schema"), true, null)
    private val source = BasketEvidence(ProviderId.HUNGERSTATION, "مطعم تجريبي", listOf(
        BasketLineEvidence("وجبة تجريبية", listOf(BasketChoiceEvidence("جانب أ", 1), BasketChoiceEvidence("مشروب ب", null)), 1, 2000)), 2000, true)

    @Test fun resolvesOnlyFromCompleteRequiredSingleChoiceSchemaWithoutDroppingChoices() {
        val meal = SingleChoiceMeal.create(source, product, 2000)!!
        assertEquals(listOf("جانب أ", "مشروب ب"), meal.cart.items.single().options.map { it.value })
        assertEquals(listOf(1, 1), meal.resolve(source, 2000)!!.lines.single().choices.map { it.quantity })
        assertNull(source.lines.single().choices.last().quantity) // original evidence remains unmodified
        assertNull(meal.cart.addressReference)
        assertNull(meal.cart.branch)
    }
    @Test fun unknownMissingDuplicateAndMultipleSelectionsAreRejected() {
        val row = source.lines.single()
        for (choices in listOf(row.choices.dropLast(1), row.choices + row.choices.first(),
            row.choices.map { it.copy(quantity = 2) }, row.choices.map { it.copy(value = "غير معروف") }))
            assertNull(SingleChoiceMeal.create(source.copy(lines = listOf(row.copy(choices = choices))), product, 2000))
        val multi = product.copy(product = product.product.copy(groups = groups.map { it.copy(maximum = 2) }))
        assertNull(SingleChoiceMeal.create(source, multi, 2000))
    }
    @Test fun partialStaleChangedPriceForeignProductAndAmbiguousGroupCannotResolve() {
        assertNull(SingleChoiceMeal.create(source, product.copy(optionsComplete = false), 2000))
        assertNull(SingleChoiceMeal.create(source, product, 31_001))
        assertNull(SingleChoiceMeal.create(source, product, 999))
        assertNull(SingleChoiceMeal.create(source.copy(subtotal = 1900), product, 2000))
        assertNull(SingleChoiceMeal.create(source, product.copy(restaurant = "مطعم آخر"), 2000))
        val duplicate = groups.last().copy(choices = groups.last().choices + MenuChoice("other", "جانب أ", true))
        assertNull(SingleChoiceMeal.create(source, product.copy(product = product.product.copy(groups = listOf(groups.first(), duplicate))), 2000))
    }
    @Test fun targetMustHaveExactlySameMealChoicesQuantityAndReconciledMoney() {
        val meal = SingleChoiceMeal.create(source, product, 2000)!!
        val target = source.copy(provider = ProviderId.KEETA)
        assertNotNull(meal.resolve(target, 3000))
        assertNull(meal.resolve(target.copy(complete = false), 3000))
        assertNull(meal.resolve(target.copy(lines = target.lines + target.lines), 3000))
        assertNull(meal.resolve(target.copy(lines = listOf(target.lines.single().copy(quantity = 2))), 3000))
        assertNull(meal.resolve(target, 602_001))
    }
    @Test fun exactNamedVariantAndUnselectedOptionalGroupsPreserveTheFullCartName() {
        val variantName = "وجبة تجريبية - وسط"
        val variant = MenuChoiceGroup("variant", "اختر العنصر", 1, 1,
            listOf(MenuChoice("medium", variantName, true), MenuChoice("large", "وجبة تجريبية - كبير", true)))
        val optional = MenuChoiceGroup("extras", "اضافة", 0, 2,
            listOf(MenuChoice("extra-a", "إضافة أ", true), MenuChoice("extra-b", "إضافة ب", true)))
        val schema = product.copy(product = product.product.copy(groups = listOf(variant) + groups + optional))
        val basket = source.copy(lines = listOf(source.lines.single().copy(name = variantName)))
        assertEquals(variantName, SingleChoiceMeal.create(basket, schema, 2000)!!.cart.items.single().name)
        assertNull(SingleChoiceMeal.create(basket, product, 2000))
        val extra = basket.copy(lines = listOf(basket.lines.single().copy(choices = basket.lines.single().choices + BasketChoiceEvidence("إضافة أ", null))))
        assertNull(SingleChoiceMeal.create(extra, schema, 2000))
    }
    @Test fun reviewRequiresFreshSchemaProofBoundToTheExactRawCartAndIsSingleUse() {
        fun node(i:Int,p:Int,id:String,label:String,click:Boolean=false,cls:String="android.view.View") =
            CatalogNode(i,p,id,label,cls,click,false,false)
        val frame=CatalogFrame(listOf(
            node(0,-1,"FlutterCart_title","السلة\nمطعم تجريبي"),
            node(1,-1,"FlutterCart_scroll_view","المجموع\n20\n§"),
            node(2,1,"FlutterCart_cartItem","وجبة تجريبية\n1x جانب أ، مشروب ب\nتعديل\n20\n§",true),
            node(3,2,"menuItemActionDualButtonValue","1"),
            node(4,-1,"FlutterCart_footer_main_action_button","اذهب للدفع",true,"android.widget.Button")),true)
        assertEquals(source,BasketEvidenceReader.hungerStation(frame,true))
        val edit = app.octopus.local.automation.HungerStationCartProductCommand.create(frame, source, 2000)!!
        assertFalse(edit.consume(frame.copy(complete=false), edit.target, HungerStationCatalog.TESTED_VERSION, 2000))
        assertTrue(edit.consume(frame, edit.target, HungerStationCatalog.TESTED_VERSION, 2000))
        assertFalse(edit.consume(frame, edit.target, HungerStationCatalog.TESTED_VERSION, 2000))
        val meal=SingleChoiceMeal.create(source,product,2000)!!
        val proof=ConfiguredBasketVerification.source(meal,frame,source,2000)!!
        val store = object : app.octopus.local.automation.BasketJournalStore {
            var values = emptyMap<String, String>()
            override fun read() = values
            override fun write(values: Map<String, String>) { this.values = values }
        }
        val journal = app.octopus.local.automation.BasketMutationJournal(store)
        journal.beforeDispatch(ProviderId.HUNGERSTATION, meal.cart)
        assertFalse(journal.observeConfigured(meal.cart, proof, frame.copy(complete=false), HungerStationCatalog.TESTED_VERSION, 2000))
        assertFalse(journal.observeConfigured(meal.cart, proof, frame, HungerStationCatalog.TESTED_VERSION, 17_001))
        assertTrue(store.values.containsKey("pending.HUNGERSTATION"))
        assertTrue(journal.observeConfigured(meal.cart, proof, frame, HungerStationCatalog.TESTED_VERSION, 2000))
        assertFalse(store.values.containsKey("pending.HUNGERSTATION"))
        assertTrue(runCatching { journal.requireFirstAdd(ProviderId.HUNGERSTATION, meal.cart) }.isFailure)
        val record=PreparedBasketRecord.create(meal.cart,proof.resolved,HungerStationCatalog.TESTED_VERSION,2000)!!
        assertNull(PreparedReviewAdmission.create(ProviderId.HUNGERSTATION,meal.cart,record,frame,HungerStationCatalog.TESTED_VERSION,2000))
        assertNull(PreparedReviewAdmission.create(ProviderId.HUNGERSTATION,meal.cart,record,frame,HungerStationCatalog.TESTED_VERSION,17_001,proof))
        val admission=PreparedReviewAdmission.create(ProviderId.HUNGERSTATION,meal.cart,record,frame,HungerStationCatalog.TESTED_VERSION,2000,proof)!!
        val target=ReviewNavigationGuard.target(ProviderId.HUNGERSTATION,frame,meal.cart,HungerStationCatalog.TESTED_VERSION,admission,2000)!!
        val changed=frame.copy(nodes=frame.nodes.map {if(it.id=="menuItemActionDualButtonValue")it.copy(label="2") else it})
        assertNull(ReviewNavigationGuard.target(ProviderId.HUNGERSTATION,changed,meal.cart,HungerStationCatalog.TESTED_VERSION,admission,2000))
        assertTrue(admission.consume(frame,target,ProviderId.HUNGERSTATION,meal.cart,HungerStationCatalog.TESTED_VERSION,2000))
        assertFalse(admission.consume(frame,target,ProviderId.HUNGERSTATION,meal.cart,HungerStationCatalog.TESTED_VERSION,2000))
    }
}
