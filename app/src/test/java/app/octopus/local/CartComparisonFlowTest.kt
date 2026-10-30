package app.octopus.local

import app.octopus.local.automation.*
import app.octopus.local.domain.ProviderId
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CartComparisonFlowTest {
    @Test fun nativePathPreparesKeetaBeforeReadingSourceAndKeepsBothTotalsRequired() = runBlocking {
        val calls = mutableListOf<ProviderId>()
        val totals = mutableSetOf<ProviderId>()
        val progress = mutableListOf<CartComparisonProgress>()
        CartComparisonFlow().run({}, { true }, { provider ->
            calls += provider
            if (provider == ProviderId.HUNGERSTATION) assertTrue(ProviderId.KEETA in totals)
            totals += provider
            null
        }, { true }, progress::add, totalAvailable = totals::contains,
            providerOrder = listOf(ProviderId.KEETA, ProviderId.HUNGERSTATION))
        assertEquals(listOf(ProviderId.KEETA, ProviderId.HUNGERSTATION), calls)
        assertEquals(2, progress.count { it.step == CartComparisonStep.TOTAL_READ })
    }
    @Test fun sourceReviewFailureDoesNotPreventEarlierTargetPreparationOrBecomeSuccess() = runBlocking {
        val calls = mutableListOf<ProviderId>()
        val progress = mutableListOf<CartComparisonProgress>()
        try {
            CartComparisonFlow().run({}, { true }, { provider ->
                calls += provider
                check(provider != ProviderId.HUNGERSTATION) { "Source review unavailable" }
                null
            }, { true }, progress::add, totalAvailable = { it == ProviderId.KEETA },
                providerOrder = listOf(ProviderId.KEETA, ProviderId.HUNGERSTATION))
            fail("Source review failure must propagate")
        } catch (_: IllegalStateException) {
            assertEquals(listOf(ProviderId.KEETA, ProviderId.HUNGERSTATION), calls)
            assertFalse(progress.any { it.provider == ProviderId.HUNGERSTATION && it.step == CartComparisonStep.TOTAL_READ })
        }
    }
    @Test fun invalidProviderOrderIsRejectedBeforeReadingOrEditing() = runBlocking {
        var calls = 0
        try {
            CartComparisonFlow().run({ calls++ }, { true }, { calls++; null }, { true }, {},
                providerOrder = listOf(ProviderId.KEETA, ProviderId.KEETA))
            fail("Duplicate provider must be rejected")
        } catch (_: IllegalArgumentException) { assertEquals(0, calls) }
    }
    @Test fun showsReadStageBeforeAnyPreparationAndStopsWhenContextChangesDuringRead() = runBlocking {
        var unchanged=true
        val progress=mutableListOf<CartComparisonProgress>()
        var edits=0
        try {
            CartComparisonFlow().run({
                assertEquals(ProviderId.entries.toSet(),progress.map {it.provider}.toSet())
                assertTrue(progress.all {it.step==CartComparisonStep.READING_MENU})
                unchanged=false
            },{true},{edits++;null},{unchanged},progress::add)
            fail("Changed region or order must stop before preparation")
        } catch (_:IllegalStateException) { assertEquals(0,edits) }
    }
    @Test fun preparationDoesNotBecomeAReadTotalWithoutTheExplicitReadingSignal() = runBlocking {
        val results = mutableListOf<CartComparisonProgress>()
        CartComparisonFlow().run({}, { true }, { null }, { true }, results::add,
            totalAvailable = { it == ProviderId.HUNGERSTATION })
        assertEquals(1,results.count { it.step == CartComparisonStep.TOTAL_READ })
        assertEquals(1,results.count { it.step == CartComparisonStep.REVIEW_UNAVAILABLE })
    }
    @Test fun preparesSequentiallyAndNeverClaimsAQuoteFromPreparation() = runBlocking {
        val calls = mutableListOf<String>()
        val results = mutableListOf<CartComparisonProgress>()
        CartComparisonFlow().run({ calls += "read" }, { true }, { calls += it.name; null }, { true }, results::add)
        assertEquals(listOf("read") + ProviderId.entries.map { it.name }, calls)
        assertEquals(2, results.count { it.step == CartComparisonStep.REVIEW_UNAVAILABLE })
    }
    @Test fun missingMenuNeverDispatchesCartMutation() = runBlocking {
        val calls = mutableListOf<ProviderId>()
        val results = mutableListOf<CartComparisonProgress>()
        CartComparisonFlow().run({}, { it == ProviderId.HUNGERSTATION }, { calls += it; "تغيرت السلة" }, { true }, results::add)
        assertEquals(listOf(ProviderId.HUNGERSTATION), calls)
        assertEquals(2, results.count { it.step == CartComparisonStep.PREPARATION_FAILED })
    }
    @Test fun changedRequestPreventsNextProvider() = runBlocking {
        var unchanged = true
        var mutations = 0
        try {
            CartComparisonFlow().run({}, { true }, { mutations++; unchanged = false; null }, { unchanged }, {})
            fail("Changed cart must stop")
        } catch (_: IllegalStateException) { assertEquals(1, mutations) }
    }
    @Test fun cancellationNeverStartsSecondProvider() = runBlocking {
        val calls = mutableListOf<ProviderId>()
        val run = launch {
            CartComparisonFlow().run({}, { true }, { calls += it; awaitCancellation() }, { true }, {})
        }
        yield()
        run.cancelAndJoin()
        assertEquals(1, calls.size)
    }
}
