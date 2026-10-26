package app.octopus.local.automation

import app.octopus.local.domain.CanonicalCart
import app.octopus.local.providers.*

enum class HungerMenuSearchAction { OPEN, TYPE, CLOSE }

/** Read-only search inside an observed restaurant. No Add/payment/quantity operation exists. */
class HungerMenuSearchSession private constructor(
    private val initial:CatalogFrame, private val fingerprint:String, val query:String, private val createdAt:Long,
) {
    private var stage=0
    private fun valid(cart:CanonicalCart,version:String?,now:Long)=cart.fingerprint==fingerprint &&
        version==HungerStationCatalog.TESTED_VERSION && now>=createdAt && now-createdAt<=90_000
    private fun searchFrame(f:CatalogFrame):Boolean=f.complete &&
        f.nodes.none {Regex("password|otp|payment|checkout|credit|bank",RegexOption.IGNORE_CASE).containsMatchIn(it.id)} &&
        f.nodes.count {it.editable}==1 &&
        f.nodes.singleOrNull {it.id=="menuSearchInput"}?.let {it.editable && it.enabled && it.className=="android.widget.EditText"}==true &&
        f.nodes.singleOrNull {it.id=="menuSearchCloseButton"}?.let {it.clickable && it.enabled && !it.editable && it.className=="android.widget.ImageView"}==true
    fun target(action:HungerMenuSearchAction,f:CatalogFrame,cart:CanonicalCart,version:String?,now:Long):CatalogNode? {
        if(!valid(cart,version,now))return null
        return when(action) {
            HungerMenuSearchAction.OPEN -> if(stage==0 && f==initial)f.nodes.singleOrNull {it.id=="menuSearchButton" && it.clickable && it.enabled} else null
            HungerMenuSearchAction.TYPE -> if(stage==1 && searchFrame(f))f.nodes.single {it.id=="menuSearchInput"} else null
            HungerMenuSearchAction.CLOSE -> if(stage in 1..2 && searchFrame(f))f.nodes.single {it.id=="menuSearchCloseButton"} else null
        }
    }
    fun consume(action:HungerMenuSearchAction,f:CatalogFrame,node:CatalogNode,cart:CanonicalCart,version:String?,now:Long):Boolean {
        if(target(action,f,cart,version,now)!=node)return false
        stage=when(action) {HungerMenuSearchAction.OPEN->1;HungerMenuSearchAction.TYPE->2;HungerMenuSearchAction.CLOSE->3}
        return true
    }
    fun results(f:CatalogFrame,cart:CanonicalCart,version:String?,now:Long):Boolean=stage==2 && valid(cart,version,now) && searchFrame(f)
    data class Candidate(val node:CatalogNode,val item:LiveMenuItem)
    /** Data observations only. Opening or adding a candidate still needs its own typed command. */
    fun candidates(f:CatalogFrame,cart:CanonicalCart,version:String?,now:Long):List<Candidate> {
        if(!results(f,cart,version,now))return emptyList()
        return f.nodes.mapNotNull {node->HungerStationCatalog.menuItem(node)?.takeIf {it.name==query}?.let {Candidate(node,it)}}
    }
    companion object {
        fun create(menu:CatalogFrame,cart:CanonicalCart,query:String,version:String?,now:Long):HungerMenuSearchSession? {
            if(now<0 || version!=HungerStationCatalog.TESTED_VERSION || !menu.complete ||
                HungerStationCatalog.screen(menu)!=CatalogScreen.MENU || menu.nodes.any {it.editable} ||
                query.length !in 1..300 || cart.items.none {it.name==query} ||
                menu.nodes.none {it.label.lineSequence().any {line->line.trim()==cart.restaurant}} ||
                menu.nodes.singleOrNull {it.id=="menuSearchButton" && it.className=="android.widget.ImageView" && it.clickable && it.enabled}==null)return null
            return HungerMenuSearchSession(menu,cart.fingerprint,query,now)
        }
    }
}
