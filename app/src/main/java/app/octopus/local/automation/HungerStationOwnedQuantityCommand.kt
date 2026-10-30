package app.octopus.local.automation

import app.octopus.local.domain.*
import app.octopus.local.providers.*

/** One row edit in a completely observed owned basket. Afterward only Back is admitted;
 * a new menu-to-cart completeness proof is required before acknowledging the edit. */
class HungerStationOwnedQuantityCommand private constructor(
    private val source:CatalogFrame, private val before:BasketEvidence,
    private val request:CanonicalCart, private val owner:PlainBasketPreparationSession,
    private val edit:PlainBasketPreparationSession.Edit, val target:CatalogNode,
    private val at:Long,
) {
    private var dispatched=false
    private var returned=false
    private var variantIssued=false
    private var issuedVariant:HungerStationVariantQuantityCommand?=null
    private var variantDispatchConsumed=false
    private val expectedQuantity=edit.before.getValue(edit.line.name)+
        if(edit.kind==PlainBasketPreparationSession.Kind.INCREASE_ONE)1 else -1

    fun consume(frame:CatalogFrame,node:CatalogNode,current:CanonicalCart,version:String?,now:Long):Boolean {
        if(dispatched || frame!=source || node!=target || !context(current,version,now) ||
            !owner.consume(edit,current,version!!,now))return false
        dispatched=true
        return true
    }
    /** A candidate transition is not upgraded to complete evidence. */
    fun afterMatches(frame:CatalogFrame,current:CanonicalCart,version:String?,now:Long):Boolean {
        if(!dispatched || returned || !context(current,version,now) || !frame.complete ||
            HungerStationCatalog.screen(frame)!=CatalogScreen.CART)return false
        val after=BasketEvidenceReader.hungerStation(frame,false)
        if(after.restaurant!=before.restaurant || after.lines.size!=before.lines.size ||
            after.lines.map {it.name}.toSet()!=before.lines.map {it.name}.toSet())return false
        val changed=before.lines.single {it.name==edit.line.name}
        val unit=changed.lineAmount!!/changed.quantity!!
        val amount=runCatching {Math.multiplyExact(unit,expectedQuantity.toLong())}.getOrNull() ?: return false
        val expected=before.lines.map {if(it.name==edit.line.name)it.copy(quantity=expectedQuantity,lineAmount=amount) else it}
        val sum=runCatching {expected.fold(0L) {a,row->Math.addExact(a,row.lineAmount!!)}}.getOrNull() ?: return false
        return after.lines.associateBy {it.name}==expected.associateBy {it.name} && after.subtotal==sum
    }
    fun consumeReturn(frame:CatalogFrame,current:CanonicalCart,version:String?,now:Long):Boolean {
        if(!afterMatches(frame,current,version,now))return false
        returned=true
        return true
    }
    fun pendingVariant(frame:CatalogFrame,current:CanonicalCart,version:String?,now:Long):Boolean {
        if(!dispatched || returned || variantIssued || !context(current,version,now))return false
        val row=before.lines.single{it.name==edit.line.name}
        val read=HungerStationExistingVariant.read(frame,edit.line.name) ?: return false
        return read.quantity==row.quantity && read.unit==row.lineAmount!!/row.quantity!!
    }
    fun issueVariant(frame:CatalogFrame,current:CanonicalCart,version:String?,now:Long):HungerStationVariantQuantityCommand? {
        if(!pendingVariant(frame,current,version,now))return null
        val row=before.lines.single{it.name==edit.line.name}
        val command=HungerStationVariantQuantityCommand.next(frame,edit.line.name,row.lineAmount!!/row.quantity!!,expectedQuantity,version,now) ?: return null
        variantIssued=true
        issuedVariant=command
        return command
    }
    /** This is the same journaled quantity edit, not authorization for a second basket mutation. */
    fun consumeVariantContinuation(frame:CatalogFrame,command:HungerStationVariantQuantityCommand?,current:CanonicalCart,version:String?,now:Long):Boolean {
        if(command==null || command!==issuedVariant || variantDispatchConsumed || !dispatched || returned || !context(current,version,now))return false
        val row=before.lines.single{it.name==edit.line.name}
        val read=HungerStationExistingVariant.read(frame,edit.line.name) ?: return false
        if(read.quantity!=row.quantity || read.unit!=row.lineAmount!!/row.quantity!!)return false
        variantDispatchConsumed=true
        return true
    }
    fun updatedVariant(frame:CatalogFrame,current:CanonicalCart,version:String?,now:Long):Boolean {
        if(!variantIssued || returned || !context(current,version,now))return false
        val row=before.lines.single{it.name==edit.line.name}
        val read=HungerStationExistingVariant.read(frame,edit.line.name) ?: return false
        return read.quantity==expectedQuantity && read.unit==row.lineAmount!!/row.quantity!!
    }
    private fun context(current:CanonicalCart,version:String?,now:Long)=
        current.fingerprint==request.fingerprint && version==HungerStationCatalog.TESTED_VERSION && now>=at && now-at<=30_000

    companion object {
        fun create(frame:CatalogFrame,before:BasketEvidence,request:CanonicalCart,
            owner:PlainBasketPreparationSession,edit:PlainBasketPreparationSession.Edit,version:String?,now:Long):HungerStationOwnedQuantityCommand? {
            if(version!=HungerStationCatalog.TESTED_VERSION || now<0 || !frame.complete ||
                HungerStationCatalog.screen(frame)!=CatalogScreen.CART || !before.complete ||
                before.provider!=ProviderId.HUNGERSTATION || before.restaurant!=request.restaurant ||
                BasketEvidenceReader.hungerStation(frame,true)!=before ||
                edit.kind==PlainBasketPreparationSession.Kind.ADD_ONE)return null
            if(before.lines.map {it.name}.distinct().size!=before.lines.size || before.lines.any {
                it.quantity !in 1..99 || it.choices.isNotEmpty() || it.lineAmount==null || it.lineAmount<0
            })return null
            val line=before.lines.singleOrNull {it.name==edit.line.name} ?: return null
            val qty=line.quantity!!
            if((edit.kind==PlainBasketPreparationSession.Kind.INCREASE_ONE && qty==99) ||
                (edit.kind==PlainBasketPreparationSession.Kind.DECREASE_ONE && qty==1) || line.lineAmount!!%qty!=0L)return null
            val sum=runCatching {before.lines.fold(0L) {a,row->Math.addExact(a,row.lineAmount!!)}}.getOrNull() ?: return null
            if(before.subtotal!=sum ||
                (owner.next(request,before,version,now) as? PlainBasketPreparationSession.Step.Change)?.edit!==edit)return null
            val rows=frame.nodes.filter {it.id=="FlutterCart_cartItem"}
            if(rows.size!=before.lines.size)return null
            val row=rows.singleOrNull {it.label.lineSequence().firstOrNull()?.trim()==edit.line.name} ?: return null
            val value=frame.descendants(row.index).singleOrNull {it.id=="menuItemActionDualButtonValue"} ?: return null
            val id=if(edit.kind==PlainBasketPreparationSession.Kind.INCREASE_ONE)"menuItemActionDualButtonAdd" else "menuItemActionDualButtonSubtract"
            val control=frame.nodes.singleOrNull {it.parent==value.index && it.id==id && it.className=="android.widget.Button" && it.enabled} ?: return null
            val target=frame.nodes.singleOrNull {it.parent==control.index && it.className=="android.widget.ImageView" &&
                it.clickable && it.enabled && it.label.isEmpty() && !it.editable} ?: return null
            return HungerStationOwnedQuantityCommand(frame,before,request,owner,edit,target,now)
        }
    }
}
