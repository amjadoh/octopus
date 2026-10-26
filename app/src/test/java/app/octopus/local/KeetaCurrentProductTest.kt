package app.octopus.local

import app.octopus.local.providers.*
import app.octopus.local.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** Actual Honor 3.13.203 read-only capture, with only whitelisted food/control labels. */
class KeetaCurrentProductTest {
    private val reader = KeetaMenuReader("مدور", "فطيرة جبن فيتا", "3.13.203")
    private fun recorded(kind: String = "product") = recordedPath("/recorded-keeta/keeta-3.13.203-feta-$kind.tsv")
    private fun recordedPath(path: String): CatalogFrame {
        val stream = checkNotNull(javaClass.getResourceAsStream(path))
        val nodes = stream.bufferedReader(Charsets.UTF_8).use { input -> input.readLines().map {it.removePrefix("\uFEFF")}.filter { it.isNotBlank() && !it.startsWith("#") }.map {
            val p = it.split('\t')
            CatalogNode(p[0].toInt(), p[1].toInt(), p[2], String(Base64.getDecoder().decode(p[9]), Charsets.UTF_8),
                p[3], p[4] == "1", p[5] == "1", p[6] == "1", p[7] == "1", p[8] == "1")
        } }
        return CatalogFrame(nodes, true)
    }
    @Test fun currentActualCartContainsExactlyOnePlainFetaAndNoFinalQuote() {
        val frame = recorded("cart")
        val evidence = checkNotNull(KeetaBasketReader(reader).evidence(frame))
        assertTrue(evidence.complete)
        assertEquals(1, evidence.lines.size)
        assertEquals(reader.title, evidence.lines.single().name)
        assertEquals(1, evidence.lines.single().quantity)
        assertTrue(evidence.lines.single().choices.isEmpty())
        assertEquals(700L, evidence.lines.single().lineAmount)
        assertFalse(evidence.recognizedOrderReview)
        assertNull(KeetaBasketReader(reader).evidence(frame.copy(complete=false)))
    }
    private fun twoRowCart(): CatalogFrame {
        val one=recorded("cart")
        var card=one.nodes.last { it.label==reader.title }
        while(!card.clickable) card=one.nodes.single { it.index==card.parent }
        val copy=one.descendants(card.index).map { node -> node.copy(index=node.index+2000,
            parent=if(node.index==card.index)card.parent else node.parent+2000,
            label=if(node.label==reader.title)"صنف ثان" else node.label) }
        return one.copy(nodes=one.nodes+copy)
    }
    @Test fun actualCurrentTwoRowDrawerAccountsForEverySeparateSectionAndSeparator() {
        val nodes=recordedPath("/recorded-keeta/keeta-3.13.203-kudo-two-row-drawer-redacted.tsv").nodes
            .mapIndexed {i,n->if(i==0)n.copy(parent=0) else n}
        val root=listOf(CatalogNode(0,-1,"","","android.view.ViewGroup",false,false,false),
            CatalogNode(1,0,"","كودو","android.widget.TextView",false,false,false),
            CatalogNode(2,0,"","إتمام الدفع","android.widget.TextView",false,false,false))
        val frame=CatalogFrame(root+nodes,true)
        val selected=KeetaMenuReader("كودو","بطاطس وسط","3.13.203")
        val basket=KeetaBasketReader(selected,setOf("بطاطس وسط","جبنة الناتشوز"))
        val candidate=nodes.single {it.className=="android.widget.TextView" && it.omittedText}
        val restored=basket.restoreRequestedRowTitles(frame,mapOf(candidate.index to "جبنة الناتشوز"))
        val evidence=basket.evidence(restored)!!
        assertTrue(evidence.complete)
        assertEquals(mapOf("بطاطس وسط" to 3,"جبنة الناتشوز" to 1),evidence.lines.associate {it.name to it.quantity})
        assertEquals(4000L,evidence.subtotal)
        assertNull(basket.evidence(frame))
        val extra=CatalogNode(9999,candidate.parent,"","","android.widget.TextView",false,false,false,omittedText=true)
        assertNull(basket.evidence(restored.copy(nodes=restored.nodes+extra)))
    }
    @Test fun basketTitleCandidatesNormalizeOnlyFormattingWithoutAdmittingUnknownText() {
        val titles=setOf(reader.title,"صنف ثان")
        assertEquals("صنف ثان",KeetaBasketReader.requestedTitleCandidate(" \u202aصنف ثان\u202c ",titles))
        assertNull(KeetaBasketReader.requestedTitleCandidate("صنف غير مطلوب",titles))
        assertNull(KeetaBasketReader.requestedTitleCandidate("اختر صنف ثان",titles))
    }
    @Test fun actualDrawerStructureRestoresOnlyRequestedRowTitlesAfterRuntimeRedaction() {
        val original=twoRowCart()
        val name=original.nodes.single { it.label=="صنف ثان" }
        val redacted=original.copy(nodes=original.nodes.map { if(it.index==name.index)it.copy(label="",omittedText=true) else it })
        val basket=KeetaBasketReader(reader,setOf(reader.title,"صنف ثان"))
        assertNull(basket.evidence(redacted))
        val restored=basket.restoreRequestedRowTitles(redacted,mapOf(name.index to "صنف ثان"))
        val evidence=basket.evidence(restored)!!
        assertTrue(evidence.complete)
        assertEquals(setOf(reader.title,"صنف ثان"),evidence.lines.map {it.name}.toSet())
        assertNotNull(basket.quantityTarget(restored,true))
        assertNull(basket.evidence(basket.restoreRequestedRowTitles(redacted,mapOf(name.index to "غير مطلوب"))))
        assertNull(basket.evidence(basket.restoreRequestedRowTitles(redacted.copy(complete=false),mapOf(name.index to "صنف ثان"))))
    }
    @Test fun requestedNameInModifierSlotCannotBecomeAnotherPlainBasketTitle() {
        val original=twoRowCart()
        val name=original.nodes.single {it.label=="صنف ثان"}
        val extra=CatalogNode(9000,name.parent,"","","android.widget.TextView",false,false,false,omittedText=true)
        val basket=KeetaBasketReader(reader,setOf(reader.title,"صنف ثان"))
        val frame=original.copy(nodes=original.nodes+extra)
        val restored=basket.restoreRequestedRowTitles(frame,mapOf(extra.index to "صنف ثان"))
        assertTrue(restored.nodes.single {it.index==extra.index}.omittedText)
        assertNull(basket.evidence(restored))
        var scroll=frame.nodes.single {it.index==name.parent}
        while(scroll.className!="android.widget.ScrollView") scroll=frame.nodes.single {it.index==scroll.parent}
        val scrolling=frame.copy(nodes=frame.nodes.map {if(it.index==scroll.index)it.copy(scrollable=true) else it})
        assertNull(basket.evidence(basket.restoreRequestedRowTitles(scrolling,mapOf(extra.index to "صنف ثان"))))
    }
    @Test fun basketNamesNeverMakeAnUnknownProductModifierSafeToAdd() {
        val original=recorded()
        val body=reader.productBody(original)!!
        val extra=CatalogNode(9000,body.index,"","","android.widget.TextView",false,false,false,omittedText=true)
        val basket=KeetaBasketReader(reader,setOf(reader.title,"صنف ثان"))
        val frame=original.copy(nodes=original.nodes+extra)
        val restored=basket.restoreRequestedRowTitles(frame,mapOf(extra.index to "صنف ثان"))
        assertTrue(restored.nodes.single {it.index==extra.index}.omittedText)
        assertNull(reader.productBody(restored))
        assertNull(basket.addTarget(restored,700))
    }
    @Test fun multiRowKeetaReviewAdmissionRequiresTheExactJustVerifiedDrawerAndFooterCount() {
        var source=twoRowCart()
        var card=source.nodes.single {it.label=="صنف ثان"}
        while(!card.clickable)card=source.nodes.single {it.index==card.parent}
        val second=source.descendants(card.index).map {it.index}.toSet()
        source=source.copy(nodes=source.nodes.map {
            if(it.index in second && it.clickable && it.className=="android.widget.TextView" && it.label=="1")it.copy(label="2")
            else if(it.index in second && it.label.contains('﷼'))it.copy(label="14.00 ﷼") else it})
        val request=CanonicalCart(reader.restaurant,null,listOf(CanonicalItem(reader.title,null,emptyList(),1),CanonicalItem("صنف ثان",null,emptyList(),2)))
        val evidence=KeetaBasketReader(reader,request.items.map {it.name}.toSet()).evidence(source)!!
        val record=PreparedBasketRecord.create(request,evidence,reader.VERSION,1000)!!
        val raw=recorded("optional-product")
        val close=reader.closeProduct(raw)!!
        val box=raw.nodes.single {it.index==close.parent}
        val modal=raw.nodes.single {it.index==box.parent}
        val removed=raw.descendants(modal.index).map {it.index}.toSet()
        val menu=raw.copy(nodes=raw.nodes.filterNot {it.index in removed})
        val permit=app.octopus.local.safety.PreparedReviewAdmission.create(ProviderId.KEETA,request,record,source,reader.VERSION,1000)!!
        assertNotNull(app.octopus.local.safety.ReviewNavigationGuard.target(ProviderId.KEETA,menu,request,reader.VERSION,permit,1001))
        val bag=KeetaBasketReader(reader).footerBag(menu)!!
        val count=menu.descendants(bag.index).single {it.label=="3"}
        val changed=menu.copy(nodes=menu.nodes.map {if(it.index==count.index)it.copy(label="4") else it})
        assertNull(app.octopus.local.safety.ReviewNavigationGuard.target(ProviderId.KEETA,changed,request,reader.VERSION,permit,1001))
        assertNull(app.octopus.local.safety.PreparedReviewAdmission.create(ProviderId.KEETA,request,record,menu,reader.VERSION,1000))
    }
    @Test fun currentRecordedCartHasQuantityControlsAndCannotRemoveLastItem() {
        val frame = recorded("cart")
        val basket = KeetaBasketReader(reader)
        val increase = checkNotNull(basket.quantityTarget(frame, true))
        assertTrue(increase.clickable)
        assertTrue(increase.label.isEmpty())
        assertNull(basket.quantityTarget(frame, false))
        assertNull(basket.quantityTarget(frame.copy(complete = false), true))
    }
    @Test fun recordedCurrentProductHasAnUnambiguousUnitPriceAndBoundedCloseAndAddTargets() {
        val frame = recorded()
        assertTrue(reader.product(frame))
        assertFalse(reader.menu(frame))
        assertEquals(700L, reader.unitPrice(frame))
        assertNotNull(reader.closeProduct(frame))
        val add = KeetaBasketReader(reader).addTarget(frame, 700)
        assertNotNull(add)
        assertNotEquals(reader.closeProduct(frame), add)
        assertNull(KeetaBasketReader(reader).addTarget(frame, 800))
    }
    @Test fun nativeMenuCardCanBeOpenedReadOnlyWithoutInventingItsHiddenDeliveryContext() {
        val frame = recorded()
        val modal = reader.productBody(frame)!!.let { body ->
            val wrapper = frame.nodes.single { it.index == body.parent }
            frame.nodes.single { it.index == wrapper.parent }
        }
        val omitted = frame.descendants(modal.index).map { it.index }.toSet()
        val menu = frame.copy(nodes = frame.nodes.filterNot { it.index in omitted })
        assertTrue(reader.menu(menu))
        assertNotNull(reader.productTarget(menu))
    }
    @Test fun extraChoiceContentOrProtectedIncompleteFramesAreRejected() {
        val frame = recorded()
        assertFalse(reader.product(frame.copy(complete = false)))
        val body = reader.productBody(frame)!!
        val changed = frame.copy(nodes = frame.nodes + CatalogNode(1000, body.index, "", "اختيار إلزامي", "android.widget.TextView", false, false, false))
        assertTrue(reader.product(changed))
        assertNull(reader.productBody(changed))
        assertFalse(KeetaMenuReader("مطعم آخر", reader.title, reader.VERSION).product(frame))
        val basket = KeetaBasketReader(reader)
        assertNull(basket.addTarget(changed, 700))
        assertNull(basket.addTarget(frame.copy(nodes = frame.nodes.map {
            if (it.index == body.index) it.copy(scrollable = true) else it
        }), 700))
        assertNull(basket.addTarget(frame.copy(complete = false), 700))
    }
    @Test fun observedNutritionHeadingIsNotAnUnknownModifier() {
        assertEquals("معلومات التغذية", reader.functionalLabel("معلومات التغذية"))
        val frame=recorded()
        val body=reader.productBody(frame)!!
        val label=reader.functionalLabel("معلومات التغذية")
        val heading=CatalogNode(1001,body.index,"",label,"android.widget.TextView",false,false,false,
            omittedText=label.isEmpty())
        assertNotNull(reader.productBody(frame.copy(nodes=frame.nodes+heading)))
        assertNull(reader.productBody(frame.copy(nodes=frame.nodes+heading.copy(label="",omittedText=true))))
    }
    @Test fun observedMenuTitleFieldKeepsUnrelatedPublicTitlesDistinctForScrolling() {
        val id="com.sankuai.sailor.afooddelivery:id/product_name"
        assertEquals("صنف آخر",reader.functionalLabel("صنف آخر",id))
        assertEquals("صنف مختلف",reader.functionalLabel("صنف مختلف",id))
        assertEquals("",reader.functionalLabel("صنف آخر","unrelated:id/text"))
        assertEquals("",reader.functionalLabel("صنف آخر","com.sankuai.sailor.afooddelivery:id/address"))
        assertEquals("",KeetaMenuReader(reader.restaurant,reader.title,"3.12.501").functionalLabel("صنف آخر",id))
    }
    @Test fun observedAllergensInformationHeadingIsNotASelectableModifier() {
        assertEquals("مسببات الحساسية",reader.functionalLabel("مسببات الحساسية"))
        val frame=recorded()
        val body=reader.productBody(frame)!!
        val heading=CatalogNode(1002,body.index,"",reader.functionalLabel("مسببات الحساسية"),
            "android.widget.TextView",false,false,false)
        assertNotNull(reader.productBody(frame.copy(nodes=frame.nodes+heading)))
        assertNull(reader.productBody(frame.copy(nodes=frame.nodes+heading.copy(label="اختر مسببات الحساسية"))))
        assertNull(reader.productBody(frame.copy(nodes=frame.nodes+heading.copy(label="",omittedText=true))))
    }
    @Test fun actualOptionalChipsModalCanBeClosedButCannotBeAddedAsPlainProduct() {
        val raw=recorded("optional-product")
        val frame=raw.copy(nodes=raw.nodes.map { n ->
            val value=reader.functionalLabel(n.label)
            n.copy(label=value,omittedText=n.omittedText || (n.label.any(Char::isLetter)&&value.isBlank()))
        })
        assertTrue(reader.product(frame))
        assertNotNull(reader.closeProduct(frame))
        assertNull(reader.productBody(frame))
        assertNull(KeetaBasketReader(reader).addTarget(frame,700))
        val cart=CanonicalCart(reader.restaurant,null,listOf(CanonicalItem(reader.title,null,emptyList(),3)))
        assertNull(reader.itemPreview(frame,cart,1000))
        assertNull(reader.closeProduct(frame.copy(complete=false)))
    }
    @Test fun observedCurrentMenuReviewButtonUsesItsActionChildInsteadOfWrapper() {
        val raw=recorded("optional-product")
        val close=reader.closeProduct(raw)!!
        val closeContainer=raw.nodes.single {it.index==close.parent}
        val modal=raw.nodes.single {it.index==closeContainer.parent}
        val removed=raw.descendants(modal.index).map {it.index}.toSet()
        val menu=raw.copy(nodes=raw.nodes.filterNot {it.index in removed})
        val cart=CanonicalCart(reader.restaurant,null,listOf(CanonicalItem(reader.title,null,emptyList(),3)))
        assertTrue(reader.menu(menu))
        val target=app.octopus.local.safety.ReviewNavigationGuard.target(ProviderId.KEETA,menu,cart,reader.VERSION)!!
        assertEquals(210,target.index)
        assertTrue(target.clickable)
        assertNull(app.octopus.local.safety.ReviewNavigationGuard.target(ProviderId.KEETA,menu,cart.copy(
            items=listOf(cart.items.single().copy(quantity=2))),reader.VERSION))
    }
    @Test fun currentRecordedProductProducesOnlyItsBasePriceWithoutInventingDeliveryOrDiscounts() {
        val cart = CanonicalCart(reader.restaurant, null, listOf(CanonicalItem(reader.title, null, emptyList(), 2)), null)
        val preview = reader.itemPreview(recorded(), cart, 1000)!!
        assertEquals(1400L, preview.itemSubtotal)
        assertNull(preview.finalTotal)
        assertNull(preview.deliveryBeforeOffers)
        assertTrue(preview.rules.isEmpty())
        assertTrue(preview.offers.isEmpty())
        assertEquals(cart.fingerprint, preview.cartFingerprint)
        assertNull(reader.itemPreview(recorded(), cart.copy(restaurant = "مطعم آخر"), 1000))
        assertNull(reader.itemPreview(recorded().copy(complete = false), cart, 1000))
    }
}
