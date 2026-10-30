package app.octopus.local

import app.octopus.local.providers.CatalogFrame
import app.octopus.local.safety.ReviewNavigationGuard
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class HungerRecommendationsVariantTest {
    private val version="8.0.303"
    private val old=Gson().fromJson(javaClass.getResource(
        "/recorded-hs/hs-8.0.303-recommendations-old-redacted.json")!!.readText(),CatalogFrame::class.java)
    private val newHeading="لا تفوت الفرصة!\nفرصتك لإضافة المزيد لطلبك"
    // Only the old variant has an actual structure capture. This changed-heading fixture is synthetic.
    private val syntheticNew=old.copy(nodes=old.nodes.map {if(it.index==8)it.copy(label=newHeading)else it})

    @Test fun actualOldCaptureSelectsOnlyItsReviewNavigationButton() {
        val target=checkNotNull(ReviewNavigationGuard.hungerRecommendationsTarget(old,version))
        assertEquals(73,target.index)
        assertEquals("اذهب للدفع",target.label)
        assertEquals("android.widget.Button",target.className)
    }

    @Test fun syntheticExactVisuallyObservedSecondHeadingRetainsTheSameGuardedTarget() {
        assertEquals(73,ReviewNavigationGuard.hungerRecommendationsTarget(syntheticNew,version)?.index)
    }

    @Test fun wrongClassDisabledCheckoutDuplicateOrUnknownHeadingStayBlocked() {
        val footer=syntheticNew.nodes.single {it.index==73}
        val cases=listOf(
            syntheticNew.copy(nodes=syntheticNew.nodes.map {if(it.index==8)it.copy(className="android.view.View")else it}),
            syntheticNew.copy(nodes=syntheticNew.nodes.map {if(it.index==73)it.copy(enabled=false)else it}),
            syntheticNew.copy(nodes=syntheticNew.nodes.map {if(it.index==73)it.copy(className="android.widget.ImageView")else it}),
            syntheticNew.copy(nodes=syntheticNew.nodes+footer.copy(index=1000,id="FlutterCheckout_confirm")),
            syntheticNew.copy(nodes=syntheticNew.nodes+footer.copy(index=1000)),
            syntheticNew.copy(nodes=syntheticNew.nodes.map {if(it.index==8)it.copy(label="$newHeading!")else it}),
            syntheticNew.copy(nodes=syntheticNew.nodes.filterNot {it.id=="menuItemActionSingleButtonAdd"}),
            syntheticNew.copy(nodes=syntheticNew.nodes+footer.copy(index=1000,label="تأكيد الطلب")),
            syntheticNew.copy(complete=false),
        )
        for(frame in cases)assertNull(ReviewNavigationGuard.hungerRecommendationsTarget(frame,version))
        assertNull(ReviewNavigationGuard.hungerRecommendationsTarget(syntheticNew,"8.0.304"))
    }
}
