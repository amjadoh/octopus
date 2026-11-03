package app.octopus.local.providers

import app.octopus.local.pricing.MoneyParser

/** Observed 3.13.203 single-SKU food modal, and the 3.13.301 form of it that adds an empty
 * special-request section. No choice, note or payment action is authorized. A sole SKU with the
 * exact product name is plain only when its price agrees with the Add footer.
 */
object KeetaSingleSkuProduct {
    private fun children(f:CatalogFrame,n:CatalogNode)=f.nodes.filter {it.parent==n.index}
    private fun quantity(n:CatalogNode)=n.label.takeIf {it.matches(Regex("[0-9٠-٩]{1,2}"))}
        ?.let(MoneyParser::parse)?.div(100)?.toInt()?.takeIf {it in 1..99}
    private fun footer(f:CatalogFrame,title:String,add:CatalogNode):Pair<Long,Int>? {
        var node=add
        repeat(12) {
            node=f.nodes.singleOrNull {it.index==node.parent} ?: return null
            val body=f.descendants(node.index)
            if(body.size>50 || body.any {it.label==title || it.editable || it.omittedText})return null
            val amounts=body.filter {it.label.contains('﷼')}.mapNotNull {MoneyParser.parse(it.label)}
            val counts=body.filter {!it.clickable}.mapNotNull(::quantity)
            if(counts.isNotEmpty())return amounts.singleOrNull()?.let {amount->counts.singleOrNull()?.let {amount to it}}
        }
        return null
    }
    const val NOTES_HINT = "سنشارك طلبك الخاص مع المتجر. لا يمكن إلغاء الطلب أو استرداد المبلغ إذا تعذّر تنفيذ طلبك الخاص."
    /** The empty-note counter: "0/70" on the Honor, "70/0" on the Samsung S22 (same counter, other text direction). */
    val EMPTY_NOTE_COUNTERS = setOf("0/70", "70/0")

    /** The 3.13.301 special-request section: its heading, one empty note field and the 0/70 counter. */
    private fun emptyNotes(f:CatalogFrame,section:CatalogNode):Boolean {
        val body=f.descendants(section.index)
        val editors=body.filter {it.editable}
        val labelled=body.filter {it.label.isNotEmpty()}
        return body.size<=12 && editors.size==1 && editors[0].className=="android.widget.EditText" &&
            !editors[0].enteredText && editors[0].label in setOf("",NOTES_HINT) &&
            body.none {it.omittedText || it.scrollable || (it.clickable && !it.editable)} &&
            labelled.count {it.label=="طلب خاص"}==1 && labelled.count {it.label in EMPTY_NOTE_COUNTERS}==1 &&
            labelled.all {it.label=="طلب خاص" || it.label in EMPTY_NOTE_COUNTERS || it.editable}
    }

    fun body(f:CatalogFrame,restaurant:String,title:String):CatalogNode? {
        if(!f.complete || restaurant.isBlank() || title.isBlank() || f.nodes.count {it.editable}>1 ||
            f.nodes.any {it.editable && it.enteredText} || f.nodes.count {it.label==restaurant}!=1)return null
        val add=f.nodes.singleOrNull {it.label=="إضافة إلى السلة" && it.enabled} ?: return null
        val (total,count)=footer(f,title,add) ?: return null
        return f.nodes.filter {it.className=="android.widget.ScrollView" && !it.scrollable}.singleOrNull {scroll ->
            val content=f.descendants(scroll.index)
            val container=children(f,scroll).singleOrNull() ?: return@singleOrNull false
            val sections=children(f,container)
            val notes=sections.getOrNull(2)
            if(sections.size !in 2..3 || (notes!=null && !emptyNotes(f,notes)) ||
                f.nodes.any {it.editable && (notes==null || f.descendants(notes.index).none {n->n.index==it.index})} ||
                content.any {it.scrollable})return@singleOrNull false
            val noteNodes=notes?.let {f.descendants(it.index).map {n->n.index}.toSet()}.orEmpty()
            val heading=f.descendants(sections[0].index).singleOrNull {it.label==title && !it.clickable}
                ?: return@singleOrNull false
            val titleGroup=f.nodes.singleOrNull {it.index==heading.parent} ?: return@singleOrNull false
            val section=f.nodes.singleOrNull {it.index==titleGroup.parent} ?: return@singleOrNull false
            val parts=children(f,section)
            if(parts.size!=2 || parts[0]!=titleGroup || !parts[1].clickable ||
                parts[1].className!="android.view.ViewGroup")return@singleOrNull false
            val description=f.descendants(parts[1].index)
            if(description.size!=2 || description.last().className!="android.widget.TextView" ||
                description.last().clickable)return@singleOrNull false
            val sku=f.descendants(sections[1].index)
            val row=sku.singleOrNull {it.clickable && it.enabled} ?: return@singleOrNull false
            val rowBody=f.descendants(row.index)
            val price=sku.singleOrNull {it.label.contains('﷼')} ?: return@singleOrNull false
            val unit=MoneyParser.parse(price.label)?.takeIf {it>0} ?: return@singleOrNull false
            if(sku.size>20 || sku.any {it.omittedText} || sku.count {it.label==title && !it.clickable}!=1 ||
                rowBody.none {it.index==price.index} || sku.filter {it.label.isNotEmpty()}.any {
                    it.label!=title && it.index!=price.index
                })return@singleOrNull false
            if(content.any {it.omittedText && it.index!=description.last().index} ||
                content.filter {it.label.isNotEmpty() && it.index !in noteNodes}.any {it.label!=title && it.index!=price.index &&
                    it.index!=description.last().index})return@singleOrNull false
            runCatching {Math.multiplyExact(unit,count.toLong())==total}.getOrDefault(false)
        }
    }
}
