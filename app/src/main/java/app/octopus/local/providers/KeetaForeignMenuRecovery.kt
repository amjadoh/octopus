package app.octopus.local.providers

/** A public food menu can be left via the observed exported launcher; no unknown node is clicked. */
object KeetaForeignMenuRecovery {
    fun permits(f:CatalogFrame,version:String):Boolean = version=="3.13.203" && f.complete &&
        f.nodes.none {it.editable || it.label in setOf("إضافة إلى السلة","إتمام الدفع","تأكيد الطلب","الأصناف المضافة","تفريغ السلة")} &&
        f.hasId("card_root") && f.nodes.any {it.id.endsWith(":id/product_name") && it.label.isNotBlank()} &&
        f.nodes.count {it.className=="androidx.recyclerview.widget.RecyclerView" && it.scrollable}==1
}
