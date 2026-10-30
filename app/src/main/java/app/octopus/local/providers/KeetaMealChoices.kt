package app.octopus.local.providers

import app.octopus.local.domain.CanonicalCart

/** Required radio groups in the current product sheet. No coordinate or fuzzy matching. */
class KeetaMealChoices(val cart: CanonicalCart) {
    private val item = cart.items.single()
    val values = item.options.map { it.value }.toSet()
    private val completed = mutableSetOf<String>()
    private val singleChoiceSeen = mutableSetOf<String>()
    private val requiredGroups = mutableMapOf<String, String?>()
    private val incompleteHeaders = mutableSetOf<String>()
    private var pending: String? = null
    private var failed = false
    private var reachedEnd = false
    private var readyFrame: CatalogFrame? = null
    val ready: Boolean get() = !failed && pending == null && incompleteHeaders.isEmpty() && reachedEnd && completed == values &&
        requiredGroups.size == values.size && requiredGroups.values.toSet() == values

    init {
        require(item.quantity == 1 && item.size == null && item.note.isNullOrBlank() &&
            values.size in 1..12 && values.size == item.options.size && item.options.all { it.quantity == 1 })
    }

    data class Group(val key: String, val value: String?, val done: Boolean, val target: CatalogNode?)
    sealed interface Step {
        data class Select(val value: String, val target: CatalogNode) : Step
        data object Scroll : Step
        data object VerifyPrevious : Step
        data object Ready : Step
        data object Blocked : Step
    }

    fun next(frame: CatalogFrame, menu: KeetaMenuReader): Step {
        if (failed || !frame.complete || menu.title != item.name || menu.restaurant != cart.restaurant ||
            !menu.product(frame) || frame.nodes.any { it.enteredText }) return Step.Blocked
        val groups = groups(frame) ?: return Step.Blocked.also { failed = true }
        for (group in groups) {
            if (!group.done) singleChoiceSeen += group.key
            else if (group.key !in singleChoiceSeen) return Step.Blocked.also { failed = true }
            val previous = requiredGroups[group.key]
            if (previous != null && group.value != null && previous != group.value) return Step.Blocked.also { failed = true }
            requiredGroups[group.key] = group.value ?: previous
        }
        if (requiredGroups.size > values.size) return Step.Blocked.also { failed = true }
        pending?.let { value ->
            val observed = groups.singleOrNull { it.value == value }
            if (observed == null) return Step.VerifyPrevious
            if (!observed.done) return Step.Blocked.also { failed = true }
            completed += value
            pending = null
        }
        groups.firstOrNull { it.value != null && it.value !in completed && it.target != null }?.let {
            return Step.Select(it.value!!, it.target!!)
        }
        return if (ready) Step.Ready.also { readyFrame = frame }
            else if (reachedEnd) Step.Blocked.also { failed = true } else Step.Scroll
    }

    /** Called before one dispatch. An uncertain dispatch is never retried. */
    fun consume(step: Step.Select, frame: CatalogFrame, menu: KeetaMenuReader): Boolean {
        if (pending != null || next(frame, menu) != step) return false
        pending = step.value
        return true
    }

    fun endObserved() { reachedEnd = true }

    fun addTarget(frame: CatalogFrame, menu: KeetaMenuReader): CatalogNode? {
        if (!ready || frame != readyFrame || !menu.product(frame) || !frame.complete || frame.nodes.any { it.enteredText }) return null
        val add = frame.nodes.singleOrNull { it.label == "إضافة إلى السلة" } ?: return null
        var parent = add
        repeat(6) {
            parent = frame.nodes.singleOrNull { it.index == parent.parent } ?: return null
            val parts = frame.nodes.filter { it.parent == parent.index }
            if (parts.size == 2 && parts[0].clickable && parts[0].enabled && !parts[0].editable &&
                parts[0].label.isEmpty() && parts[0].className == "android.view.ViewGroup" &&
                frame.descendants(parts[1].index).count { it.label == "إضافة إلى السلة" } == 1) {
                var footer = parent
                repeat(5) {
                    footer = frame.nodes.singleOrNull { it.index == footer.parent } ?: return null
                    val content = frame.descendants(footer.index)
                    if (content.any { it.label == item.name || it.scrollable }) return null
                    val counts = content.filter { it.label.matches(Regex("[0-9٠-٩]+")) }
                    if (counts.isNotEmpty()) return parts[0].takeIf { counts.singleOrNull()?.label in setOf("1", "١") }
                }
                return null
            }
        }
        return null
    }

