package app.octopus.local

import app.octopus.local.automation.KeetaSearchReplan
import app.octopus.local.providers.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class KeetaSearchReplanTest {
    private val frame = Gson().fromJson(javaClass.getResource(
        "/recorded-keeta/keeta-3.13.301-search-repeat-redacted.json")!!.readText(), CatalogFrame::class.java)
    private val reader = KeetaMenuReader("كودو", "بطاطس وسط", "3.13.301")

    @Test fun actualSearchKeepsSeparateInputAndSubmitTargetsWithoutAnyCartAction() {
        assertEquals(14, reader.searchTarget(frame, KeetaSearchAction.ENTER_QUERY)?.index)
        assertEquals(15, reader.searchTarget(frame, KeetaSearchAction.SUBMIT_QUERY)?.index)
        assertNull(reader.searchTarget(frame, KeetaSearchAction.OPEN_SEARCH))
        assertNull(reader.searchTarget(frame, KeetaSearchAction.EDIT_QUERY))
        assertNull(reader.searchTarget(frame.copy(complete=false), KeetaSearchAction.ENTER_QUERY))
    }

    @Test fun twoRejectedPlansReobserveBeforeOneSuccessfulDispatch() = runBlocking {
        var attempts=0; var observations=0; var dispatches=0
        KeetaSearchReplan.run(frame, observe={ observations++; frame }) { fresh, marker ->
            assertEquals(frame,fresh)
            if (++attempts < 3) KeetaSearchReplan.Result.ChangedBeforeDispatch(IllegalArgumentException("stale"))
            else { marker.mark(); dispatches++; KeetaSearchReplan.Result.Dispatched }
        }
        assertEquals(3,attempts); assertEquals(2,observations); assertEquals(1,dispatches)
    }

    @Test fun threeRejectedPlansStopWithoutDispatchOrFourthObservation() = runBlocking {
        var attempts=0; var observations=0
        val failure=IllegalArgumentException("unchanged retry failure")
        try {
            KeetaSearchReplan.run(frame, observe={observations++;frame}) { _,_ ->
                attempts++; KeetaSearchReplan.Result.ChangedBeforeDispatch(failure)
            }
            fail("Must stop")
        } catch (actual: IllegalArgumentException) { assertSame(failure,actual) }
        assertEquals(3,attempts); assertEquals(2,observations)
    }

    @Test fun aFalseNativeResultAfterDispatchIsNeverRetried() = runBlocking {
        var dispatches=0
        val failure=IllegalStateException("native returned false")
        try {
            KeetaSearchReplan.run(frame, observe={error("No observation after dispatch")}) { _,marker ->
                marker.mark(); dispatches++; throw failure
            }
            fail("Must propagate")
        } catch (actual: IllegalStateException) { assertSame(failure,actual) }
        assertEquals(1,dispatches)
    }

    @Test fun evenAChangeMisclassifiedAfterDispatchCannotCauseAnotherAttempt() = runBlocking {
        var attempts=0
        val failure=IllegalArgumentException("after dispatch")
        try {
            KeetaSearchReplan.run(frame, observe={error("No observation after dispatch")}) { _,marker ->
                attempts++; marker.mark(); KeetaSearchReplan.Result.ChangedBeforeDispatch(failure)
            }
            fail("Must propagate")
        } catch (actual: IllegalArgumentException) { assertSame(failure,actual) }
        assertEquals(1,attempts)
    }

    @Test fun changedSessionOrSafetyFailureBeforeDispatchIsNotAReplanSignal() = runBlocking {
        val failure=IllegalStateException("request or version changed")
        try {
            KeetaSearchReplan.run(frame, observe={error("Safety failures never retry")}) { _,_ -> throw failure }
            fail("Must propagate")
        } catch (actual: IllegalStateException) { assertSame(failure,actual) }
    }

    @Test fun cancellationDuringReobservationPreventsTheNextAttempt() = runBlocking {
        var attempts=0
        val task=launch {
            KeetaSearchReplan.run(frame, observe={ currentCoroutineContext().cancel(); frame }) { _,_ ->
                attempts++; KeetaSearchReplan.Result.ChangedBeforeDispatch(IllegalArgumentException("stale"))
            }
        }
        task.join()
        assertTrue(task.isCancelled)
        assertEquals(1,attempts)
    }
}
