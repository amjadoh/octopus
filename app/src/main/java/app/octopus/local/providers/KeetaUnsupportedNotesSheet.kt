package app.octopus.local.providers

/** Diagnostic stop only. These anchors never authorize Close, Add, options or a price. */
object KeetaUnsupportedNotesSheet {
    const val MESSAGE = "شاشة خيارات الصنف في كيتا ذات الملاحظات غير مدعومة بعد؛ لم أضف الصنف أو أعدل السلة."

    fun matches(frame:CatalogFrame,restaurant:String,title:String,version:String?,knownProduct:Boolean):Boolean {
        if(KeetaVersionProfile.resolve(version)?.usesCurrentLayout!=true || knownProduct || !frame.complete || restaurant.isBlank() || title.isBlank())return false
        val editor=frame.nodes.singleOrNull {it.editable} ?: return false
        return !editor.enteredText && editor.enabled && editor.className=="android.widget.EditText" &&
            frame.nodes.count {it.label==restaurant && !it.editable}==1 &&
            frame.nodes.count {it.label==title && !it.clickable && !it.editable}==1 &&
            frame.nodes.count {it.label=="طلب خاص" && !it.clickable && !it.editable}==1 &&
            frame.nodes.count {it.label=="إضافة إلى السلة" && !it.editable}==1
    }
}
