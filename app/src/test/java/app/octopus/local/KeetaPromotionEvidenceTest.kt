package app.octopus.local

import app.octopus.local.providers.*
import app.octopus.local.pricing.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class KeetaPromotionEvidenceTest {
    private val now=Instant.parse("2026-10-19T19:00:00Z").toEpochMilli()
    private fun n(i:Int,parent:Int=-1,label:String="",click:Boolean=false)=CatalogNode(i,parent,"",label,"android.view.ViewGroup",click,false,false)
    private val terms=CatalogFrame(listOf(n(0,label="ريدان"),n(1,label="قسائمي"),n(2,label="خصم 10%"),
        n(3,label="الحد الأدنى للطلب ‏60 ﷼‏؛ وفر حتى ‏8 ﷼‏"),n(4),n(5,4,"شروط الاستخدام"),
        n(6,4,"طلبات التوصيل فقط"),n(7,4,"تنتهي اليوم"),n(8,4,"حسنًا")),true)
    @Test fun ownedTermsSupplyRealCapAndExpireAtSaudiMidnight() {
        val rule=checkNotNull(KeetaPromotionEvidence.ownedRule(terms,now))
        assertTrue(rule.termsComplete)
        assertEquals(800L,rule.maximumDiscount)
        assertEquals(6000L,rule.minimum)
        assertEquals(1000,rule.percentBasisPoints)
        assertEquals(Instant.parse("2026-10-19T21:00:00Z").toEpochMilli(),rule.validUntilMillis)
        assertEquals(600L,OfferCalculator.evaluate(6000,0,listOf(rule),now).itemDiscount)
        assertEquals(800L,OfferCalculator.evaluate(9000,0,listOf(rule),now).itemDiscount)
        assertEquals(0L,OfferCalculator.evaluate(9000,0,listOf(rule),rule.validUntilMillis!!).itemDiscount)
    }
    @Test fun omittedUnknownTermsAndMembershipConditionsBlockCalculation() {
        val missing=terms.copy(nodes=terms.nodes+n(9,4).copy(omittedText=true))
        assertFalse(checkNotNull(KeetaPromotionEvidence.ownedRule(missing,now)).termsComplete)
        val membership=terms.copy(nodes=terms.nodes+n(9,4,"يتطلب اشتراك"))
        assertFalse(checkNotNull(KeetaPromotionEvidence.ownedRule(membership,now)).termsComplete)
        assertNull(KeetaPromotionEvidence.ownedRule(terms.copy(complete=false),now))
    }
    private fun menu()=CatalogFrame(listOf(n(0,label="ريدان"),n(1,label="توصيل"),n(2,label="الاصناف الرئيسية"),
        n(3).copy(className="androidx.recyclerview.widget.RecyclerView",scrollable=true),n(4,3,click=true),
        n(5,4,"نصف دجاج مضغوط"),n(6,4,"30 ﷼"),n(7,4,"2",true),n(8),n(9,8,"إتمام الدفع"),
        n(10,8,"54.00 ﷼"),n(11,8,"60.00 ﷼"),n(12,8,"وفَّرت 6.00 ﷼"),n(13,8,"توصيل مجاني"),n(14,8,"2")),true)
    @Test fun simultaneousDiscountAndFreeDeliveryNeedMatchingCartEvidence() {
        val rule=checkNotNull(KeetaPromotionEvidence.ownedRule(terms,now))
        assertTrue(KeetaPromotionEvidence.simultaneous(menu(),rule))
        assertFalse(KeetaPromotionEvidence.simultaneous(menu().copy(nodes=menu().nodes.filterNot{it.index==13}),rule))
        assertFalse(KeetaPromotionEvidence.simultaneous(menu().copy(nodes=menu().nodes.map{if(it.index==14)it.copy(label="3")else it}),rule))
        assertFalse(KeetaPromotionEvidence.simultaneous(menu(),rule.copy(maximumDiscount=500)))
    }
    @Test fun unitPriceUsesProductQuantityAndRejectsMissingOrAmbiguousQuantity() {
        val product=CatalogFrame(listOf(n(0,label="ريدان"),n(1).copy(className="android.widget.ScrollView"),
            n(2,1,"نصف دجاج مضغوط"),n(3,1,"طلب خاص"),n(4),n(5,4),n(6,5,"إضافة إلى السلة"),
            n(7,5,"60.00 ﷼"),n(8,4,"2")),true)
        assertEquals(3000L,KeetaMenuPreview.unitPrice(product))
        assertNull(KeetaMenuPreview.unitPrice(product.copy(nodes=product.nodes.filterNot{it.index==8})))
        assertNull(KeetaMenuPreview.unitPrice(product.copy(nodes=product.nodes+n(9,4,"3"))))
    }
}
