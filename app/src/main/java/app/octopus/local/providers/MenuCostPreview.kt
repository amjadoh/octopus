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
    fun calculationAt(now: Long) = itemSubtotal?.let { OfferCalculator.evaluate(it, deliveryBeforeOffers, rules, now) }
    val calculation get() = calculationAt(System.currentTimeMillis())
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
    fun supports(cart: CanonicalCart) = cart.restaurant.isNotBlank() && cart.items.size == 1 &&
        cart.items.single().let { it.name.isNotBlank() && it.quantity in 1..99 &&
            it.size == null && it.options.isEmpty() && it.note.isNullOrBlank() }
}

/** Semantic observations on Honor / Keeta 3.12.501. No cart, quantity or payment command exists here. */
enum class KeetaSearchAction { OPEN_SEARCH, EDIT_QUERY, ENTER_QUERY, SUBMIT_QUERY }

open class KeetaMenuReader(val restaurant: String, val title: String, val VERSION: String = "3.12.501") {
    init { require(restaurant.isNotBlank() && title.isNotBlank() && restaurant.length <= 200 && title.length <= 300)
        require(VERSION in setOf("3.12.501", "3.13.203")) }
    /** Only the selected product's displayed base price. No hidden header or offer eligibility inferred. */
    fun itemPreview(frame: CatalogFrame, cart: CanonicalCart, at: Long): MenuCostPreview? {
        if (at < 0 || !PreviewEvidence.supports(cart) || cart.restaurant != restaurant ||
            cart.items.single().name != title || !product(frame)) return null
        if (VERSION == "3.13.203" && productBody(frame) == null) return null
        val unit = unitPrice(frame) ?: return null
        val amount = runCatching { Math.multiplyExact(unit, cart.items.single().quantity.toLong()) }.getOrNull() ?: return null
        return MenuCostPreview(ProviderId.KEETA, cart.fingerprint, amount, emptyList(), at, VERSION,
            "سعر الوجبة مقروء؛ رسوم التوصيل والخدمة والعروض المطبقة على السلة لم تُقرأ.")
    }
    fun functionalLabel(raw: String, resourceId: String = ""): String {
        val text = raw.replace(Regex("[\\u200e\\u200f\\u061c\\u202a-\\u202e\\u2066-\\u2069]"), "").trim()
        // Observed public menu title field. Retain visible titles so scrolling past unrelated
        // cards cannot look identical after redaction. Other unknown fields stay omitted.
        if (VERSION == "3.13.203" && resourceId == "com.sankuai.sailor.afooddelivery:id/product_name" &&
            text.length in 1..300 && text.any(Char::isLetter)) return text
        if (KeetaRestaurantName.matches(restaurant, text)) return restaurant
        if (KeetaAvailability.closedText(text) || KeetaAvailability.unavailableText(text) || text=="موافق") return text
        if (VERSION == "3.13.203" && (text in setOf("معلومات غذائية", "معلومات التغذية", "مسببات الحساسية") || text.matches(Regex("[0-9٠-٩]{1,5} سعرة حرارية")))) return text
        return if (text in setOf(restaurant, "feed shopcard", title, "homepage header searchbar", "search page box", "search page btn", "طلب خاص", "إضافة إلى السلة", "توصيل", "استلام شخصي",
                "الاصناف الرئيسية", "الأصناف الرئيسية", "الأصناف المضافة", "تفريغ السلة", "إتمام الدفع", "قسائمي", "شروط الاستخدام", "عرض خاص", "حسنًا", "حسنا", "1", "١") || text.matches(Regex("[0-9٠-٩]{1,2}")) || PreviewEvidence.offer(text) ||
            (text.length < 40 && text.contains('﷼') && MoneyParser.parse(text) != null) ||
            (text.length < 900 && Regex("وفر حتى|وف[َّ]*رت|تحتفظ Keeta|تنتهي|قسيمة|القسيمة|طلبات التوصيل فقط|اختر|إلزامي|الزامي|مطلوب|اختياري|اشتراك|مشترك|عضوية|قسيمة|كوبون|أول طلب|الجدد").containsMatchIn(text))) text else ""
    }
    fun homeSearch(frame: CatalogFrame): CatalogNode? = frame.nodes.singleOrNull {
        frame.complete && it.label == "homepage header searchbar" && it.clickable && it.enabled &&
            it.className == "android.widget.FrameLayout" && frame.nodes.any { parent ->
                parent.index == it.parent && parent.id.endsWith(":id/search_bar_container") }
    }
    fun searchInput(frame: CatalogFrame): CatalogNode? {
        if (!frame.complete) return null
        val box = frame.nodes.singleOrNull { !it.editable && it.label == "search page box" &&
            it.className == "android.widget.FrameLayout" } ?: return null
        return frame.nodes.singleOrNull { it.parent == box.index && it.editable && it.enabled &&
            it.className == "android.widget.EditText" }
    }
    fun searchSubmit(frame: CatalogFrame): CatalogNode? {
        if (searchInput(frame) == null) return null
        return frame.nodes.singleOrNull { it.label == "search page btn" && it.clickable && it.enabled &&
            !it.editable && it.className == "android.view.ViewGroup" }
    }
    fun editSearchQuery(frame: CatalogFrame): CatalogNode? {
        if (!frame.complete || !frame.hasId("titlebar") || !frame.hasId("flash_list_view")) return null
        return frame.nodes.singleOrNull { it.id.endsWith(":id/tv_keyword") && it.clickable && it.enabled &&
            !it.editable && it.className == "android.widget.TextView" }
    }
    fun searchTarget(frame: CatalogFrame, action: KeetaSearchAction): CatalogNode? = when (action) {
        KeetaSearchAction.OPEN_SEARCH -> homeSearch(frame)
        KeetaSearchAction.EDIT_QUERY -> editSearchQuery(frame)
        KeetaSearchAction.ENTER_QUERY -> searchInput(frame)
        KeetaSearchAction.SUBMIT_QUERY -> searchSubmit(frame)
    }
    fun menu(frame: CatalogFrame): Boolean = frame.complete && !basketDrawer(frame) && !KeetaPromotionEvidence.couponSheet(frame) && !KeetaPromotionEvidence.terms(frame) &&
        frame.nodes.none { it.label in setOf("طلب خاص", "إضافة إلى السلة") } && frame.nodes.count { it.label == restaurant } == 1 &&
        !(frame.hasId("titlebar") && frame.hasId("flash_list_view")) &&
        frame.nodes.count { it.scrollable && it.className == "androidx.recyclerview.widget.RecyclerView" } == 1

