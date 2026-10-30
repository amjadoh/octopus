package app.octopus.local.automation

import app.octopus.local.domain.IdentityText
import app.octopus.local.providers.*
import app.octopus.local.pricing.MoneyParser
import java.util.concurrent.atomic.AtomicBoolean

/** Completeness for positive-quantity rows: every unit in the observed footer must be accounted for.
 * Bound to one actual MENU -> CART command; never proves address, branch, or an order-review total.
 */
class HungerStationCompleteCartProof private constructor(
    val command: BasketOpenCommand,
    private val restaurant: String,
    private val quantity: Int,
    private val itemAmount: Long,
    private val issuedAt: Long,
) {
    private val used=AtomicBoolean(false)
    fun finish(frame:CatalogFrame,version:String?,now:Long):BasketEvidence? {
        if(!used.compareAndSet(false,true) || !command.wasConsumed || version!=HungerStationCatalog.TESTED_VERSION ||
            now<issuedAt || now-issuedAt>30_000 || !frame.complete || HungerStationCatalog.screen(frame)!=CatalogScreen.CART)return null
        val evidence=BasketEvidenceReader.hungerStation(frame,true)
        if(!evidence.complete || evidence.restaurant?.let(IdentityText::clean)!=IdentityText.clean(restaurant) ||
            evidence.lines.any {it.quantity !in 1..99 || it.lineAmount==null || it.lineAmount<0} ||
            evidence.lines.sumOf {it.quantity!!}!=quantity)return null
        val sum=runCatching {evidence.lines.fold(0L) {sum,row->Math.addExact(sum,row.lineAmount!!)}}.getOrNull() ?: return null
        return evidence.takeIf {sum==itemAmount && it.subtotal==sum}
    }
    companion object {
        private fun menuValues(frame:CatalogFrame,restaurant:String):Pair<Int,Long>? {
            val target=HungerStationBasketControls.openTarget(frame) ?: return null
            if(frame.nodes.none {!it.editable && it.label.lineSequence().any {line->IdentityText.clean(line)==IdentityText.clean(restaurant)}})return null
            val count=frame.nodes.singleOrNull {it.parent==target.index && it.id=="FlutterMenu_goToCartQuantity"} ?: return null
            val quantity=count.label.map {if(it in '٠'..'٩')('0'.code+it.code-'٠'.code).toChar() else it}.joinToString("").toIntOrNull() ?: return null
            val amount=target.label.replace(Regex("[\\u200e\\u200f\\u061c\\u202a-\\u202e\\u2066-\\u2069]"),"")
                .lineSequence().map(String::trim).toList().getOrNull(1)?.let(MoneyParser::parse) ?: return null
            return quantity to amount
        }
        fun fromMenu(frame:CatalogFrame,restaurant:String,version:String?,now:Long):HungerStationCompleteCartProof? {
            if(version!=HungerStationCatalog.TESTED_VERSION || now<0 || restaurant.isBlank() || restaurant.length>512)return null
            val (quantity,amount)=menuValues(frame,restaurant) ?: return null
            return HungerStationCompleteCartProof(BasketOpenCommand(frame,HungerStationBasketControls.openTarget(frame)!!,now),restaurant,quantity,amount,now)
        }
        fun matchesMenu(frame:CatalogFrame,before:BasketEvidence):Boolean {
            val restaurant=before.restaurant ?: return false
            val (quantity,amount)=menuValues(frame,restaurant) ?: return false
            if(!before.complete || before.lines.isEmpty() || before.lines.any {it.quantity !in 1..99 || it.lineAmount==null || it.lineAmount<0})return false
            val sum=runCatching {before.lines.fold(0L) {sum,row->Math.addExact(sum,row.lineAmount!!)}}.getOrNull() ?: return false
            return quantity==before.lines.sumOf {it.quantity!!} && amount==sum && before.subtotal==sum
        }
    }
}
