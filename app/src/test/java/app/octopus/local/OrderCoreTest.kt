package app.octopus.local

import app.octopus.local.automation.*
import app.octopus.local.domain.*
import app.octopus.local.matching.*
import app.octopus.local.pricing.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

private fun meal(quantity: Int = 1) = CanonicalItem("دجاج مضبي", "نصف دجاج", listOf(CartOption("الأرز", "بشاور")), quantity)
private fun order(items: List<CanonicalItem> = listOf(meal())) = CanonicalCart("المضياف العربي", "فرع الاختبار", items, "address-local-reference")
private fun observed(cart: CanonicalCart = order()) = ObservedCart(cart.restaurant, cart.branch, cart.addressReference, cart.items, true)
// Explicit test-only authorization. The default production engine denies all mutations.
private fun fixturePreparation() = CartPreparationEngine(authorizeEdit = { _, _ -> true })

class CartIdentityTest {
    @Test fun itemOrderAndDuplicateRowsDoNotChangeFingerprint() {
        val extra = meal().copy(name = "ماء")
        assertEquals(order(listOf(meal(), extra)).fingerprint, order(listOf(extra, meal())).fingerprint)
        assertEquals(order(listOf(meal(2))).fingerprint, order(listOf(meal(), meal())).fingerprint)
    }
    @Test fun sizeAdditionsNotesQuantityBranchAndAddressAffectFingerprint() {
        val base = order()
        listOf(base.copy(items = listOf(meal().copy(size = "دجاج كامل"))),
            base.copy(items = listOf(meal().copy(options = emptyList()))),
            base.copy(items = listOf(meal().copy(note = "بدون فلفل"))),
            base.copy(items = listOf(meal(2))), base.copy(branch = "فرع آخر"), base.copy(addressReference = "other-address"))
            .forEach { assertNotEquals(base.fingerprint, it.fingerprint) }
    }
    @Test fun missingSizeCannotCollideWithLiteralPlaceholder() {
        assertNotEquals(CartIdentity.itemKey(meal().copy(size = null)), CartIdentity.itemKey(meal().copy(size = "<unknown-size>")))
    }
    @Test fun nonSemanticWhitespaceIsNormalizedButArabicSpellingIsNotGuessed() {
        assertEquals(CartIdentity.itemKey(meal()), CartIdentity.itemKey(meal().copy(name = "  دجاج   مضبي  ")))
        assertNotEquals(CartIdentity.itemKey(meal()), CartIdentity.itemKey(meal().copy(name = "دجاج مظبي")))
    }
    @Test fun invalidQuantitiesAndDuplicateOptionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { meal(0) }
        assertThrows(IllegalArgumentException::class.java) { meal(100) }
        assertThrows(IllegalArgumentException::class.java) { meal().copy(options = listOf(CartOption("أرز", "أبيض"), CartOption("أرز", "أبيض"))) }
        assertThrows(IllegalArgumentException::class.java) { order(listOf(meal(99), meal())) }
    }
}

class ItemMatcherTest {
    @Test fun exactMultiItemCartMatches() {
        val desired = order(listOf(meal(2), meal().copy(name = "ماء", options = emptyList())))
        assertTrue(ItemMatcher().compare(desired, observed(desired)).exact)
    }
    @Test fun unknownBranchOrAddressNeverCountsAsExact() {
        assertTrue(MatchFailure.BRANCH_UNKNOWN in ItemMatcher().compare(order(), observed().copy(branch = null)).failures)
        assertTrue(MatchFailure.ADDRESS_UNKNOWN in ItemMatcher().compare(order(), observed().copy(addressReference = null)).failures)
    }
    @Test fun wrongSizeAdditionQuantityAndIncompleteReadDoNotMatch() {
        listOf(observed().copy(items = listOf(meal().copy(size = "كامل"))),
            observed().copy(items = listOf(meal().copy(options = emptyList()))),
            observed().copy(items = listOf(meal(2))), observed().copy(complete = false))
            .forEach { assertFalse(ItemMatcher().compare(order(), it).exact) }
    }
}

