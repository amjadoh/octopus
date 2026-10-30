package app.octopus.local

import app.octopus.local.catalog.firstUsableLocation
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LocationLookupRaceTest {
    @Test fun allUnavailableSourcesReturnWithoutWaitingForOverallDeadline() = runBlocking {
        assertNull(withTimeout(1000) { firstUsableLocation<Int>(listOf({null},{error("unavailable")})) {it>0} })
    }
    @Test fun unusableEarlyResultDoesNotHideLaterGoodFix() = runBlocking {
        assertEquals(2,firstUsableLocation<Int>(listOf({-1},{yield();2})) {it>0})
    }
    @Test fun goodFixCancelsOutstandingSensorRequest() = runBlocking {
        var cancelled=false
        val started=CompletableDeferred<Unit>()
        val result=firstUsableLocation<Int>(listOf(
            {try {started.complete(Unit);awaitCancellation()} finally {cancelled=true}},
            {started.await();3}
        )) {it>0}
        assertEquals(3,result); assertTrue(cancelled)
    }
    @Test fun closingLookupCancelsEveryOutstandingRequest() = runBlocking {
        var cancelled=0
        val jobs=launch { firstUsableLocation<Int>(List(2) {
            suspend {try {awaitCancellation()} finally {cancelled++}}
        }) {it>0} }
        yield();yield()
        jobs.cancelAndJoin()
        assertEquals(2,cancelled)
    }
}
