package app.octopus.local.automation

import app.octopus.local.domain.*
import app.octopus.local.providers.*

/** Owns one first-line Add from an observed empty menu, without shrinking the full request.
 * An empty menu is not fabricated BasketEvidence. Only a complete post-Add reader can acknowledge it. */
class PlainBasketSeed private constructor(
    private val request:CanonicalCart, val line:CanonicalItem, val provider:ProviderId,
    private val version:String, private val unit:Long, val initialQuantity:Int, private val at:Long,
) {
    private var consumed=false
    private var finished=false
    fun consumeAdd(current:CanonicalCart,observedVersion:String?,now:Long):Boolean {
        if(consumed || finished || !context(current,observedVersion,now))return false
        consumed=true
        return true
    }
    fun acknowledge(current:CanonicalCart,actual:BasketEvidence,observedVersion:String?,now:Long):Boolean {
        if(finished)return false
        finished=true
        if(!consumed || !context(current,observedVersion,now) || actual.provider!=provider ||
            actual.restaurant!=request.restaurant || !actual.complete || actual.lines.size!=1)return false
        val amount=runCatching {Math.multiplyExact(unit,initialQuantity.toLong())}.getOrNull() ?: return false
        return actual.lines.single()==BasketLineEvidence(line.name,emptyList(),initialQuantity,amount) && actual.subtotal==amount
    }
    private fun context(current:CanonicalCart,observedVersion:String?,now:Long)=
        current.fingerprint==request.fingerprint && observedVersion==version && now>=at && now-at<=120_000
    companion object {
        fun create(request:CanonicalCart,line:CanonicalItem,provider:ProviderId,version:String?,unit:Long,
            emptyMenu:CatalogFrame,now:Long):PlainBasketSeed? {
            val plan=PlainMenuReadPlan.create(request,provider,version.orEmpty()) ?: return null
            if(plan.lines.size !in 1..2 || line !in plan.lines || unit<0 || now<0 || !emptyMenu.complete)return null
            val qty=when(provider) {
                ProviderId.HUNGERSTATION -> {
                    if(version!=HungerStationCatalog.TESTED_VERSION || HungerStationCatalog.screen(emptyMenu)!=CatalogScreen.MENU ||
                        emptyMenu.nodes.none {!it.clickable && it.label.lineSequence().any {v->IdentityText.clean(v)==IdentityText.clean(request.restaurant)}} ||
                        emptyMenu.nodes.any {it.id in setOf("FlutterMenu_goToCartButton","FlutterMenu_goToCartQuantity","menuItemActionDualButtonValue")})return null
                    line.quantity
                }
                ProviderId.KEETA -> {
                    val profile=KeetaVersionProfile.resolve(version) ?: return null
                    if(!profile.allowsOwnedPlainPreparation || !profile.allowsPreparation(plan.lines.size>1) ||
                        !KeetaBasketReader(KeetaMenuReader(request.restaurant,line.name,profile.version),plan.lines.map {it.name}.toSet()).emptyMenu(emptyMenu))return null
                    1
                }
            }
            if(qty !in 1..99 || runCatching {Math.multiplyExact(unit,qty.toLong())}.isFailure)return null
            return PlainBasketSeed(request,line,provider,version!!,unit,qty,now)
        }
    }
}
