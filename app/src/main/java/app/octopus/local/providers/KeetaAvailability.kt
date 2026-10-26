package app.octopus.local.providers

/** Observed closed-store notice. This only stops work; it authorizes no button click. */
object KeetaAvailability {
    fun closedText(raw:String):Boolean = raw.replace(Regex("[\\u064b-\\u065f\\u0670\\u200e\\u200f\\u061c]"),"")
        .replace(Regex("\\s+")," ").trim()=="هذا المتجر مغلق حاليا. ألق نظرة على المتاجر الأخرى."
    fun closedNotice(frame:CatalogFrame):Boolean = frame.complete && frame.nodes.size<=30 &&
        frame.nodes.none {it.editable} && frame.nodes.count {closedText(it.label)}==1 &&
        frame.nodes.count {it.label=="موافق" && it.clickable && it.enabled}==1
}
