package app.octopus.local

import app.octopus.local.pricing.*
import app.octopus.local.providers.*
import app.octopus.local.domain.ProviderId
import org.junit.Assert.*
import org.junit.Test

class OfferCalculatorTest {
    private val shipping = OfferRule("ship","توصيل 10 عند 35",OfferTarget.DELIVERY,3500,deliveryPrice=1000)
    @Test fun lowerEqualAndHigherThresholdsUseRequestedSubtotal() {
        val below=OfferCalculator.evaluate(3000,2400,listOf(shipping))
        assertEquals(2400L,below.delivery)
        assertEquals(500L,below.decisions.single().remaining)
        assertEquals(OfferStatus.BELOW_MINIMUM,below.decisions.single().status)
        assertEquals(1000L,OfferCalculator.evaluate(3500,2400,listOf(shipping)).delivery)
        assertEquals(7000L,OfferCalculator.evaluate(6000,2400,listOf(shipping)).knownPartTotal)
    }
    @Test fun freeDeliveryAtMinimumAndExclusiveBoundaryAreDifferent() {
        val free=OfferRule("free","مجاني من 30",OfferTarget.DELIVERY,3000,deliveryPrice=0)
        assertEquals(3000L,OfferCalculator.evaluate(3000,2200,listOf(free)).knownPartTotal)
        val exclusive=OfferCalculator.evaluate(3000,2200,listOf(free.copy(inclusiveMinimum=false)))
        assertEquals(2200L,exclusive.delivery)
        assertEquals(1L,exclusive.decisions.single().remaining)
        assertEquals(0L,OfferCalculator.evaluate(3001,2200,listOf(free.copy(inclusiveMinimum=false))).delivery)
    }
    @Test fun percentageCapAndCouponMustAllBeKnown() {
        val rule=OfferRule("pct","خصم 10% من 60 بحد أقصى 20",OfferTarget.ITEMS,6000,
            percentBasisPoints=1000,maximumDiscount=2000,requiresCoupon=true)
        assertEquals(0L,OfferCalculator.evaluate(6000,0,listOf(rule)).itemDiscount)
        assertEquals(OfferStatus.UNVERIFIED,OfferCalculator.evaluate(6000,0,listOf(rule)).decisions.single().status)
        assertEquals(600L,OfferCalculator.evaluate(6000,0,listOf(rule.copy(couponVerified=true))).itemDiscount)
        assertEquals(2000L,OfferCalculator.evaluate(30000,0,listOf(rule.copy(couponVerified=true))).itemDiscount)
        assertEquals(OfferStatus.INELIGIBLE,OfferCalculator.evaluate(6000,0,listOf(rule.copy(couponVerified=false))).decisions.single().status)
        assertEquals(0L,OfferCalculator.evaluate(6000,0,listOf(rule.copy(couponVerified=true,maximumDiscount=null))).itemDiscount)
    }
    @Test fun membershipUnknownAndUnavailableNeverProduceSavings() {
        val rule=shipping.copy(requiresMembership=true)
        assertEquals(2400L,OfferCalculator.evaluate(6000,2400,listOf(rule)).delivery)
        assertEquals(2400L,OfferCalculator.evaluate(6000,2400,listOf(rule.copy(membershipVerified=false))).delivery)
        assertEquals(1000L,OfferCalculator.evaluate(6000,2400,listOf(rule.copy(membershipVerified=true))).delivery)
    }
    @Test fun offersDoNotStackUnlessCombinationIsExplicitlyKnown() {
        val discount=OfferRule("pct","30%",OfferTarget.ITEMS,0,percentBasisPoints=3000,maximumDiscount=10000)
        val free=shipping.copy(id="free",minimum=0,deliveryPrice=0)
        val separate=OfferCalculator.evaluate(6000,1000,listOf(discount,free))
        assertEquals(5200L,separate.knownPartTotal)
        assertEquals(listOf("pct"),separate.selected)
        val combined=OfferCalculator.evaluate(6000,1000,listOf(discount.copy(stackGroup="verified"),free.copy(stackGroup="verified")))
        assertEquals(4200L,combined.knownPartTotal)
    }
    @Test fun pricesNeverBecomeNegativeAndOffersNeverRaiseDelivery() {
        val tooLarge=OfferRule("fixed","خصم",OfferTarget.ITEMS,0,fixedDiscount=9000)
        assertEquals(0L,OfferCalculator.evaluate(3000,0,listOf(tooLarge)).knownPartTotal)
        assertEquals(500L,OfferCalculator.evaluate(6000,500,listOf(shipping)).delivery)
        assertNull(OfferCalculator.evaluate(3000,null,emptyList()).knownPartTotal)
        assertEquals(3000L,OfferCalculator.evaluate(3000,null,listOf(shipping.copy(minimum=0,deliveryPrice=0))).knownPartTotal)
    }
    private fun n(i:Int,parent:Int=-1,label:String="",id:String="")=CatalogNode(i,parent,id,label,"android.view.ViewGroup",false,false,false)
    @Test fun observedAutomaticDeliveryFormatIsParsedButPickupAndUnknownRulesAreNot() {
        val frame=CatalogFrame(listOf(n(0,id="menuBackButton"),n(1,id="menuSearchButton"),
            n(2,label="تطبيق تلقائي\n§\n10\nرسوم التوصيل\nالحد الأدنى: 35"),
            n(3,label="استلام 30% خصم")),true)
        val rules=MenuOfferRules.fromFrame(ProviderId.HUNGERSTATION,frame)
        assertEquals(1,rules.size)
        assertEquals(1000L,OfferCalculator.evaluate(6000,2400,rules).delivery)
        assertEquals(2400L,OfferCalculator.evaluate(3000,2400,rules).delivery)
    }
    @Test fun freeThresholdIsBoundToItsOwnOfferAndUnconfirmedCouponIsPending() {
        val frame=CatalogFrame(listOf(n(0),n(1,0,"توصيل مجاني"),n(2,0,"الحد الأدنى ‏30 ﷼‏"),
            n(3,label="خصم 10% من ‏60 ﷼‏")),true)
        val rules=MenuOfferRules.fromFrame(ProviderId.KEETA,frame)
        assertEquals(2,rules.size)
        val result=OfferCalculator.evaluate(6000,2200,rules)
        assertEquals(0L,result.delivery)
        assertEquals(0L,result.itemDiscount)
        assertEquals(6000L,result.knownPartTotal)
        assertEquals(OfferStatus.UNVERIFIED,result.decisions.single { it.rule.target==OfferTarget.ITEMS }.status)
    }
}
