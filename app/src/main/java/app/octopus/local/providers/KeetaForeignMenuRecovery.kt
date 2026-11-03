package app.octopus.local.providers

/** A public food menu can be left via the observed exported launcher; no unknown node is clicked. */
object KeetaForeignMenuRecovery {
    fun permits(f:CatalogFrame,version:String):Boolean = KeetaVersionProfile.resolve(version)?.let {
        it.usesCurrentLayout && it.allowsReading()
    } == true && f.complete &&
        f.nodes.none {it.editable || it.label in setOf("إضافة إلى السلة","إتمام الدفع","تأكيد الطلب","الأصناف المضافة","تفريغ السلة")} &&
        f.hasId("card_root") && f.nodes.any {it.id.endsWith(":id/product_name") && it.label.isNotBlank()} &&
        f.nodes.count {it.className=="androidx.recyclerview.widget.RecyclerView" && it.scrollable}==1

    /** S22 / Keeta 3.13.301: Keeta reopened on another restaurant's menu with its cart drawer still open
     * (left there by an earlier comparison). Only system Back closes such a drawer; no node is clicked. */
    fun drawerOverForeignMenu(f:CatalogFrame,version:String):Boolean = KeetaVersionProfile.resolve(version)?.let {
        it.usesCurrentLayout && it.allowsReading()
    } == true && f.complete && f.nodes.none {it.editable || it.label=="تأكيد الطلب"} &&
        listOf("الأصناف المضافة","تفريغ السلة","إتمام الدفع").all {label -> f.nodes.count {it.label==label}==1} &&
        f.hasId("card_root") && f.nodes.any {it.id.endsWith(":id/product_name") && it.label.isNotBlank()}
}
