package app.octopus.local.automation

import app.octopus.local.providers.*
import app.octopus.local.pricing.MoneyParser

/** Exact observed existing-variant sheet; a new variant is never selected by this command. */
object HungerStationExistingVariant {
    data class Reading(val quantity:Int,val unit:Long,val close:CatalogNode,val counter:CatalogNode)
    private fun clean(s:String)=s.replace(Regex("[\\u200e\\u200f\\u061c\\u202a-\\u202e\\u2066-\\u2069]"),"").trim()
    fun read(frame:CatalogFrame,title:String):Reading? {
        if(!frame.complete || title.isBlank() || frame.nodes.any{it.editable})return null
        val close=frame.nodes.singleOrNull{it.id=="FlutterMenu_bottomSheetClose" && clean(it.label)=="إعادة آخر تعديل" &&
            it.className=="android.widget.ImageView" && it.clickable && it.enabled} ?: return null
        val body=frame.descendants(close.index)
        val row=body.singleOrNull { n -> n.clickable && n.enabled && n.className=="android.widget.ImageView" &&
            clean(n.label).lineSequence().take(4).toList()==listOf("لديك تعديل على هذا الصنف:",title,"لايوجد تعديلات أو إضافات","تعديل") } ?: return null
        val lines=clean(row.label).lines()
        if(lines.size!=6 || lines[4]!="§")return null
        val unit=MoneyParser.parse(lines[5])?.takeIf{it>0 && it<=Long.MAX_VALUE/99} ?: return null
        if(body.count{it.clickable && clean(it.label)=="صنف جديد للتعديل"}!=1)return null
        val counter=frame.descendants(row.index).singleOrNull{it.id=="menuItemActionDualButtonValue" &&
            !it.clickable && it.enabled && it.className=="android.view.View"} ?: return null
        val number=clean(counter.label).map{if(it in '٠'..'٩')('0'.code+it.code-'٠'.code).toChar()else it}.joinToString("")
        val quantity=number.takeIf{it.matches(Regex("[0-9]{1,2}"))}?.toIntOrNull()?.takeIf{it in 1..99} ?: return null
        if(body.any {it.scrollable || (it.label.isNotBlank() && it.index !in setOf(close.index,row.index,counter.index) && clean(it.label)!="صنف جديد للتعديل")})return null
        // No additional described variant may hide behind the selected row.
        if(body.count{clean(it.label).startsWith("لديك تعديل على هذا الصنف:")}!=1)return null
        return Reading(quantity,unit,close,counter)
    }
    fun target(frame:CatalogFrame,title:String,increase:Boolean):CatalogNode? {
        val reading=read(frame,title) ?: return null
        if((increase && reading.quantity==99)||(!increase && reading.quantity==1))return null
        val control=frame.nodes.singleOrNull {it.parent==reading.counter.index &&
            it.id==(if(increase)"menuItemActionDualButtonAdd" else "menuItemActionDualButtonSubtract") &&
            it.className=="android.widget.Button" && !it.clickable && it.enabled && it.label.isEmpty()} ?: return null
        return frame.nodes.singleOrNull{it.parent==control.index && it.className=="android.widget.ImageView" &&
            it.id.isEmpty() && it.label.isEmpty() && it.enabled && it.clickable}
    }
}

class HungerStationVariantQuantityCommand private constructor(val before:Int,val expectedAfter:Int,val target:CatalogNode,
    private val title:String,private val unit:Long,private val issuedAt:Long) {
    private var consumed=false
    fun consume(frame:CatalogFrame,node:CatalogNode,version:String?,now:Long):Boolean {
        val read=HungerStationExistingVariant.read(frame,title) ?: return false
        if(consumed || version!=HungerStationCatalog.TESTED_VERSION || now<issuedAt || now-issuedAt>15000 ||
            read.quantity!=before || read.unit!=unit || node!=target ||
            HungerStationExistingVariant.target(frame,title,expectedAfter>before)!=node)return false
        consumed=true;return true
    }
    companion object {
        fun next(frame:CatalogFrame,title:String,unit:Long,desired:Int,version:String?,now:Long):HungerStationVariantQuantityCommand? {
            if(version!=HungerStationCatalog.TESTED_VERSION || desired !in 1..99 || now<0)return null
            val read=HungerStationExistingVariant.read(frame,title) ?: return null
            if(read.unit!=unit || read.quantity==desired)return null
            val target=HungerStationExistingVariant.target(frame,title,desired>read.quantity) ?: return null
            return HungerStationVariantQuantityCommand(read.quantity,read.quantity+if(desired>read.quantity)1 else -1,target,title,unit,now)
        }
    }
}
