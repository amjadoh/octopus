package app.octopus.local.diagnostics

import app.octopus.local.domain.ProviderId
import app.octopus.local.providers.CatalogScreen

enum class CatalogStopReason { FINANCIAL_ELEMENT, FINANCIAL_CONTROL_ON_UNKNOWN_SCREEN }

enum class CatalogOperation { BROWSE, READ_PRODUCT, COMPARE_MENU, BUILD_CART, OPEN_PREPARED_CART }

/**
 * Structural evidence from the node that stops a catalog read, collected before screen stability.
 * The previous recognized screen is context only: it must never be treated as recognition of the
 * blocked screen. Text, content descriptions, entered values and screen images have no fields here.
 *
 * Construction is factory-only (including no data-class copy) so persisted evidence cannot bypass
 * metadata sanitization. An omitted identifier is preferable to retaining a dynamic personal value.
 * This is evidence of a stop, not permission to read or activate a financial element.
 */
class CatalogStopEvidence private constructor(
    val provider: ProviderId,
    val providerVersion: String?,
    val capturedAtMillis: Long,
    val priorRecognizedScreen: CatalogScreen?,
    val reason: CatalogStopReason,
    val resourceId: String?,
    val className: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val password: Boolean,
    val dataSensitive: Boolean,
    val appVersion: String?,
    val appVersionCode: Long,
    val operation: CatalogOperation,
    val requestFingerprint: String?,
) {
    val schemaVersion: Int = 1

    companion object {
        // No numeric suffixes: dynamic phone/account/order IDs are not needed to identify a control.
        private val controlName = Regex("[A-Za-z][A-Za-z_]{0,95}")
        private val frameworkClass = Regex("(?:android|androidx)(?:\\.[a-z][A-Za-z_]*){1,6}\\.[A-Z][A-Za-z_]*(?:\\$[A-Z][A-Za-z_]*)?")
        private val version = Regex("[0-9]{1,4}(?:\\.[0-9]{1,4}){1,3}(?:-[a-zA-Z][a-zA-Z]{0,15})?")
        private val fingerprint = Regex("[a-fA-F0-9]{64}")

        fun create(
            provider: ProviderId,
            providerVersion: String?,
            capturedAtMillis: Long,
            priorRecognizedScreen: CatalogScreen?,
            reason: CatalogStopReason,
            resourceId: String?,
            className: String?,
            clickable: Boolean,
            editable: Boolean,
            password: Boolean,
            dataSensitive: Boolean,
            appVersion: String,
            appVersionCode: Long,
            operation: CatalogOperation,
            requestFingerprint: String? = null,
        ): CatalogStopEvidence = CatalogStopEvidence(
            provider = provider,
            providerVersion = safeVersion(providerVersion),
            capturedAtMillis = capturedAtMillis.coerceAtLeast(0),
            priorRecognizedScreen = priorRecognizedScreen?.takeUnless { it == CatalogScreen.UNKNOWN },
            reason = reason,
            resourceId = safeResourceId(resourceId, provider),
            className = className?.takeIf { it.length <= 150 && frameworkClass.matches(it) },
            clickable = clickable,
            editable = editable,
            password = password,
            dataSensitive = dataSensitive,
            appVersion = safeVersion(appVersion),
            appVersionCode = appVersionCode.coerceAtLeast(0),
            operation = operation,
            requestFingerprint = requestFingerprint?.takeIf { fingerprint.matches(it) }?.lowercase(),
        )

        private fun safeVersion(value: String?): String? = value?.takeIf { it.length <= 40 && version.matches(it) }

        private fun safeResourceId(value: String?, provider: ProviderId): String? {
            if (value == null || value.length !in 1..180) return null
            if (controlName.matches(value)) return value
            // Package prefixes are known constants, never copied from arbitrary screen identifiers.
            val prefix = listOf("${provider.packageName}:id/", "android:id/")
                .singleOrNull { value.startsWith(it) } ?: return null
            return value.takeIf { controlName.matches(it.removePrefix(prefix)) }
        }
    }
}
