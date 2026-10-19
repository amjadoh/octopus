package app.octopus.local

import app.octopus.local.diagnostics.*
import app.octopus.local.data.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SessionEvidenceTest {
    private fun frame(stage: String = "AUTO_PRICE_READING", structure: String = "[]") =
        SessionEvidence(stage, structure, 1, false, 10_000)

    @Test fun transientOrChangingScreensAreNotRecorded() {
        val buffer = SessionEvidenceBuffer()
        buffer.observe(frame(), 0)
        buffer.observe(frame(), 640)
        buffer.observe(frame(structure = "[{}]"), 650)
        assertTrue(buffer.drain().isEmpty())
        buffer.observe(frame(), 1000)
        buffer.observe(frame(), 1650)
        assertEquals(1, buffer.drain().size)
    }

    @Test fun newSessionAndDeletionCannotLeakPreviousOrQueuedFrames() = runBlocking {
        val buffer = SessionEvidenceBuffer()
        buffer.observe(frame(), 0); buffer.observe(frame(), 650)
        buffer.clear()
        assertTrue(buffer.drain().isEmpty())
        val dao = FakeDao()
        val recorder = InspectionRecorder(dao)
        val old = recorder.generation()
        val record = ScreenInspectionReport("one", "KEETA", "unknown", "AUTO_PRICE_READING", 1, "[]", false, 1)
        recorder.record(listOf(record), old)
        recorder.clear()
        recorder.record(listOf(record), old)
        assertTrue(dao.rows.isEmpty())
        recorder.record(listOf(record), recorder.generation())
        assertEquals(1, dao.rows.size)
    }

    @Test fun repeatedStagesReplaceEarlierViewsAndHistoryIsBounded() {
        val buffer = SessionEvidenceBuffer(limit = 2)
        listOf("AUTO_CATALOG_HOME", "AUTO_CATALOG_MENU", "AUTO_CATALOG_PRODUCT").forEachIndexed { i, name ->
            buffer.observe(frame(name), i * 1000L)
            buffer.observe(frame(name), i * 1000L + 650)
        }
        assertEquals(listOf("AUTO_CATALOG_MENU", "AUTO_CATALOG_PRODUCT"), buffer.drain().map { it.stage })
        assertTrue(buffer.drain().isEmpty())
    }

    @Test fun emptyOversizedAndUnlabelledDataCannotEnterTheBuffer() {
        val buffer = SessionEvidenceBuffer()
        listOf(frame().copy(nodeCount = 0), frame().copy(nodeCount = 401),
            frame(structure = "x".repeat(131_073)), frame(stage = "user@example.com")).forEach {
            buffer.observe(it, 0); buffer.observe(it, 1000)
        }
        assertTrue(buffer.drain().isEmpty())
    }

    @Test fun observedFlutterIdentifiersSurviveButArbitraryIdsAndPrivateSubtreesDoNot() {
        listOf("pdpCloseButton", "flutterAddButton", "FlutterCart_cartItem", "gridMenuCell-0-0", "UniversalSearch_back_button")
            .forEach { assertEquals(it, InspectionRedactor.resourceId(it)) }
        listOf("customer123", "FlutterCart_customer_name", "pdpCloseButton secret", "gridMenuCell-123456-0")
            .forEach { assertNull(InspectionRedactor.resourceId(it)) }
        listOf("com.example:id/address", "com.example:id/credit_card", "com.example:id/otp")
            .forEach { assertTrue(InspectionRedactor.privateSubtree(it)) }
        assertFalse(InspectionRedactor.privateSubtree("FlutterCart_footer_main_action_button"))
        assertEquals("AMBIGUOUS_CHECKOUT", InspectionRedactor.tag("إتمام الدفع", InspectionRequest(InspectionStage.REVIEW, "", "")))
    }

    private class FakeDao : InspectionDao {
        val rows = mutableListOf<ScreenInspectionReport>()
        override suspend fun insert(report: ScreenInspectionReport) { rows += report }
        override suspend fun all(): List<ScreenInspectionReport> = rows.toList()
        override fun observe(): Flow<List<ScreenInspectionReport>> = flowOf(rows.toList())
        override suspend fun trim() = Unit
        override suspend fun clear() { rows.clear() }
    }
}
