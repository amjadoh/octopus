package app.octopus.local

import app.octopus.local.providers.*
import app.octopus.local.domain.*
import org.junit.Assert.*
import org.junit.Test

class MenuCostPreviewTest {
    private val cart = CanonicalCart("ريدان", null, listOf(CanonicalItem("نصف دجاج مضغوط", null, emptyList(), 1)))
    private fun n(i: Int, parent: Int = -1, label: String = "", click: Boolean = false) =
        CatalogNode(i,parent,"",label,"android.view.ViewGroup",click,false,false)
    private val product = CatalogFrame(listOf(n(0,label="ريدان"),n(10).copy(className="android.widget.ScrollView"),n(1,10,label="نصف دجاج مضغوط"),
        n(2,10,label="طلب خاص"), n(3,click=true),n(4,3,"إضافة إلى السلة"),n(5,3,"‏30.00 ﷼‏"),
        n(6,label="‏22 ﷼‏"),n(7,label="توصيل مجاني"),n(8,label="الحد الأدنى ‏30 ﷼‏")),true)
    @Test fun displayedOffersNeverBecomeAppliedDiscountOrFinalTotal() {
        val preview=MenuCostPreview(ProviderId.KEETA,cart.fingerprint,3000,
            listOf("خصم 10% من 60 ﷼", "توصيل مجاني", "الحد الأدنى 30 ﷼"),1000,KeetaMenuPreview.VERSION)
        assertEquals(3000L,preview.itemSubtotal)
        assertNull(preview.finalTotal)
        assertTrue(preview.fresh(cart,1001))
        assertFalse(preview.fresh(cart.copy(items=cart.items.map { it.copy(quantity=2) }),1001))
        assertFalse(preview.fresh(cart,301001))
        assertFalse(preview.fresh(cart,999))
    }
    @Test fun priceMustBelongToProductAddControlNotDeliveryOrRecommendation() {
        assertEquals(3000L,KeetaMenuPreview.price(product))
        assertNull(KeetaMenuPreview.price(product.copy(complete=false)))
        assertNull(KeetaMenuPreview.price(product.copy(nodes=product.nodes+n(9,3,"25 ﷼"))))
        assertNull(KeetaMenuPreview.price(product.copy(nodes=product.nodes.filterNot { it.index==5 })))
        assertNull(KeetaMenuPreview.price(product.copy(nodes=product.nodes.filterNot { it.index==0 })))
        assertNull(KeetaMenuPreview.price(product.copy(nodes=product.nodes+n(11,10,"اختر الحجم — إلزامي"))))
        assertNull(KeetaMenuPreview.price(product.copy(nodes=product.nodes.map { if(it.index==1) it.copy(parent=-1) else it })))
    }
    @Test fun functionalCaptureRejectsPrivateAndUnrelatedText() {
        assertEquals("",KeetaMenuPreview.functionalLabel("شارع العنوان الخاص"))
        assertEquals("",KeetaMenuPreview.functionalLabel("سجل الدخول برمز التحقق"))
        assertEquals("",KeetaMenuPreview.functionalLabel("ملاحظة شخصية للطلب"))
        assertEquals("توصيل مجاني",KeetaMenuPreview.functionalLabel("توصيل مجاني"))
        assertFalse(PreviewEvidence.supports(cart.copy(items=cart.items.map { it.copy(note="خاص") })))
        assertTrue(PreviewEvidence.supports(cart.copy(restaurant="مطعم آخر")))
    }
    @Test fun menuOpeningCanOnlyTargetMatchingProductCardAndNeverItsAddButton() {
        val menu=CatalogFrame(listOf(n(0,label="ريدان"),n(1,label="توصيل"),n(2,label="الاصناف الرئيسية"),
            n(3,click=true),n(4,3,"نصف دجاج مضغوط"),n(5,3,"30 ﷼"),n(6,3,click=true)),true)
        assertEquals(3,KeetaMenuPreview.productTarget(menu)?.index)
        assertNull(KeetaMenuPreview.productTarget(product))
        assertNull(KeetaMenuPreview.productTarget(menu.copy(nodes=menu.nodes+n(7,3,"نصف دجاج مضغوط"))))
    }
    @Test fun deliveryQuoteIsSeparatedFromConditionalPromotionsAndTimeEstimates() {
        val menu = CatalogFrame(listOf(n(0).copy(id="menuBackButton"), n(1).copy(id="menuSearchButton"),
            n(2,label="ريدان\nالحد الأدنى للطلب\n§\n15\nرسوم التوصيل\n§\n24"),
            n(3,label="تطبيق تلقائي\n§\n10\nرسوم التوصيل\nالحد الأدنى: 35"),
            n(4,label="مدة التوصيل\n20 - 35")),true)
        assertEquals(2400L,PreviewEvidence.hungerComponent(menu,"رسوم التوصيل"))
        assertEquals(1500L,PreviewEvidence.hungerComponent(menu,"الحد الأدنى للطلب"))
        assertEquals(listOf("تطبيق تلقائي § 10 رسوم التوصيل الحد الأدنى: 35"),PreviewEvidence.offers(menu))
        assertNull(PreviewEvidence.hungerComponent(menu.copy(complete=false),"رسوم التوصيل"))
    }
    @Test fun selectedIdentityCannotBorrowAnotherMealPriceOrRetainUnrelatedText() {
        val selected=KeetaMenuReader("مدور","فطيرة شكشوكة بالجبن")
        val renamed=product.copy(nodes=product.nodes.map {
            when(it.label) { "ريدان" -> it.copy(label=selected.restaurant); "نصف دجاج مضغوط" -> it.copy(label=selected.title); else -> it }
        })
        assertEquals(3000L,selected.price(renamed))
        assertNull(selected.price(product))
        assertNull(KeetaMenuPreview.price(renamed))
        assertEquals(selected.restaurant,selected.functionalLabel(selected.restaurant))
        assertEquals(selected.title,selected.functionalLabel(selected.title))
        assertEquals("",selected.functionalLabel("شارع العنوان الخاص"))
        assertEquals("",selected.functionalLabel("اسم وجبة مختلفة"))
    }
}