    private fun groups(frame: CatalogFrame): List<Group>? {
        fun children(n: CatalogNode) = frame.nodes.filter { it.parent == n.index }
        val results = mutableListOf<Group>()
        val markers = frame.nodes.filter { it.label == "مطلوب" }
        if (frame.nodes.any { it.label == "اختياري" }) return null
        for (marker in markers) {
            var parent = marker
            var found: Group? = null
            repeat(6) {
                if (found == null) {
                    parent = frame.nodes.singleOrNull { it.index == parent.parent } ?: return null
                    val parts = children(parent)
                    if (parts.size in 1..2) {
                        val header = frame.descendants(parts[0].index)
                        val rows = parts.getOrNull(1)?.let(::children).orEmpty()
                        if (header.any { it.index == marker.index } &&
                            header.count { it.label in setOf("اختر 1", "اختر ١", "تم") } == 1 &&
                            rows.all { it.clickable && it.enabled && !it.editable && it.className == "android.view.ViewGroup" }) {
                            val status = header.filter { it.label in setOf("اختر 1", "اختر ١", "تم") }
                            if (status.size != 1) return null
                            val matches = rows.mapNotNull { row ->
                                val body = frame.descendants(row.index)
                                val named = body.filter { it.label in values }
                                if (named.size == 1) row to named.single().label else null
                            }
                            if (matches.size > 1) return null
                            val key = header.filter { it.className == "android.widget.TextView" && it.label.isNotBlank() &&
                                it.label !in setOf("مطلوب", "اختر 1", "اختر ١", "تم") }.singleOrNull()?.label ?: return null
                            val matched = matches.singleOrNull()
                            if (matched == null) {
                                found = Group(key, null, status.single().label == "تم", null)
                                return@repeat
                            }
                            val (row, value) = matched
                            // Observed row owns a nested label/icon press handler. Use the
                            // smallest clickable ancestor containing the exact requested label.
                            val namedHandler = frame.descendants(row.index).filter { n ->
                                n.clickable && n.enabled && !n.editable &&
                                    frame.descendants(n.index).any { it.label == value }
                            }.minByOrNull { frame.descendants(it.index).size } ?: return null
                            // The label can open nested modifiers. The adjacent empty handler
                            // is the radio control; never dispatch the label handler in that layout.
                            val parts = children(namedHandler)
                            val target = if (namedHandler != row) {
                                if (parts.size != 2 || frame.descendants(parts[0].index).none { it.label == value }) return null
                                parts[1].takeIf { it.clickable && it.enabled && !it.editable &&
                                    it.className == "android.view.ViewGroup" &&
                                    frame.descendants(it.index).all { child -> child.label.isBlank() && !child.omittedText } } ?: return null
                            } else namedHandler
                            found = Group(key, value, status.single().label == "تم", target)
                        }
                    }
                }
            }
            if (found == null) {
                // A viewport may end at the required badge, before its limit or rows.
                // Remember the heading until its complete group is observed; never add on it.
                var ancestor = marker
                var clippedKey: String? = null
                repeat(4) {
                    ancestor = frame.nodes.singleOrNull { it.index == ancestor.parent } ?: return null
                    val body = frame.descendants(ancestor.index)
                    if (clippedKey == null && body.count { it.label == "مطلوب" } == 1 &&
                        body.none { it.label in setOf("اختر 1", "اختر ١", "تم") }) {
                        clippedKey = body.filter { it.className == "android.widget.TextView" &&
                            it.label.isNotBlank() && it.label != "مطلوب" }.singleOrNull()?.label
                    }
                }
                incompleteHeaders += clippedKey ?: return null
            } else {
                incompleteHeaders -= found!!.key
                results += found!!
            }
        }
        if (results.map { it.key }.distinct().size != results.size ||
            results.mapNotNull { it.value }.let { it.distinct().size != it.size }) return null
        return results
    }

    companion object {
        /** Restore only a product radio-group heading, never arbitrary account/menu text. */
        fun restoreHeaders(frame: CatalogFrame, menu: KeetaMenuReader, candidates: Map<Int, String>): CatalogFrame {
            if (menu.requestedChoices.isEmpty() || menu.VERSION != "3.13.301" || !menu.product(frame)) return frame
            val headings = mutableMapOf<Int, String>()
            for (marker in frame.nodes.filter { it.label == "مطلوب" }) {
                var parent = marker
                for (depth in 0 until 5) {
                    parent = frame.nodes.singleOrNull { it.index == parent.parent } ?: break
                    val body = frame.descendants(parent.index)
                    if (body.count { it.label == "مطلوب" } == 1 &&
                        body.count { it.label in setOf("اختر 1", "اختر ١", "تم") } <= 1) {
                        val heading = body.filter { it.className == "android.widget.TextView" && it.omittedText && !it.clickable &&
                            !it.editable && candidates[it.index] != null }.singleOrNull()
                        if (heading != null) headings.putIfAbsent(heading.index, candidates.getValue(heading.index))
                        if (heading != null) break
                    }
                }
            }
            return frame.copy(nodes = frame.nodes.map { node -> headings[node.index]?.let { node.copy(label=it,omittedText=false) } ?: node })
        }
    }
}
