package app.octopus.local.pricing

/** Amounts are halalas. A calculated menu scenario is never proof of a final checkout total. */
enum class OfferTarget { ITEMS, DELIVERY }
enum class OfferStatus { ELIGIBLE, BELOW_MINIMUM, INELIGIBLE, UNVERIFIED }
data class OfferRule(
    val id: String, val title: String, val target: OfferTarget, val minimum: Long,
    val inclusiveMinimum: Boolean = true, val fixedDiscount: Long? = null,
    val percentBasisPoints: Int? = null, val maximumDiscount: Long? = null,
    val deliveryPrice: Long? = null, val termsComplete: Boolean = true,
    val requiresCoupon: Boolean = false, val couponVerified: Boolean? = null,
    val requiresMembership: Boolean = false, val membershipVerified: Boolean? = null,
    val stackGroup: String? = null, val validUntilMillis: Long? = null,
) {
    init {
        require(id.isNotBlank() && minimum >= 0)
        require(percentBasisPoints == null || percentBasisPoints in 1..10000)
        require(listOfNotNull(fixedDiscount, maximumDiscount, deliveryPrice).all { it >= 0 })
        require(if (target == OfferTarget.DELIVERY) deliveryPrice != null && fixedDiscount == null && percentBasisPoints == null
            else deliveryPrice == null && ((fixedDiscount != null) xor (percentBasisPoints != null)))
    }
}
data class OfferDecision(val rule: OfferRule, val status: OfferStatus, val remaining: Long = 0, val reason: String = "")
data class OfferCalculation(val itemSubtotal: Long, val itemDiscount: Long, val delivery: Long?,
    val selected: List<String>, val decisions: List<OfferDecision>) {
    val knownPartTotal: Long? get() = delivery?.let { Math.addExact(itemSubtotal - itemDiscount, it) }
}
object OfferCalculator {
    fun evaluate(subtotal: Long, delivery: Long?, rules: List<OfferRule>, now: Long = System.currentTimeMillis()): OfferCalculation {
        require(subtotal in 0..1_000_000_000 && (delivery == null || delivery in 0..1_000_000_000))
        require(rules.size <= 30 && rules.map { it.id }.distinct().size == rules.size)
        val decisions = rules.map { r ->
            when {
                r.validUntilMillis != null && now >= r.validUntilMillis -> OfferDecision(r, OfferStatus.INELIGIBLE, reason="انتهت صلاحية العرض")
                subtotal < r.minimum || (!r.inclusiveMinimum && subtotal == r.minimum) -> OfferDecision(r, OfferStatus.BELOW_MINIMUM,
                    (r.minimum - subtotal + if (r.inclusiveMinimum) 0 else 1).coerceAtLeast(0), "الحد الأدنى غير متحقق")
                r.requiresCoupon && r.couponVerified == false -> OfferDecision(r, OfferStatus.INELIGIBLE, reason="الكوبون غير صالح لهذا الطلب")
                r.requiresMembership && r.membershipVerified == false -> OfferDecision(r, OfferStatus.INELIGIBLE, reason="يتطلب اشتراكًا غير متاح")
                !r.termsComplete -> OfferDecision(r, OfferStatus.UNVERIFIED, reason="شروط العرض غير مكتملة")
                r.requiresCoupon && r.couponVerified != true -> OfferDecision(r, OfferStatus.UNVERIFIED, reason="صلاحية الكوبون غير متحققة")
                r.requiresMembership && r.membershipVerified != true -> OfferDecision(r, OfferStatus.UNVERIFIED, reason="الاشتراك غير متحقق")
                r.percentBasisPoints != null && r.maximumDiscount == null -> OfferDecision(r, OfferStatus.UNVERIFIED, reason="سقف الخصم غير مقروء")
                else -> OfferDecision(r, OfferStatus.ELIGIBLE)
            }
        }
        val eligible = decisions.filter { it.status == OfferStatus.ELIGIBLE }.map { it.rule }
        val groups = eligible.map { listOf(it) }.toMutableList()
        eligible.filter { it.stackGroup != null }.groupBy { it.stackGroup }.values.filter { group ->
            group.size > 1 && group.map { it.target }.distinct().size == group.size
        }.forEach { groups += it }
        fun scenario(group: List<OfferRule>): OfferCalculation {
            val item = group.singleOrNull { it.target == OfferTarget.ITEMS }
            val discount = item?.let { r ->
                r.fixedDiscount ?: ((subtotal * r.percentBasisPoints!!) / 10000).coerceAtMost(r.maximumDiscount!!)
            }?.coerceIn(0, subtotal) ?: 0
            val shipping = group.singleOrNull { it.target == OfferTarget.DELIVERY }?.deliveryPrice
            return OfferCalculation(subtotal, discount,
                if (shipping == null) delivery else delivery?.let { minOf(it, shipping) } ?: shipping,
                group.map { it.id }, decisions)
        }
        val baseline = scenario(emptyList())
        val candidates = listOf(baseline) + groups.map(::scenario)
        return candidates.filter { it.knownPartTotal != null }.minByOrNull { it.knownPartTotal!! }
            ?: candidates.maxBy { it.itemDiscount }
    }
}
