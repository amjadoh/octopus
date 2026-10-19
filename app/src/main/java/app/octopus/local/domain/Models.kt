package app.octopus.local.domain

enum class ProviderId(val title: String, val packageName: String) {
    KEETA("كيتا", "com.sankuai.sailor.afooddelivery"),
    HUNGERSTATION("هنقرستيشن", "com.hungerstation.android.web")
}

data class ProviderInstallation(val provider: ProviderId, val installed: Boolean, val version: String? = null)
enum class SessionPhase { IDLE, WAITING_FOR_SCREEN, READING, COMPLETED, STOPPED, FAILED }
data class SessionState(
    val phase: SessionPhase = SessionPhase.IDLE,
    val provider: ProviderId? = null,
    val message: String = "لم تبدأ جلسة قراءة بعد",
    val remainingSeconds: Int = 0,
) {
    val active: Boolean get() = phase == SessionPhase.WAITING_FOR_SCREEN || phase == SessionPhase.READING
}

enum class PriceField { ORDER_TOTAL, SUBTOTAL, DELIVERY, SERVICE, SMALL_ORDER, TAX, DISCOUNT, WALLET, AMOUNT_DUE }
data class MoneyToken(val halala: Long)
data class SafeToken(
    val label: PriceField? = null,
    val amount: Long? = null,
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
    val right: Int = 0,
    val parent: String = "",
)
data class SafeSnapshot(
    val tokens: List<SafeToken>,
    val visibleNodeCount: Int,
    val sensitiveScreen: Boolean,
    val truncated: Boolean = false,
)
data class QuoteCandidate(val fields: Map<PriceField, Long>, val ambiguous: Boolean) {
    val total: Long? get() = if (ambiguous) null else fields[PriceField.ORDER_TOTAL]
    val fingerprint: String get() = fields.toSortedMap(compareBy { it.ordinal }).toString() + ambiguous
}

// Diagnostic price candidates cannot be promoted to a verified order quote.
data class CartOption(val group: String, val value: String, val quantity: Int = 1) {
    init {
        require(IdentityText.clean(group).isNotBlank() && IdentityText.clean(value).isNotBlank() && quantity in 1..99)
        require(group.length <= 300 && value.length <= 300)
    }
}
data class CanonicalItem(
    val name: String,
    val size: String?,
    val options: List<CartOption>,
    val quantity: Int,
    val note: String? = null,
) {
    init {
        require(IdentityText.clean(name).isNotBlank() && name.length <= 512 && quantity in 1..99)
        require(size == null || (IdentityText.clean(size).isNotBlank() && size.length <= 300))
        require(options.size <= 100)
        require(options.map { IdentityText.clean(it.group) to IdentityText.clean(it.value) }.distinct().size == options.size)
        require(note == null || note.length <= 500)
    }
}
data class CanonicalCart(
    val restaurant: String,
    val branch: String?,
    val items: List<CanonicalItem>,
    val addressReference: String? = null,
) {
    init {
        require(IdentityText.clean(restaurant).isNotBlank() && restaurant.length <= 512 && items.isNotEmpty() && items.size <= 100)
        require(branch == null || (IdentityText.clean(branch).isNotBlank() && branch.length <= 512))
        require(addressReference == null || (addressReference.isNotBlank() && addressReference.length <= 200))
        require(items.groupBy { CartIdentity.itemKey(it) }.values.all { rows -> rows.sumOf { it.quantity } <= 99 })
    }
    val fingerprint: String get() = CartIdentity.cartFingerprint(this)
}
data class VerifiedQuote(
    val provider: ProviderId,
    val orderCost: Long?,
    val walletApplied: Long?,
    val amountDueNow: Long?,
    val cartFingerprint: String,
    val addressReference: String,
    val cartVerified: Boolean,
    val addressVerified: Boolean,
    val branchVerified: Boolean,
    val allMandatoryFeesIncluded: Boolean,
    val capturedAtMillis: Long,
)
