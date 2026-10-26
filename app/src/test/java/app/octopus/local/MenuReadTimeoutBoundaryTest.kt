package app.octopus.local

import app.octopus.local.automation.*
import app.octopus.local.domain.ProviderId
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MenuReadTimeoutBoundaryTest {
    @Test fun nestedTimeoutLeavesPriceUnknownAndOnlyOtherProviderCanPrepare() = runBlocking {
        val available=mutableSetOf<ProviderId>()
        val prepared=mutableListOf<ProviderId>()
        CartComparisonFlow().run(readMenus={
            for(id in ProviderId.entries) {
                val price:Long?=MenuReadTimeoutBoundary.read(onTimeout={null}) {
                    if(id==ProviderId.KEETA) withTimeout(10) {awaitCancellation()} else 5300L
                }
                if(price!=null)available+=id
            }
        },available={it in available},prepare={prepared+=it;null},unchanged={true},progress={})
        assertEquals(listOf(ProviderId.HUNGERSTATION),prepared)
    }
    @Test fun enclosingTimeoutNeverRunsFallbackOrNextProvider() = runBlocking {
        var fallback=false;var next=false
        try {
            withTimeout(10) {
                MenuReadTimeoutBoundary.read(onTimeout={fallback=true}) {awaitCancellation()}
                next=true
            }
            fail("The enclosing comparison must expire")
        } catch(_:TimeoutCancellationException) {
            assertFalse(fallback);assertFalse(next)
        }
    }
    @Test fun userCancellationNeverRunsFallbackOrNextProvider() = runBlocking {
        val entered=CompletableDeferred<Unit>()
        var fallback=false;var next=false
        val job=launch {
            MenuReadTimeoutBoundary.read(onTimeout={fallback=true}) {entered.complete(Unit);awaitCancellation()}
            next=true
        }
        entered.await();job.cancelAndJoin()
        assertFalse(fallback);assertFalse(next)
    }
    @Test fun safetyAndUnknownScreenFailuresRemainWholeSessionFailures() = runBlocking {
        val expected=IllegalStateException("screen changed or protected")
        var fallback=false
        try {
            MenuReadTimeoutBoundary.read(onTimeout={fallback=true}) {throw expected}
            fail("Safety interruption must propagate")
        } catch(actual:IllegalStateException) {
            assertSame(expected,actual);assertFalse(fallback)
        }
    }
}
