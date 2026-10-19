package app.octopus.local.diagnostics

import android.os.Build
import app.octopus.local.BuildConfig
import app.octopus.local.data.DiagnosticReport
import app.octopus.local.data.ScreenInspectionReport
import org.json.JSONArray
import org.json.JSONObject

object DiagnosticExporter {
    fun json(reports: List<DiagnosticReport>, inspections: List<ScreenInspectionReport> = emptyList()): String = JSONObject().apply {
        put("schema", "octopus.readonly-diagnostic.v2")
        put("appVersion", BuildConfig.VERSION_NAME)
        put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("androidRelease", Build.VERSION.RELEASE).put("api", Build.VERSION.SDK_INT))
        put("containsRawScreenText", false)
        put("containsAccountIdentifiers", false)
        put("resultsAreFinalPrices", false)
        put("screenInspections", JSONArray().apply {
            inspections.forEach { report -> put(JSONObject().apply {
                put("provider", report.provider); put("providerVersion", report.providerVersion)
                val automatic = report.stage.startsWith("AUTO_")
                put("captureMode", if (automatic) "automatic_session" else "manual_screen")
                put("stage", report.stage)
                put("stageUserLabelled", if (automatic) JSONObject.NULL else report.stage); put("capturedAt", report.capturedAt)
                put("nodeCount", report.nodeCount); put("truncated", report.truncated)
                put("readingObservation", JSONObject(report.observationJson))
                put("nodes", JSONArray(report.nodesJson)); put("deviceAutomationVerified", false)
            }) }
        })
        put("reports", JSONArray().apply {
            reports.forEach { report -> put(JSONObject().apply {
                put("provider", report.provider); put("providerVersion", report.providerVersion)
                put("adapterVersion", report.adapterVersion); put("capturedAt", report.capturedAt)
                put("outcome", report.outcome); put("candidateTotalHalala", report.candidateTotalHalala ?: JSONObject.NULL)
                put("fieldsHalala", JSONObject(report.fieldsJson)); put("visibleNodeCount", report.visibleNodes)
                put("addressVerified", false); put("cartVerified", false); put("finalPriceVerified", false)
            }) }
        })
    }.toString(2)
}
