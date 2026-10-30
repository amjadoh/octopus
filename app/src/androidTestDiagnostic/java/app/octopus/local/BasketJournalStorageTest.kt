package app.octopus.local

import androidx.test.platform.app.InstrumentationRegistry
import app.octopus.local.automation.BasketMutationJournal
import app.octopus.local.catalog.CurrentAreaLocator
import app.octopus.local.data.AtomicBasketJournalStore
import app.octopus.local.domain.*
import app.octopus.local.providers.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test

class BasketJournalStorageTest {
    @Test fun interruptedMutationSurvivesActualFileReopenAndCorruptionFailsClosed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = context.cacheDir.resolve("journal-regression-${System.nanoTime()}.properties")
        val request = CanonicalCart("مطعم اختبار", null, listOf(CanonicalItem("وجبة", null, emptyList(), 3)))
        try {
            BasketMutationJournal(AtomicBasketJournalStore(file)).beforeDispatch(ProviderId.HUNGERSTATION, request)
            val restarted = BasketMutationJournal(AtomicBasketJournalStore(file))
            assertTrue(runCatching { restarted.beforeDispatch(ProviderId.HUNGERSTATION, request) }.isFailure)
            val evidence = BasketEvidence(ProviderId.HUNGERSTATION, request.restaurant,
                listOf(BasketLineEvidence("وجبة", emptyList(), 3, 2100)), 2100, true)
            assertTrue(restarted.observe(request, evidence))
            assertFalse(file.readText().contains(request.restaurant))
            file.writeText("")
            assertTrue(runCatching { AtomicBasketJournalStore(file).read() }.isFailure)
        } finally { file.delete(); file.resolveSibling(file.name + ".bak").delete() }
    }
    @Test fun deniedLocationPermissionOffersManualSelectionBeforeReadingGps() = runBlocking {
        val locator = CurrentAreaLocator(InstrumentationRegistry.getInstrumentation().targetContext)
        assumeFalse(locator.permitted())
        val failure = runCatching { locator.locate() }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("اختر المدينة والحي"))
    }
}
