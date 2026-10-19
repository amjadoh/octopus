package app.octopus.local.diagnostics

import app.octopus.local.data.ScreenInspectionReport
import app.octopus.local.domain.ProviderId
import org.json.JSONObject
import java.util.UUID

/** Reuses the existing local inspection store, export, retention limit and delete control. */
suspend fun InspectionRecorder.recordAutomaticEvidence(
    provider: ProviderId, version: String, frames: List<SessionEvidence>, outcome: String, generation: Long,
) {
    val reports = frames.map { frame ->
        ScreenInspectionReport(UUID.randomUUID().toString(), provider.name, version,
            frame.stage, frame.capturedAtMillis, frame.nodesJson, frame.truncated, frame.nodeCount,
            JSONObject().put("captureOrigin", "active_session").put("outcome", outcome)
                .put("recognizedOrderReview", false).put("cartVerified", false)
                .put("finalPriceVerified", false).toString())
    }
    record(reports, generation)
}
