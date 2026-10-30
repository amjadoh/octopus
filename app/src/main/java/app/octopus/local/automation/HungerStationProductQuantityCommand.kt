package app.octopus.local.automation

import app.octopus.local.providers.*
import app.octopus.local.domain.IdentityText
import app.octopus.local.pricing.MoneyParser

/** Changes only the unsaved PDP quantity, never a cart row or an add/checkout button. */
class HungerStationProductQuantityCommand private constructor(
    val before:Int, val expectedAfter:Int, val target:CatalogNode,
    private val title:String, private val unit:Long, private val issuedAt:Long
) {
    private var consumed=false
    fun consume(frame:CatalogFrame,node:CatalogNode,version:String?,now:Long):Boolean {
        if(consumed || version!=HungerStationCatalog.TESTED_VERSION || now<issuedAt || now-issuedAt>15000 ||
            observed(frame,title,unit)!=before || target(frame,title,expectedAfter>before)!=node || node!=target)return false
        consumed=true; return true
    }
    companion object {
        fun observed(frame:CatalogFrame,title:String,unit:Long):Int? {
            if(!frame.complete || unit<=0 || unit>Long.MAX_VALUE/99 || frame.nodes.any{it.editable} ||
                HungerStationCatalog.screen(frame)!=CatalogScreen.PRODUCT ||
                frame.nodes.count{!it.clickable && IdentityText.clean(it.label)==IdentityText.clean(title)}!=1)return null
            val add=frame.nodes.singleOrNull{it.id=="flutterAddButton" && it.enabled && it.clickable &&
                it.className=="android.widget.Button" && HungerStationProductProgram.isAddLabel(it.label)} ?: return null
            val counter=frame.nodes.singleOrNull{it.index==add.parent && !it.clickable && it.className=="android.view.View"} ?: return null
            val value=counter.label.trim().map {if(it in '٠'..'٩')('0'.code+it.code-'٠'.code).toChar() else it}.joinToString("")
            val count=value.takeIf{it.matches(Regex("[0-9]{1,2}"))}?.toIntOrNull()?.takeIf{it in 1..99} ?: return null
            val normalized=add.label.replace(Regex("[\\u200e\\u200f\\u061c\\u202a-\\u202e\\u2066-\\u2069]"),"")
            if(MoneyParser.parse(normalized.lineSequence().last().trim())!=unit*count)return null
            return count
        }
        private fun target(frame:CatalogFrame,title:String,increase:Boolean):CatalogNode? {
            val add=frame.nodes.singleOrNull{it.id=="flutterAddButton"} ?: return null
            return frame.nodes.singleOrNull{it.parent==add.parent &&
                it.id==(if(increase)"quantityStepperIncrease" else "quantityStepperDecrease") &&
                it.className=="android.widget.ImageView" && it.clickable && it.enabled && !it.editable && it.label.isEmpty()}
        }
        fun next(frame:CatalogFrame,title:String,unit:Long,desired:Int,version:String?,now:Long):HungerStationProductQuantityCommand? {
            if(version!=HungerStationCatalog.TESTED_VERSION || desired !in 1..99 || now<0)return null
            val before=observed(frame,title,unit) ?: return null
            if(before==desired)return null
            val target=target(frame,title,desired>before) ?: return null
            return HungerStationProductQuantityCommand(before,before+if(desired>before)1 else -1,target,title,unit,now)
        }
    }
}
