package app.octopus.local

import app.octopus.local.domain.*
import app.octopus.local.providers.*
import app.octopus.local.safety.PreparedReviewAdmission
import app.octopus.local.safety.ReviewNavigationGuard
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

/** Actual 3.13.301 drawer whose footer opened review without leaving the restaurant. */
class KeetaDirectDrawerReviewTest {
    private val provider = ProviderId.KEETA
    private val version = "3.13.301"
    private val request = CanonicalCart("كودو", null, listOf(CanonicalItem("بطاطس وسط", null, emptyList(), 3)))
    private val source = Gson().fromJson(javaClass.getResource(
        "/recorded-keeta/keeta-3.13.301-drawer-before-direct-review-redacted.json")!!.readText(), CatalogFrame::class.java)
    private val evidence = checkNotNull(KeetaBasketReader(KeetaMenuReader("كودو", "بطاطس وسط", version)).evidence(source))
    private val record = checkNotNull(PreparedBasketRecord.create(request, evidence, version, 1000))
    private fun admission() = checkNotNull(PreparedReviewAdmission.create(provider, request, record, source, version, 1100))

    @Test fun exactFreshRecordAndDrawerAuthorizeOneKnownFooterNavigation() {
        val permit = admission()
        val target = checkNotNull(ReviewNavigationGuard.target(provider, source, request, version, permit, 1200))
        assertEquals(285, target.index)
        assertTrue(permit.consume(source, target, provider, request, version, 1200))
        assertFalse(permit.consume(source, target, provider, request, version, 1201))
        assertNull(ReviewNavigationGuard.target(provider, source, request, version, permit, 1201))
    }

    @Test fun missingExpiredOrWrongVersionAdmissionCannotOpenReview() {
        assertNull(ReviewNavigationGuard.target(provider, source, request, version, now = 1200))
        assertNull(ReviewNavigationGuard.target(provider, source, request, version, admission(), 16_001))
        assertNull(ReviewNavigationGuard.target(provider, source, request, "3.13.302", admission(), 1200))
        assertNull(PreparedReviewAdmission.create(provider, request, record, source, version, 16_001))
        assertNull(PreparedReviewAdmission.create(provider, request, record, source, "3.13.203", 1200))
    }

    @Test fun alteredRowQuantityAmountOrFooterCannotReuseAValidRecord() {
        val changes = listOf(237 to "صنف مختلف", 255 to "4", 248 to "36.00 ﷼")
        val frames = changes.map { (index, label) -> source.copy(nodes = source.nodes.map {
            if (it.index == index) it.copy(label = label) else it
        }) } + source.copy(complete = false) + source.copy(nodes = source.nodes.map {
            if (it.index == 285) it.copy(enabled = false) else it
        })
        for (changed in frames) {
            assertNull(ReviewNavigationGuard.target(provider, changed, request, version, admission(), 1200))
        }
        for (changed in frames.take(4)) {
            assertNull(PreparedReviewAdmission.create(provider, request, record, changed, version, 1200))
        }
    }

    @Test fun changedFullRequestAndUnverifiedMultiItemRequestRemainBlocked() {
        val changed = request.copy(items = listOf(request.items.single().copy(quantity = 4)))
        assertNull(ReviewNavigationGuard.target(provider, source, changed, version, admission(), 1200))
        assertNull(PreparedReviewAdmission.create(provider, changed, record, source, version, 1200))
        val multi = request.copy(items = request.items + CanonicalItem("جبنة الناتشوز", null, emptyList(), 1))
        assertNull(PreparedReviewAdmission.create(provider, multi, record, source, version, 1200))
        assertNull(ReviewNavigationGuard.target(provider, source, multi, version, admission(), 1200))
    }
}