class CartReconcilerTest {
    @Test fun matchedCartProducesNoEdit() {
        assertEquals(ReconcileDecision.Matched, CartReconciler().next(order(), observed()))
    }
    @Test fun emptyBasketGetsAbsoluteQuantityNotBlindRepeatedAdd() {
        val actual = observed().copy(items = emptyList())
        val decision = CartReconciler().next(order(listOf(meal(3))), actual) as ReconcileDecision.SetQuantity
        assertEquals(3, decision.targetQuantity)
        assertEquals(actual.fingerprint, decision.expectedFingerprint)
    }
    @Test fun previousBasketNeedsExplicitSnapshotBoundApproval() {
        val actual = observed().copy(items = listOf(meal(2)))
        val reconciler = CartReconciler()
        assertTrue(reconciler.next(order(), actual) is ReconcileDecision.NeedsApproval)
        assertTrue(reconciler.next(order(), actual, CartReplacementApproval("stale", order().fingerprint)) is ReconcileDecision.NeedsApproval)
        assertTrue(reconciler.next(order(), actual, CartReplacementApproval(actual.fingerprint, "other-order")) is ReconcileDecision.NeedsApproval)
        assertTrue(reconciler.next(order(), actual, CartReplacementApproval(actual.fingerprint, order().fingerprint)) is ReconcileDecision.SetQuantity)
    }
    @Test fun unknownRestaurantCannotBecomeMatchedOrEditableAfterApproval() {
        val desired = order()
        for (name in listOf(null, "", "   ")) for (items in listOf(emptyList(), listOf(meal()), listOf(meal(2)))) {
            val actual = observed().copy(restaurant = name, items = items)
            val approval = CartReplacementApproval(actual.fingerprint, desired.fingerprint)
            for (permission in listOf(null, approval)) {
                assertEquals(ReconcileDecision.Blocked(ReconcileBlock.RESTAURANT_UNKNOWN),
                    CartReconciler().next(desired, actual, permission))
            }
        }
    }
    @Test fun unsupportedRestaurantReplacementIsBlockedBeforeAskingForApproval() {
        val actual = observed().copy(restaurant = "Different restaurant")
        val approval = CartReplacementApproval(actual.fingerprint, order().fingerprint)
        for (permission in listOf(null, approval)) {
            assertEquals(ReconcileDecision.Blocked(ReconcileBlock.WRONG_RESTAURANT),
                CartReconciler().next(order(), actual, permission))
        }
    }
    @Test fun missingOrWrongBranchIsBlockedBeforeAskingForApproval() {
        val cases = listOf(null to ReconcileBlock.BRANCH_UNKNOWN, "" to ReconcileBlock.BRANCH_UNKNOWN,
            "   " to ReconcileBlock.BRANCH_UNKNOWN, "Different branch" to ReconcileBlock.WRONG_BRANCH)
        for ((branch, reason) in cases) for (items in listOf(emptyList(), listOf(meal(2)))) {
            val actual = observed().copy(branch = branch, items = items)
            val approval = CartReplacementApproval(actual.fingerprint, order().fingerprint)
            for (permission in listOf(null, approval)) {
                assertEquals(ReconcileDecision.Blocked(reason), CartReconciler().next(order(), actual, permission))
            }
        }
    }
    @Test fun partialCartAndWrongAddressAreNeverEditedEvenWithApproval() {
        val actual = observed().copy(complete = false)
        assertEquals(ReconcileDecision.Blocked(ReconcileBlock.INCOMPLETE_CART), CartReconciler().next(order(), actual))
        assertEquals(ReconcileDecision.Blocked(ReconcileBlock.WRONG_ADDRESS), CartReconciler().next(order(), observed().copy(addressReference = "other")))
    }
}

private class MemoryCartPort(var cart: ObservedCart) : CartPreparationPort {
    var edits = 0
    var failEdit = false
    var externalChangeAfterEdit = false
    var onObserve: (() -> Unit)? = null
    override suspend fun observeCart(): ObservedCart { onObserve?.invoke(); return cart }
    override suspend fun applyAbsoluteQuantity(edit: ReconcileDecision.SetQuantity, stop: StopSignal): Boolean {
        if (stop.stopped || failEdit || cart.fingerprint != edit.expectedFingerprint) return false
        edits++
        val key = CartIdentity.itemKey(edit.item)
        cart = cart.copy(items = cart.items.filter { CartIdentity.itemKey(it) != key } +
            if (edit.targetQuantity == 0) emptyList() else listOf(edit.item.copy(quantity = edit.targetQuantity)))
        if (externalChangeAfterEdit) cart = cart.copy(items = cart.items + meal().copy(name = "صنف خارجي"))
        return true
    }
}

