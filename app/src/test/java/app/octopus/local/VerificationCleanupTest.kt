package app.octopus.local
import app.octopus.local.automation.VerificationCleanup
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
class VerificationCleanupTest {
    @Test fun cancellationCannotSkipCleanupAfterSuspendingDiagnosticWrite()=runBlocking {
        var recorded=false;var released=false
        val ready=CompletableDeferred<Unit>()
        val job=launch {
            try {ready.complete(Unit);awaitCancellation()}
            finally {VerificationCleanup.finish(record={delay(10);recorded=true},release={released=true})}
        }
        ready.await();job.cancelAndJoin()
        assertTrue(recorded);assertTrue(released)
    }
    @Test fun failedDiagnosticWriteStillReleasesSession()=runBlocking {
        var released=0
        VerificationCleanup.finish(record={error("disk unavailable")},release={released++})
        assertEquals(1,released)
    }
}
