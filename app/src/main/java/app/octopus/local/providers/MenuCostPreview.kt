package app.octopus.local.providers

import app.octopus.local.domain.*
import app.octopus.local.pricing.MoneyParser
import app.octopus.local.pricing.OfferRule
import app.octopus.local.pricing.OfferCalculator

/** Pre-basket evidence. Offers are displayed, never silently converted to applied discounts. */
data class MenuCostPreview(
    val provider: ProviderId,
    val cartFingerprint: String,
    val itemSubtotal: Long?,
    val offers: List<String>,
    val capturedAtMillis: Long,
    val version: String,
    val error: String? = null,
    val deliveryBeforeOffers: Long? = null,
    val minimumOrder: Long? = null,
    val rules: List<OfferRule> = emptyList(),
) {
    // A menu does not prove mandatory fees, voucher application, branch or equal delivery context.
    val finalTotal: Long? get() = null
    val calculation get() = itemSubtotal?.let { OfferCalculator.evaluate(it, deliveryBeforeOffers, rules) }
    fun fresh(cart: CanonicalCart, now: Long) = cart.fingerprint == cartFingerprint &&
        now >= capturedAtMillis && now - capturedAtMillis <= 300_000
}

object PreviewEvidence {
    fun offer(text: String): Boolean = text.length in 2..240 &&
        text !in setOf("توصيل", "التوصيل") && !text.startsWith("ضمان") &&
        Regex("خصم|قسيمة|مجاني|الحد الأدنى|الحد الادنى|تطبيق تلقائي").containsMatchIn(text) &&
        !Regex("عنوان|شارع|هاتف|جوال|حساب|بطاقة|كلمة المرور|رمز التحقق").containsMatchIn(text)
    fun hungerComponent(frame: CatalogFrame, label: String): Long? {
        if (HungerStationCatalog.screen(frame) != CatalogScreen.MENU) return null
        val values = frame.nodes.mapNotNull { node ->
            val lines = node.label.lineSequence().map { it.replace(Regex("[\\u200e\\u200f\\u061c]"), "").trim() }.filter(String::isNotBlank).toList()
            val positions = lines.indices.filter { lines[it] == label }
            if (positions.size != 1) return@mapNotNull null
            val tail = lines.drop(positions.single() + 1)
            if (tail.firstOrNull() !in setOf("§", "﷼", "SAR")) return@mapNotNull null
            tail.getOrNull(1)?.let(MoneyParser::parse)?.takeIf { it >= 0 }
        }.distinct()
        return values.singleOrNull()
    }
    fun offers(frame: CatalogFrame): List<String> {
        val labels = frame.nodes.map { it.label.trim() }.filter(::offer).filterNot {
            it.contains("الحد الأدنى للطلب") && it.contains("رسوم التوصيل") &&
                hungerComponent(frame, "رسوم التوصيل") != null
        }.map { it.replace(Regex("[\\s\\u00a0]+"), " ").trim() }.distinct()
        return labels.filter { candidate -> labels.none { other -> other != candidate && other.contains(candidate) } }.take(12)
    }
    fun supports(cart: CanonicalCart) = cart.restaurant == "ريدان" && cart.items.size == 1 &&
        cart.items.single().let { it.name == "نصف دجاج مضغوط" && it.quantity in 1..99 &&
            it.size == null && it.options.isEmpty() && it.note.isNullOrBlank() }
}

/** Semantic observations on Honor / Keeta 3.12.501. No cart, quantity or payment command exists here. */
object KeetaMenuPreview {
    const val VERSION = "3.12.501"
    private const val TITLE = "نصف دجاج مضغوط"
    fun functionalLabel(raw: String): String {
        val text = raw.trim()
        return if (text in setOf("ريدان", "feed shopcard", TITLE, "طلب خاص", "إضافة إلى السلة", "توصيل", "استلام شخصي",
                "الاصناف الرئيسية", "الأصناف الرئيسية", "الأصناف المضافة", "تفريغ السلة", "إتمام الدفع", "قسائمي", "شروط الاستخدام", "عرض خاص", "حسنًا", "حسنا", "1", "١") || text.matches(Regex("[0-9٠-٩]{1,2}")) || PreviewEvidence.offer(text) ||
            (text.length < 40 && text.contains('﷼') && MoneyParser.parse(text) != null) ||
            (text.length < 900 && Regex("وفر حتى|وف[َّ]*رت|تحتفظ Keeta|تنتهي|قسيمة|القسيمة|طلبات التوصيل فقط|اختر|إلزامي|الزامي|مطلوب|اختياري|اشتراك|مشترك|عضوية|قسيمة|كوبون|أول طلب|الجدد").containsMatchIn(text))) text else ""
    }
    fun menu(frame: CatalogFrame): Boolean = frame.complete && !basketDrawer(frame) && !KeetaPromotionEvidence.couponSheet(frame) && !KeetaPromotionEvidence.terms(frame) &&
        frame.nodes.none { it.label == "طلب خاص" } && frame.nodes.count { it.label == "ريدان" } == 1 &&
        frame.nodes.any { it.label in setOf("الاصناف الرئيسية", "الأصناف الرئيسية") } &&
        frame.nodes.count { it.scrollable && it.className == "androidx.recyclerview.widget.RecyclerView" } == 1