class CartPreparationTest {
    @Test fun buildsMultipleItemsAndRetryDoesNotDuplicate() = runBlocking {
        val desired = order(listOf(meal(2), meal().copy(name = "ماء", options = emptyList())))
        val port = MemoryCartPort(observed().copy(items = emptyList()))
        assertTrue(fixturePreparation().prepare(desired, port, StopSignal()) is PreparationResult.Ready)
        assertEquals(2, port.edits)
        assertTrue(fixturePreparation().prepare(desired, port, StopSignal()) is PreparationResult.Ready)
        assertEquals(2, port.edits)
    }
    @Test fun foreignBasketIsNotChangedWithoutApproval() = runBlocking {
        val port = MemoryCartPort(observed().copy(items = listOf(meal(3))))
        assertEquals(PreparationResult.Failed(PreparationFailure.NEEDS_APPROVAL), CartPreparationEngine().prepare(order(), port, StopSignal()))
        assertEquals(0, port.edits)
    }
    @Test fun approvedReplacementDoesNotRequireRepeatedApprovalForOwnedChanges() = runBlocking {
        val port = MemoryCartPort(observed().copy(items = listOf(meal().copy(name = "طلب سابق"))))
        val approval = CartReplacementApproval(port.cart.fingerprint, order().fingerprint)
        assertTrue(fixturePreparation().prepare(order(), port, StopSignal(), approval) is PreparationResult.Ready)
        assertEquals(2, port.edits)
    }
    @Test fun unobservedRestaurantNeverBecomesReadyOrReceivesAnEdit() = runBlocking {
        for (items in listOf(emptyList(), listOf(meal()), listOf(meal(2)))) {
            val port = MemoryCartPort(observed().copy(restaurant = null, items = items))
            val approval = CartReplacementApproval(port.cart.fingerprint, order().fingerprint)
            assertEquals(PreparationResult.Failed(PreparationFailure.UNSAFE_CONTEXT),
                fixturePreparation().prepare(order(), port, StopSignal(), approval))
            assertEquals(0, port.edits)
        }
    }
    @Test fun impossibleBranchReplacementDoesNotAskForConsentOrMutateTheCart() = runBlocking {
        for (branch in listOf(null, "Different branch")) {
            val port = MemoryCartPort(observed().copy(branch = branch, items = listOf(meal(2))))
            assertEquals(PreparationResult.Failed(PreparationFailure.UNSAFE_CONTEXT),
                fixturePreparation().prepare(order(), port, StopSignal()))
            assertEquals(0, port.edits)
        }
    }
    @Test fun stopDuringObservationPreventsAnyEdit() = runBlocking {
        val stop = StopSignal()
        val port = MemoryCartPort(observed().copy(items = emptyList())).apply { onObserve = { stop.stop() } }
        assertEquals(PreparationResult.Failed(PreparationFailure.STOPPED), CartPreparationEngine().prepare(order(), port, stop))
        assertEquals(0, port.edits)
    }
    @Test fun unexpectedExternalChangeStopsBeforeNextEdit() = runBlocking {
        val port = MemoryCartPort(observed().copy(items = emptyList())).apply { externalChangeAfterEdit = true }
        val desired = order(listOf(meal(), meal().copy(name = "ماء")))
        assertEquals(PreparationResult.Failed(PreparationFailure.UNEXPECTED_CHANGE), fixturePreparation().prepare(desired, port, StopSignal()))
        assertEquals(1, port.edits)
    }
    @Test fun failedEditNeverTriggersBlindRetry() = runBlocking {
        val port = MemoryCartPort(observed().copy(items = emptyList())).apply { failEdit = true }
        assertEquals(PreparationResult.Failed(PreparationFailure.EDIT_FAILED), fixturePreparation().prepare(order(), port, StopSignal()))
        assertEquals(0, port.edits)
    }
    @Test fun providerFailureIsReportedWithoutRetry() = runBlocking {
        var calls = 0
        val port = object : CartPreparationPort {
            override suspend fun observeCart(): ObservedCart { calls++; error("Unavailable test fixture") }
            override suspend fun applyAbsoluteQuantity(edit: ReconcileDecision.SetQuantity, stop: StopSignal): Boolean = error("Must never execute")
        }
        assertEquals(PreparationResult.Failed(PreparationFailure.PROVIDER_ERROR), CartPreparationEngine().prepare(order(), port, StopSignal()))
        assertEquals(1, calls)
    }
    @Test fun defaultEngineCannotExecuteAnyProviderMutation() = runBlocking {
        val port = MemoryCartPort(observed().copy(items = emptyList()))
        assertEquals(PreparationResult.Failed(PreparationFailure.ACTION_NOT_AUTHORIZED), CartPreparationEngine().prepare(order(), port, StopSignal()))
        assertEquals(0, port.edits)
    }
}

