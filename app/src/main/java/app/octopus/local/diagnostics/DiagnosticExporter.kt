package app.octopus.local.diagnostics

import android.os.Build
import app.octopus.local.BuildConfig
import app.octopus.local.data.DiagnosticReport
import org.json.JSONArray
import org.json.JSONObject

object DiagnosticExporter {
    fun json(reports: List<DiagnosticReport>): String = JSONObject().apply {
        put("schema", "octopus.readonly-diagnostic.v1")
        put("appVersion", BuildConfig.VERSION_NAME)
        put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("androidRelease", Build.VERSION.RELEASE).put("api", Build.VERSION.SDK_INT))
        put("containsRawScreenText", false)
        put("containsAccountIdentifiers", false)
        put("resultsAreFinalPrices", false)
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