    /** Read-only candidate opening. The resulting menu must independently say Ridan before any cart action. */
    fun searchCandidate(frame: CatalogFrame): CatalogNode? {
        if (!frame.complete || frame.nodes.singleOrNull { it.id.endsWith(":id/tv_keyword") }?.label != "ريدان" ||
            !frame.hasId("flash_list_view") || !frame.hasId("titlebar")) return null
        val card = frame.nodes.firstOrNull { it.id.endsWith(":id/dl_item_root") } ?: return null
        return frame.descendants(card.index).singleOrNull {
            it.label == "feed shopcard" && it.clickable && it.enabled && it.className == "android.widget.Button"
        }
    }

    fun basketDrawer(frame: CatalogFrame): Boolean = frame.complete &&
        frame.nodes.any { it.label == "ريدان" } && frame.nodes.count { it.label == "الأصناف المضافة" } == 1 &&
        frame.nodes.count { it.label == "تفريغ السلة" } == 1 && frame.nodes.any { it.label == "إتمام الدفع" }

    private fun ancestors(frame: CatalogFrame, node: CatalogNode): List<CatalogNode> {
        val result = mutableListOf<CatalogNode>()
        var current = node
        repeat(12) {
            current = frame.nodes.singleOrNull { it.index == current.parent } ?: return result
            result += current
        }
        return result
    }
    fun closeProduct(frame: CatalogFrame): CatalogNode? {
        if (!product(frame)) return null
        val notes = frame.nodes.singleOrNull { it.label == "طلب خاص" } ?: return null
        val modal = ancestors(frame, notes).firstOrNull { parent ->
            val children = frame.nodes.filter { it.parent == parent.index }
            children.size == 3 && children[0].className == "android.widget.FrameLayout" &&
                frame.descendants(children[0].index).any { it.className == "android.widget.ScrollView" } &&
                frame.descendants(children[2].index).any { it.label == "إضافة إلى السلة" }
        } ?: return null
        val close = frame.nodes.filter { it.parent == modal.index }[1]
        val body = frame.descendants(close.index)
        if (body.size > 8 || body.any { it.label.isNotEmpty() || it.omittedText } ||
            body.count { it.className == "android.widget.ImageView" } != 1) return null
        return body.singleOrNull { it.clickable && it.enabled && it.className == "android.view.ViewGroup" }
    }
    fun product(frame: CatalogFrame): Boolean {
        if (!frame.complete || frame.nodes.none { it.label == "ريدان" } ||
            frame.nodes.count { it.label == "إضافة إلى السلة" } != 1) return false
        val notes = frame.nodes.singleOrNull { it.label == "طلب خاص" } ?: return false
        val body = ancestors(frame, notes).firstOrNull { it.className == "android.widget.ScrollView" } ?: return false
        val content = frame.descendants(body.index)
        return content.count { it.label == TITLE } == 1 && content.count { it.editable } <= 1 &&
            content.none { Regex("اختر|إلزامي|الزامي|مطلوب|اختياري").containsMatchIn(it.label) }
    }
    fun price(frame: CatalogFrame): Long? {
        if (!product(frame)) return null
        val add = frame.nodes.single { it.label == "إضافة إلى السلة" }
        for (parent in ancestors(frame, add).take(6)) {
            val children = frame.descendants(parent.index)
            if (children.size > 50 || children.count { it.label == "إضافة إلى السلة" } != 1) return null
            val prices = children.filter { it.label.contains('﷼') }.mapNotNull { MoneyParser.parse(it.label) }.filter { it >= 0 }
            if (prices.isNotEmpty()) return prices.singleOrNull()
        }
        return null
    }
    fun unitPrice(frame: CatalogFrame): Long? {
        val total = price(frame) ?: return null
        val add = frame.nodes.single { it.label == "إضافة إلى السلة" }
        for (parent in ancestors(frame, add)) {
            val children = frame.descendants(parent.index)
            if (children.size > 100 || children.any { it.label == TITLE }) return null
            val numbers = children.filter { !it.clickable && it.label.matches(Regex("[0-9٠-٩]{1,2}")) }
                .mapNotNull { MoneyParser.parse(it.label)?.div(100) }
            if (numbers.isNotEmpty()) {
                val quantity = numbers.singleOrNull()?.takeIf { it in 1..99 } ?: return null
                return if (total % quantity == 0L) total / quantity else null
            }
        }
        return null
    }

    fun productTarget(frame: CatalogFrame): CatalogNode? {
        if (!frame.complete || basketDrawer(frame) || frame.nodes.any { it.label in setOf("طلب خاص", "إضافة إلى السلة") } ||
            frame.nodes.none { it.label == "ريدان" } || frame.nodes.none { it.label == "توصيل" } ||
            frame.nodes.none { it.label in setOf("الاصناف الرئيسية", "الأصناف الرئيسية") }) return null
        val title = frame.nodes.singleOrNull { it.label == TITLE } ?: return null
        val parent = ancestors(frame, title).take(6).firstOrNull { it.clickable && it.enabled &&
            it.className == "android.view.ViewGroup" && it.id.isEmpty() } ?: return null
        val children = frame.descendants(parent.index)
        return parent.takeIf { children.size <= 80 && children.count { it.label == TITLE } == 1 &&
            children.mapNotNull { child -> child.label.takeIf { it.contains('﷼') }?.let(MoneyParser::parse) }.size == 1 }
    }
}
