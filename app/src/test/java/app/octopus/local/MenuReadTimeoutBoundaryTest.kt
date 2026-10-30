package app.octopus.local

import app.octopus.local.automation.*
import app.octopus.local.domain.ProviderId
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MenuReadTimeoutBoundaryTest {
    @Test fun readOnlyRetryRecoversOneNestedTimeoutButNeverLoopsForever() = runBlocking {
        var attempts=0
        val recovered=MenuReadTimeoutBoundary.read(retryOnce=true,onTimeout={-1}) {
            if(++attempts==1) withTimeout(10) {awaitCancellation()} else 40
        }
        assertEquals(40,recovered);assertEquals(2,attempts)
        attempts=0
        val unavailable=MenuReadTimeoutBoundary.read(retryOnce=true,onTimeout={-1}) {
            attempts++;withTimeout(10) {awaitCancellation()}
        }
        assertEquals(-1,unavailable);assertEquals(2,attempts)
    }
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
                MenuReadTimeoutBoundary.read(retryOnce=true,onTimeout={fallback=true}) {awaitCancellation()}
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
            MenuReadTimeoutBoundary.read(retryOnce=true,onTimeout={fallback=true}) {entered.complete(Unit);awaitCancellation()}
            next=true
        }
        entered.await();job.cancelAndJoin()
        assertFalse(fallback);assertFalse(next)
    }
    @Test fun safetyAndUnknownScreenFailuresRemainWholeSessionFailures() = runBlocking {
        val expected=IllegalStateException("screen changed or protected")
        var fallback=false
        try {
            MenuReadTimeoutBoundary.read(retryOnce=true,onTimeout={fallback=true}) {throw expected}
            fail("Safety interruption must propagate")
        } catch(actual:IllegalStateException) {
            assertSame(expected,actual);assertFalse(fallback)
        }
    }
}
