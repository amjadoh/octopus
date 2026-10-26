package app.octopus.local.providers

/** Observed closed-store notice. This only stops work; it authorizes no button click. */
object KeetaAvailability {
    private fun normalized(raw:String):String = raw.replace(Regex("[\\u064b-\\u065f\\u0670\\u200e\\u200f\\u061c]"),"")
        .replace(Regex("\\s+")," ").trim()
    fun closedText(raw:String):Boolean = normalized(raw)=="هذا المتجر مغلق حاليا. ألق نظرة على المتاجر الأخرى."
    fun unavailableText(raw:String):Boolean = normalized(raw)=="المتجر مغلق وغير متاح مؤقتا"
    /** Actual disabled-menu banner observed on Fawal 3.13.203; no click authority. */
    fun closedMenu(frame:CatalogFrame):Boolean = frame.complete && frame.nodes.none {it.editable} &&
        frame.hasId("card_root") && frame.nodes.count {unavailableText(it.label)}==1
    fun closedNotice(frame:CatalogFrame):Boolean = frame.complete && frame.nodes.size<=30 &&
        frame.nodes.none {it.editable} && frame.nodes.count {closedText(it.label)}==1 &&
        frame.nodes.count {it.label=="موافق" && it.clickable && it.enabled}==1
}
