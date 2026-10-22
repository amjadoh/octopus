package app.octopus.local

import app.octopus.local.automation.*
import app.octopus.local.domain.ProviderId
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CartComparisonFlowTest {
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