class MenuSelectionTest {
    private val source = CatalogProvenance(ProviderId.KEETA, "test-fixture", 1000, "test-screen")
    private fun menu() = RestaurantMenu("r", "المضياف العربي", "فرع الاختبار", "address-local-reference", listOf(
        MenuProduct("m", "دجاج مضبي", "نصف", true, null, listOf(
            MenuChoiceGroup("rice", "الأرز", 1, 1, listOf(MenuChoice("white", "أبيض", true), MenuChoice("red", "أحمر", false))),
        )),
    ), source, true)
    @Test fun requiredChoicesMustBeSelected() {
        assertEquals(SelectionResult.Rejected(SelectionError.REQUIRED_CHOICES), MenuSelectionValidator().select(menu(), ProductSelection("m", emptyMap(), 1), 2000))
    }
    @Test fun unknownAndUnavailableChoicesAreRejected() {
        assertEquals(SelectionResult.Rejected(SelectionError.UNKNOWN_CHOICE), MenuSelectionValidator().select(menu(), ProductSelection("m", mapOf("rice" to setOf("fake")), 1), 2000))
        assertEquals(SelectionResult.Rejected(SelectionError.UNAVAILABLE), MenuSelectionValidator().select(menu(), ProductSelection("m", mapOf("rice" to setOf("red")), 1), 2000))
    }
    @Test fun validSelectionKeepsUnknownPriceUnknown() {
        val result = MenuSelectionValidator().select(menu(), ProductSelection("m", mapOf("rice" to setOf("white")), 2), 2000) as SelectionResult.Accepted
        assertEquals(2, result.item.quantity)
        assertEquals(listOf(CartOption("الأرز", "أبيض")), result.item.options)
        assertNull(menu().products.single().listedPriceHalala)
    }
    @Test fun staleAndPartialMenusCannotCreateAnOrder() {
        val selection = ProductSelection("m", mapOf("rice" to setOf("white")), 1)
        assertEquals(SelectionResult.Rejected(SelectionError.STALE_MENU), MenuSelectionValidator().select(menu(), selection, 500_000))
        assertEquals(SelectionResult.Rejected(SelectionError.PARTIAL_MENU), MenuSelectionValidator().select(menu().copy(complete = false), selection, 2000))
    }
}

