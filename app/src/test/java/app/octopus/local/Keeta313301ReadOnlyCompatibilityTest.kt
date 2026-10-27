package app.octopus.local

import app.octopus.local.providers.*
import app.octopus.local.domain.*
import app.octopus.local.safety.ReviewNavigationGuard
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

/** Actual 3.13.301 captures, 2026-10-27. Evaluate only the prior reader's structural
 * compatibility; runtime admission is tested separately by KeetaVersionProfileTest.
 * The probe omitted unrequested product names; never reconstruct them from screenshots.
 */
class Keeta313301ReadOnlyCompatibilityTest {
    private val priorReader = KeetaMenuReader("كودو", "بطاطس وسط", "3.13.203")
    private fun recorded(phase: String): CatalogFrame = Gson().fromJson(
        javaClass.getResource("/recorded-keeta/keeta-3.13.301-$phase-redacted.json")!!.readText(),
        CatalogFrame::class.java,
    )

    @Test fun actualHomeRetainsThePriorSearchAnchorOnly() {
        val frame = recorded("home")
        assertEquals(14, priorReader.homeSearch(frame)?.index)
        assertFalse(priorReader.menu(frame))
        assertNull(priorReader.searchCandidate(frame))
        assertNull(priorReader.homeSearch(frame.copy(complete = false)))
    }

    @Test fun actualResultsRetainScopedFirstCandidateAndQueryEdit() {
        val frame = recorded("results")
        assertEquals(41, priorReader.searchCandidate(frame)?.index)
        assertEquals(10, priorReader.editSearchQuery(frame)?.index)
        assertFalse(priorReader.menu(frame))
        assertNull(priorReader.searchCandidate(frame.copy(complete = false)))
    }

    @Test fun actualMenuIsRecognizedButDoesNotProveAProductOrEmptyBasket() {
        val frame = recorded("menu")
        assertTrue(priorReader.menu(frame))
        assertFalse(priorReader.product(frame))
        assertNull(priorReader.productTarget(frame))
        // No visible named requested card exists in this capture. Missing footer is not
        // sufficient evidence to begin a new basket, even though the menu is recognized.
        val basket = KeetaBasketReader(priorReader)
        assertFalse(basket.emptyMenu(frame))
        assertNull(basket.addTarget(frame, 900))
        assertNull(basket.evidence(frame))
        assertFalse(priorReader.menu(frame.copy(complete = false)))
    }

    @Test fun actualPlainFriesSheetMatchesPriorProductPriceAndControlStructure() {
        val frame = recorded("pdp-fries")
        assertTrue(priorReader.product(frame))
        assertFalse(priorReader.menu(frame))
        assertEquals(183, priorReader.productBody(frame)?.index)
        assertEquals(900L, priorReader.price(frame))
        assertEquals(900L, priorReader.unitPrice(frame))
        assertEquals(219, priorReader.closeProduct(frame)?.index)
        // Finding a compatible structural target in a recorded frame does not dispatch it
        // or grant runtime support for the observed provider version.
        assertEquals(232, KeetaBasketReader(priorReader).addTarget(frame, 900)?.index)
    }

    @Test fun actualFriesSheetDoesNotAuthorizeDifferentPriceOrIncompleteRead() {
        val frame = recorded("pdp-fries")
        val basket = KeetaBasketReader(priorReader)
        assertNull(basket.addTarget(frame, 800))
        assertNull(basket.addTarget(frame.copy(complete = false), 900))
        assertNull(basket.evidence(frame))
        assertNull(basket.quantityTarget(frame, true))
    }

    @Test fun omittedTextOutsideObservedDescriptionSlotCannotAuthorizeAdd() {
        val frame = recorded("pdp-fries")
        // Mutate an existing recorded nutrition label, without fabricating a control or
        // treating any unknown content as an approved option.
        val unknown = frame.copy(nodes = frame.nodes.map {
            if (it.index == 200) it.copy(label = "", omittedText = true) else it
        })
        assertNull(priorReader.productBody(unknown))
        assertNull(KeetaBasketReader(priorReader).addTarget(unknown, 900))
    }

    @Test fun actualPublicProductBodyCaptureStillMatchesThePriorPlainReader() {
        val frame = recorded("product-fries")
        assertTrue(priorReader.product(frame))
        assertEquals(183, priorReader.productBody(frame)?.index)
        assertEquals(900L, priorReader.unitPrice(frame))
        assertEquals(232, KeetaBasketReader(priorReader).addTarget(frame, 900)?.index)
    }

    @Test fun actualSingleRowCartsExposeExactAmountsAndOwnedQuantityControls() {
        val basket = KeetaBasketReader(priorReader)
        for ((phase, quantity) in listOf("drawer-one" to 1, "drawer-three" to 3)) {
            val frame = recorded(phase)
            val evidence = checkNotNull(basket.evidence(frame))
            assertTrue(evidence.complete)
            assertEquals(listOf(BasketLineEvidence("بطاطس وسط", emptyList(), quantity, 900L * quantity)), evidence.lines)
            assertEquals(900L * quantity, evidence.subtotal)
            assertEquals(249, basket.quantityTarget(frame, true)?.index)
            if (quantity == 1) assertNull(basket.quantityTarget(frame, false))
            else assertEquals(245, basket.quantityTarget(frame, false)?.index)
            assertNull(basket.evidence(frame.copy(complete = false)))
        }
    }

    @Test fun actualDrawerDoesNotMatchOldCloseButtonStructure() {
        val basket = KeetaBasketReader(priorReader)
        // The first modal child is a promotion block, not the old image/close wrapper.
        // Keep the mismatch visible rather than guessing a close button or coordinate.
        assertNull(basket.closeDrawer(recorded("drawer-one")))
        assertNull(basket.closeDrawer(recorded("drawer-three")))
    }

    @Test fun actualMenuCountsAndReviewControlsMatchPriorStructureButNotUnknownVersion() {
        val basket = KeetaBasketReader(priorReader)
        assertEquals(1, basket.menuQuantity(recorded("cart-menu")))
        assertEquals(3, basket.menuQuantity(recorded("cart-menu-three")))
        val request = CanonicalCart("كودو", null, listOf(CanonicalItem("بطاطس وسط", null, emptyList(), 3)))
        assertEquals(228, ReviewNavigationGuard.target(ProviderId.KEETA, recorded("cart-menu-three"),
            request, "3.13.203")?.index)
        assertEquals(11, ReviewNavigationGuard.keetaReviewBackTarget(recorded("review-frame"), "3.13.203")?.index)
        assertNull(ReviewNavigationGuard.target(ProviderId.KEETA, recorded("cart-menu-three"), request, "3.13.302"))
        assertNull(ReviewNavigationGuard.keetaReviewBackTarget(recorded("review-frame"), "3.13.302"))
        assertNull(ReviewNavigationGuard.keetaReviewBackTarget(recorded("review-frame").copy(complete = false), "3.13.203"))
    }
}