    /** Read-only candidate opening. The resulting menu must independently match the selected restaurant before any cart action. */
    fun searchCandidate(frame: CatalogFrame): CatalogNode? {
        if (!frame.complete || frame.nodes.singleOrNull { it.id.endsWith(":id/tv_keyword") }?.label != restaurant ||
            !frame.hasId("flash_list_view") || !frame.hasId("titlebar")) return null
        val card = frame.nodes.firstOrNull { it.id.endsWith(":id/dl_item_root") } ?: return null
        return frame.descendants(card.index).singleOrNull {
            it.label == "feed shopcard" && it.clickable && it.enabled &&
                it.className in (if (VERSION == "3.13.203") setOf("android.view.ViewGroup", "android.widget.Button") else setOf("android.widget.Button"))
        }
    }

    fun basketDrawer(frame: CatalogFrame): Boolean = frame.complete &&
        frame.nodes.any { it.label == restaurant } && frame.nodes.count { it.label == "الأصناف المضافة" } == 1 &&
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
        val productContent = (if (VERSION == "3.13.203") currentReadOnlyProductBody(frame) else productBody(frame)) ?: return null
        val modal = ancestors(frame, productContent).firstOrNull { parent ->
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
        if (VERSION == "3.13.203") return currentReadOnlyProductBody(frame) != null
        if (!frame.complete || frame.nodes.none { it.label == restaurant } ||
            frame.nodes.count { it.label == "إضافة إلى السلة" } != 1) return false
        val notes = frame.nodes.singleOrNull { it.label == "طلب خاص" } ?: return false
        val body = ancestors(frame, notes).firstOrNull { it.className == "android.widget.ScrollView" } ?: return false
        val content = frame.descendants(body.index)
        return content.count { it.label == title } == 1 && content.count { it.editable } <= 1 && content.none { it.omittedText } &&
            content.none { Regex("اختر|إلزامي|الزامي|مطلوب|اختياري").containsMatchIn(it.label) }
    }
    fun productBody(frame: CatalogFrame): CatalogNode? {
        if (VERSION == "3.13.203") return currentPlainProductBody(frame)
        val notes = frame.nodes.singleOrNull { it.label == "طلب خاص" } ?: return null
        return ancestors(frame, notes).firstOrNull { it.className == "android.widget.ScrollView" }
    }
    /** Read-only recognition does not authorize adding a product with unknown modifiers. */
    private fun currentReadOnlyProductBody(frame: CatalogFrame): CatalogNode? {
        KeetaSingleSkuProduct.body(frame,restaurant,title)?.let {return it}
        if (!frame.complete || frame.nodes.count { it.label == restaurant } != 1 ||
            frame.nodes.count { it.label == "إضافة إلى السلة" } != 1) return null
        return frame.nodes.filter { it.className == "android.widget.ScrollView" }.singleOrNull { body ->
            val content = frame.descendants(body.index)
            // A SKU can repeat the heading. Recognize the unique description-heading slot
            // for safe closing only; currentPlainProductBody still rejects unknown/scrolling options.
            val headings=content.filter { node ->
                if(node.label!=title || node.clickable || node.className!="android.widget.TextView") false else {
                    val group=frame.nodes.singleOrNull {it.index==node.parent}
                    val section=group?.let {g->frame.nodes.singleOrNull {it.index==g.parent}}
                    val parts=section?.let {p->frame.nodes.filter {it.parent==p.index}}.orEmpty()
                    parts.size>=2 && parts[0]==group && parts[1].className=="android.view.ViewGroup" && parts[1].clickable
                }
            }
            (content.count { it.label == title && !it.clickable } == 1 || headings.size==1) && content.none { it.editable } &&
                ancestors(frame,body).any { modal ->
                    val parts = frame.nodes.filter { it.parent == modal.index }
                    parts.size == 3 && parts[0].className == "android.widget.FrameLayout" &&
                        frame.descendants(parts[0].index).any { it.index == body.index } &&
                        frame.descendants(parts[2].index).count { it.label == "إضافة إلى السلة" } == 1
                }
        }
    }
    /** Honor / 3.13.203 plain-product modal. Only its observed description slot may omit text. */
    private fun currentPlainProductBody(frame: CatalogFrame): CatalogNode? {
        KeetaSingleSkuProduct.body(frame,restaurant,title)?.let {return it}
        if (!frame.complete || frame.nodes.count { it.label == restaurant } != 1 ||
            frame.nodes.count { it.label == "إضافة إلى السلة" } != 1) return null
        val candidates = frame.nodes.filter { it.className == "android.widget.ScrollView" && !it.scrollable }
        return candidates.singleOrNull { body ->
            val content = frame.descendants(body.index)
            val heading = content.singleOrNull { it.label == title && !it.clickable } ?: return@singleOrNull false
            if (content.any { it.editable || Regex("اختر|إلزامي|الزامي|مطلوب|اختياري|اختيار").containsMatchIn(it.label) }) return@singleOrNull false
            val titleGroup = frame.nodes.singleOrNull { it.index == heading.parent } ?: return@singleOrNull false
            val section = frame.nodes.singleOrNull { it.index == titleGroup.parent } ?: return@singleOrNull false
            val parts = frame.nodes.filter { it.parent == section.index }
            if (parts.size != 3 || parts[0] != titleGroup || parts[1].className != "android.view.ViewGroup" ||
                !parts[1].clickable || parts[2].className != "android.view.ViewGroup") return@singleOrNull false
            val description = frame.descendants(parts[1].index)
            if (description.size != 2 || description.last().className != "android.widget.TextView" ||
                description.last().clickable || description.last().editable) return@singleOrNull false
            content.none { it.omittedText && it.index != description.last().index }
        }
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
            if (children.size > 100 || children.any { it.label == title }) return null
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
            frame.nodes.none { it.label == restaurant } ||
            (frame.nodes.none { it.label in setOf("الاصناف الرئيسية", "الأصناف الرئيسية") } &&
                frame.nodes.count { it.scrollable && it.className == "androidx.recyclerview.widget.RecyclerView" } != 1)) return null
        val titleNode = frame.nodes.singleOrNull { it.label == title } ?: return null
        if (VERSION == "3.13.203") {
            if (!titleNode.id.endsWith(":id/product_name")) return null
            val card = frame.nodes.singleOrNull { it.index == titleNode.parent && it.id.endsWith(":id/card_root") } ?: return null
            val target = frame.nodes.singleOrNull { it.index == card.parent && it.clickable && it.enabled &&
                it.className == "android.widget.FrameLayout" && it.id.isEmpty() } ?: return null
            return target.takeIf { frame.descendants(it.index).count { child -> child.label == title } == 1 }
        }
        if (frame.nodes.none { it.label == "توصيل" }) return null
        val parent = ancestors(frame, titleNode).take(6).firstOrNull { it.clickable && it.enabled &&
            it.className == "android.view.ViewGroup" && it.id.isEmpty() } ?: return null
        val children = frame.descendants(parent.index)
        return parent.takeIf { children.size <= 80 && children.count { it.label == title } == 1 &&
            children.mapNotNull { child -> child.label.takeIf { it.contains('﷼') }?.let(MoneyParser::parse) }.size == 1 }
    }
}

/** Legacy recorded fixture context; production creates a reader for the selected request. */
object KeetaMenuPreview : KeetaMenuReader("ريدان", "نصف دجاج مضغوط")