class QuoteValidationTest {
    private fun reading(at: Long = 1000) = QuoteObservation(ProviderId.KEETA, "test-fixture", "opaque-account-context", observed(),
        QuoteCandidate(mapOf(PriceField.ORDER_TOTAL to 2430, PriceField.DISCOUNT to -270), false), at, true, true)
    private fun validate(first: QuoteObservation = reading(), last: QuoteObservation = reading(2000)) = QuoteValidator().validate(order(), first, last, 2500)
    @Test fun candidateWithoutReviewOrAddressEvidenceCannotBecomeFinal() {
        assertEquals(QuoteValidation.Rejected(QuoteRejection.NOT_ORDER_REVIEW), validate(last = reading(2000).copy(recognizedOrderReview = false)))
        assertEquals(QuoteValidation.Rejected(QuoteRejection.CART_MISMATCH), validate(last = reading(2000).copy(observedCart = observed().copy(addressReference = null))))
        assertEquals(QuoteValidation.Rejected(QuoteRejection.FEES_UNKNOWN), validate(last = reading(2000).copy(mandatoryFeesIncluded = false)))
    }
    @Test fun verifiedTotalMayHavePartialDetails() {
        val result = validate() as QuoteValidation.Accepted
        assertEquals(2430L, result.quote.orderCost)
        assertTrue(result.detailsPartial)
        assertEquals(order().fingerprint, result.quote.cartFingerprint)
    }
    @Test fun taxIncludedIsNotAddedAgainAndNegativeDiscountIsNotSubtractedTwice() {
        val breakdown = PriceBreakdown(2700, emptyList(), 352, TaxTreatment.INCLUDED, -270)
        val result = validate(reading().copy(breakdown = breakdown), reading(2000).copy(breakdown = breakdown)) as QuoteValidation.Accepted
        assertFalse(result.detailsPartial)
        assertEquals(2430L, result.quote.orderCost)
    }
    @Test fun inconsistentCompleteBreakdownIsRejected() {
        val breakdown = PriceBreakdown(2700, listOf(500), null, TaxTreatment.INCLUDED, 270)
        assertEquals(QuoteValidation.Rejected(QuoteRejection.BREAKDOWN_MISMATCH), validate(reading().copy(breakdown = breakdown), reading(2000).copy(breakdown = breakdown)))
    }
    @Test fun changingPriceAccountOrShortStabilityIsRejected() {
        listOf(reading(2000).copy(candidate = QuoteCandidate(mapOf(PriceField.ORDER_TOTAL to 2500), false)),
            reading(2000).copy(accountContext = "different-account"), reading(1500))
            .forEach { assertEquals(QuoteValidation.Rejected(QuoteRejection.UNSTABLE), validate(last = it)) }
    }
    @Test fun walletDoesNotReduceOrderCostAndMustReconcileWhenKnown() {
        val fields = mapOf(PriceField.ORDER_TOTAL to 2430L, PriceField.WALLET to 1000L, PriceField.AMOUNT_DUE to 1430L)
        val a = reading().copy(candidate = QuoteCandidate(fields, false))
        val b = a.copy(capturedAtMillis = 2000)
        assertEquals(2430L, (validate(a, b) as QuoteValidation.Accepted).quote.orderCost)
        val bad = QuoteCandidate(fields + (PriceField.AMOUNT_DUE to 1400L), false)
        assertEquals(QuoteValidation.Rejected(QuoteRejection.WALLET_MISMATCH), validate(a.copy(candidate = bad), b.copy(candidate = bad)))
    }
    @Test fun futureAndOldObservationsAreRejected() {
        assertEquals(QuoteValidation.Rejected(QuoteRejection.STALE), validate(last = reading(3000)))
        assertEquals(QuoteValidation.Rejected(QuoteRejection.STALE), QuoteValidator().validate(order(), reading(), reading(2000), 500_000))
    }
    @Test fun overflowInFeeSumIsRejected() {
        val breakdown = PriceBreakdown(Long.MAX_VALUE, listOf(1), null, TaxTreatment.INCLUDED, 270)
        assertEquals(QuoteValidation.Rejected(QuoteRejection.INVALID_AMOUNT), validate(reading().copy(breakdown = breakdown), reading(2000).copy(breakdown = breakdown)))
    }
    @Test fun breakdownCannotContradictReadDiscount() {
        val breakdown = PriceBreakdown(2600, emptyList(), null, TaxTreatment.INCLUDED, 170)
        assertEquals(QuoteValidation.Rejected(QuoteRejection.BREAKDOWN_MISMATCH), validate(reading().copy(breakdown = breakdown), reading(2000).copy(breakdown = breakdown)))
    }
}

class ComparisonContextTest {
    private fun quote(provider: ProviderId, total: Long, at: Long = 2000) = VerifiedQuote(provider, total, null, null,
        order().fingerprint, order().addressReference!!, true, true, true, true, at)
    @Test fun latestReadingPerProviderIsUsedOnce() {
        val ranked = PriceComparisonEngine().rankFor(order(), listOf(quote(ProviderId.KEETA, 2500, 1000), quote(ProviderId.KEETA, 2700), quote(ProviderId.HUNGERSTATION, 2600)), 2500)
        assertEquals(2, ranked.size)
        assertEquals(ProviderId.HUNGERSTATION, ranked.first().provider)
    }
    @Test fun conflictingSameTimeProviderReadingsAreExcluded() {
        assertTrue(PriceComparisonEngine().rankFor(order(), listOf(quote(ProviderId.KEETA, 2500), quote(ProviderId.KEETA, 2600)), 2500).isEmpty())
    }
    @Test fun quoteForOtherOrderCannotPoisonCurrentOrderRanking() {
        val ranked = PriceComparisonEngine().rankFor(order(), listOf(quote(ProviderId.KEETA, 2500), quote(ProviderId.HUNGERSTATION, 2000).copy(cartFingerprint = "other-order")), 2500)
        assertEquals(ProviderId.KEETA, ranked.single().provider)
    }
}
