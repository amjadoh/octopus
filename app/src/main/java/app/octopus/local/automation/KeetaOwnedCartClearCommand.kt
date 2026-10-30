package app.octopus.local.automation
import app.octopus.local.domain.*
import app.octopus.local.providers.*

/** Explicit test-cart reset only. Does not admit any follow-up confirmation or payment control. */
class KeetaOwnedCartClearCommand private constructor(private val source:CatalogFrame,
    private val fingerprint:String,val target:CatalogNode,private val at:Long) {
    private var used=false
    fun consume(frame:CatalogFrame,node:CatalogNode,request:CanonicalCart,version:String?,now:Long):Boolean {
        if(used || frame!=source || node!=target || fingerprint!=request.fingerprint || version!="3.13.203" ||
            now<at || now-at>15_000)return false
        used=true;return true
    }
    companion object {
        fun create(frame:CatalogFrame,request:CanonicalCart,actual:BasketEvidence,version:String?,now:Long):KeetaOwnedCartClearCommand? {
            if(version!="3.13.203" || now<0 || !PlainComparisonSupport.existingTwoRow(request))return null
            val reader=KeetaBasketReader(KeetaMenuReader(request.restaurant,request.items.first().name,version),request.items.map {it.name}.toSet())
            if(reader.evidence(frame)!=actual || !actual.complete || !BasketItemMatcher.compare(request,actual).matches)return null
            val heading=frame.nodes.singleOrNull {it.label=="الأصناف المضافة" && !it.clickable && !it.editable} ?: return null
            val label=frame.nodes.singleOrNull {it.label=="تفريغ السلة" && !it.clickable && !it.editable} ?: return null
            val target=frame.nodes.singleOrNull {it.index==label.parent && it.parent==heading.parent && it.clickable && it.enabled &&
                it.label.isEmpty() && it.className=="android.view.ViewGroup" && !it.editable && !it.scrollable} ?: return null
            val children=frame.nodes.filter {it.parent==target.index}
            if(children.size!=2 || children[1]!=label || children[0].className!="android.widget.ImageView" ||
                children.any {it.clickable || it.editable || it.omittedText || it.scrollable})return null
            return KeetaOwnedCartClearCommand(frame,request.fingerprint,target,now)
        }
    }
}
