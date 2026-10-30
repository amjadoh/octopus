package app.octopus.local.diagnostics

import app.octopus.local.data.ScreenInspectionReport
import app.octopus.local.domain.ProviderId
import org.json.JSONObject
import java.util.UUID

/** Reuses the existing local inspection store, export, retention limit and delete control. */
suspend fun InspectionRecorder.recordAutomaticEvidence(
    provider: ProviderId, version: String, frames: List<SessionEvidence>, outcome: String, generation: Long,
    stopped: CatalogStopEvidence? = null,
) {
    val reports = frames.map { frame ->
        ScreenInspectionReport(UUID.randomUUID().toString(), provider.name, version,
            frame.stage, frame.capturedAtMillis, frame.nodesJson, frame.truncated, frame.nodeCount,
            JSONObject().put("captureOrigin", "active_session").put("outcome", outcome)
                .put("isFailureSnapshot", false)
                .put("recognizedOrderReview", false).put("cartVerified", false)
                .put("finalPriceVerified", false).toString())
    } + listOfNotNull(stopped?.let { stop ->
        // A rejected frame never reaches the stable-view buffer. Store only its structural stop cause.
        // Use the actual stopped provider, not the platform on which the multi-provider session began.
        ScreenInspectionReport(UUID.randomUUID().toString(), stop.provider.name, stop.providerVersion ?: "unknown",
            "AUTO_CATALOG_STOP", stop.capturedAtMillis, "[]", true, 0, catalogStopObservation(stop).toString())
    })
    record(reports, generation)
}

fun catalogStopObservation(stop: CatalogStopEvidence): JSONObject = JSONObject()
    .put("schemaVersion", stop.schemaVersion)
    .put("provider", stop.provider.name)
    .put("providerVersion", stop.providerVersion ?: JSONObject.NULL)
    .put("capturedAt", stop.capturedAtMillis)
    .put("captureOrigin", "active_session_stop_metadata")
    .put("outcome", "FINANCIAL_STOP")
    .put("reason", stop.reason.name)
    .put("operation", stop.operation.name)
    .put("appVersion", stop.appVersion ?: JSONObject.NULL)
    .put("appVersionCode", stop.appVersionCode)
    .put("priorRecognizedScreen", stop.priorRecognizedScreen?.name ?: JSONObject.NULL)
    .put("currentScreenRecognized", false)
    .put("isFailureSnapshot", false)
    .put("resourceId", stop.resourceId ?: JSONObject.NULL)
    .put("className", stop.className ?: JSONObject.NULL)
    .put("clickable", stop.clickable).put("editable", stop.editable)
    .put("password", stop.password).put("dataSensitive", stop.dataSensitive)
    .put("requestFingerprint", stop.requestFingerprint ?: JSONObject.NULL)
    .put("recognizedOrderReview", false).put("cartVerified", false).put("finalPriceVerified", false)
