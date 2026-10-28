package app.octopus.local.providers

/** Version-scoped coverage of the observed ALBAIK optional groups. Unknown choices stay blocked. */
class KeetaOptionalPlainSession private constructor(
    val restaurant: String, val title: String, private val amount: Long, private val at: Long,
) {
    private val groups = linkedSetOf<String>()
    private val choices = linkedSetOf<String>()
    private var rejected = false
    private var failure = "coverage"
    val blocked: Boolean get() = rejected
    override fun toString(): String = "rejected=$rejected failure=$failure groups=$groups choices=$choices"
    private val suffix = if (title == "بيج بيك") title else "برجر دجاج حراق"
    private val expectedGroups = setOf("اختر من اضافات-مبرده إلى $suffix",
        "اختر من اضافات- من غير صوص إلى $suffix", "اختر من اضافات-صوص إلى $suffix")
    private val expectedChoices = if (title == "بيج بيك") setOf("مع الجبنة", "بدون مخلل", "بدون سلطة", "بدون صلصة")
        else setOf("جبنة شيدر 1 قطعة", "جبنة شيدر مع الهالبينو", "بدون مخلل", "بدون مخلل حار", "بدون صلصة")

    fun observe(frame: CatalogFrame, reader: KeetaMenuReader, now: Long): Boolean {
        if (rejected || now < at || now - at > 120_000 || reader.restaurant != restaurant || reader.title != title ||
            reader.VERSION != "3.13.301" || reader.price(frame) != amount) { rejected = true; failure = "context"; return false }
        val body = readBody(frame, restaurant, title) ?: run { rejected = true; failure = "body"; return false }
        val content = frame.descendants(body.index)
        fun description(node: CatalogNode): Boolean {
            val parent = frame.nodes.singleOrNull { it.index == node.parent } ?: return false
            if (!parent.clickable || frame.descendants(parent.index).size != 2) return false
            val section = frame.nodes.singleOrNull { it.index == parent.parent } ?: return false
            val parts = frame.nodes.filter { it.parent == section.index }
            return parts.size >= 2 && parts[1] == parent && frame.descendants(parts[0].index).any { it.label == title }
        }
        val editors = content.filter { it.editable }
        val emptyNotes = editors.size <= 1 && editors.all { it.className == "android.widget.EditText" && it.id.isEmpty() && !it.enteredText }
        if (!emptyNotes || content.any { it.enteredText || Regex("إلزامي|الزامي|مطلوب").containsMatchIn(it.label) }) {
            rejected = true; failure = "required_or_entered"; return false
        }
        if (content.any { it.omittedText && !description(it) }) { rejected = true; failure = "unknown_text"; return false }
        for (heading in content.filter { it.label.startsWith("اختر من ") }) {
            if (heading.label !in expectedGroups) { rejected = true; failure = "unknown_group:${heading.label}"; return false }
            var section = heading
            repeat(3) { section = frame.nodes.singleOrNull { it.index == section.parent } ?: section }
            val labels = frame.descendants(section.index).map { it.label }
            val limit = if (heading.label.contains("- من غير صوص")) "اختر ما يصل إلى 2" else "اختر ما يصل إلى 1"
            if ("اختياري" !in labels || limit !in labels) continue
            groups += heading.label
        }
        for (choice in content.filter { it.label in expectedChoices }) {
            var row = choice
            while (!row.clickable && row.parent >= 0) row = frame.nodes.single { it.index == row.parent }
            val parts = frame.nodes.filter { it.parent == row.index }
            if (!row.enabled || parts.size != 2 || parts[0].clickable || !parts[1].clickable) {
                rejected = true; failure = "choice_structure"; return false
            }
            val indicator = frame.nodes.filter { it.parent == parts[1].index }.lastOrNull()
            // Both observed paid/free indicators have exactly two unlabelled wrappers when unchecked;
            // checked indicators add two more. Never infer state from the displayed price.
            if (indicator == null || frame.descendants(indicator.index).size != 2 ||
                frame.descendants(indicator.index).any { it.label.isNotEmpty() || it.omittedText || it.clickable }) {
                rejected = true; failure = "selected_choice"; return false
            }
            choices += choice.label
        }
        return groups == expectedGroups && choices == expectedChoices &&
            content.count { it.label == "طلب خاص" } == 1 && content.count { it.label == "0/70" } == 1 &&
            content.none { it.omittedText }
    }

    companion object {
        val publicLabels = setOf("مع الجبنة", "بدون مخلل", "بدون سلطة", "بدون صلصة", "جبنة شيدر 1 قطعة",
            "جبنة شيدر مع الهالبينو", "بدون مخلل حار", "شائع", "0/70",
            "سنشارك طلبك الخاص مع المتجر. لا يمكن إلغاء الطلب أو استرداد المبلغ إذا تعذّر تنفيذ طلبك الخاص.")
        fun start(frame: CatalogFrame, reader: KeetaMenuReader, now: Long): KeetaOptionalPlainSession? {
            if (reader.VERSION != "3.13.301" || reader.restaurant != "البيك" ||
                reader.title !in setOf("بيج بيك", "برجر فيليه دجاج حرّاق") || !reader.product(frame)) return null
            val body = readBody(frame, reader.restaurant, reader.title) ?: return null
            val content = frame.descendants(body.index)
            // The exact named base SKU and its description heading were both observed before scrolling.
            if (content.count { it.label == reader.title && !it.clickable } != 2) return null
            val price = reader.unitPrice(frame) ?: return null
            if (reader.price(frame) != price) return null
            return KeetaOptionalPlainSession(reader.restaurant, reader.title, price, now)
        }
        private fun modal(frame: CatalogFrame, scroll: CatalogNode, title: String): List<CatalogNode>? {
            var parent = scroll
            repeat(8) {
                parent = frame.nodes.singleOrNull { it.index == parent.parent } ?: return null
                val parts = frame.nodes.filter { it.parent == parent.index }
                if (parts.size !in 3..4 || parts[0].className != "android.widget.FrameLayout" ||
                    frame.descendants(parts[0].index).none { it.index == scroll.index }) return@repeat
                val close = frame.descendants(parts[parts.lastIndex-1].index)
                val footer = frame.descendants(parts.last().index)
                if (close.size > 8 || close.count { it.className == "android.widget.ImageView" } != 1 ||
                    close.any { it.label.isNotEmpty() || it.omittedText } || footer.size > 50 ||
                    footer.count { it.label == "إضافة إلى السلة" } != 1) return@repeat
                if (parts.size == 4) {
                    val header = frame.descendants(parts[1].index)
                    if(header.size > 4 || header.count { it.label == title } != 1 ||
                        header.any { it.omittedText || it.editable || it.label.isNotEmpty() && it.label != title }) return@repeat
                }
                if(frame.descendants(parent.index).any { it.label == title }) return parts
            }
            return null
        }
        /** Recognition permits reading/closing only; it never grants Add authority. */
        fun readBody(frame: CatalogFrame, restaurant: String, title: String): CatalogNode? {
            if (!frame.complete || frame.nodes.count { it.label == restaurant } != 1 ||
                frame.nodes.count { it.label == "إضافة إلى السلة" } != 1) return null
            return frame.nodes.filter { it.className == "android.widget.ScrollView" }.singleOrNull {
                modal(frame,it,title) != null
            }
        }
        fun closeTarget(frame: CatalogFrame, restaurant: String, title: String): CatalogNode? {
            val body = readBody(frame,restaurant,title) ?: return null
            val parts = modal(frame,body,title) ?: return null
            return frame.descendants(parts[parts.lastIndex-1].index).singleOrNull {
                it.clickable && it.enabled && it.className == "android.view.ViewGroup"
            }
        }
    }
}
