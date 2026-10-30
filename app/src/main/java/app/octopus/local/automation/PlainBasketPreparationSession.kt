package app.octopus.local.automation

import app.octopus.local.domain.*
import app.octopus.local.providers.*

/** Owns only exact plain-row content transitions. It never asserts address/branch equality. */
class PlainBasketPreparationSession private constructor(
    private val fingerprint: String,
    private val restaurant: String,
    private val provider: ProviderId,
    private val version: String,
    private val wanted: List<CanonicalItem>,
    initial: BasketEvidence,
) {
    enum class Kind { ADD_ONE, INCREASE_ONE, DECREASE_ONE }
    class Edit internal constructor(val line: CanonicalItem, val kind: Kind, internal val before: Map<String,Int>, internal val at: Long)
    sealed interface Step {
        data class Change(val edit: Edit): Step
        data object Ready: Step
        data object Blocked: Step
    }
    private var owned = quantities(initial)!!
    private var pending: Edit? = null
    private var attempted = false
    private var failed = false

    fun next(request: CanonicalCart, actual: BasketEvidence, observedVersion: String, now: Long): Step {
        if (failed || !context(request,actual,observedVersion) || quantities(actual)!=owned || now<0) return block()
        pending?.let { return if(attempted) block() else Step.Change(it) }
        val line=wanted.firstOrNull {owned[it.name] != it.quantity} ?: return Step.Ready
        val count=owned[line.name] ?: 0
        val kind=if(count==0)Kind.ADD_ONE else if(count<line.quantity)Kind.INCREASE_ONE else Kind.DECREASE_ONE
        return Step.Change(Edit(line,kind,owned.toMap(),now).also {pending=it})
    }
    /** Consume before dispatch; failure/timeout cannot reissue the action in this session. */
    fun consume(edit: Edit, request: CanonicalCart, observedVersion: String, now: Long): Boolean {
        if(failed || attempted || pending!==edit || request.fingerprint!=fingerprint || observedVersion!=version ||
            edit.before!=owned || now<edit.at || now-edit.at>120_000) { failed=true;return false }
        attempted=true
        return true
    }
    fun acknowledge(edit: Edit, request: CanonicalCart, actual: BasketEvidence, observedVersion: String, now: Long): Boolean {
        if(failed || !attempted || pending!==edit || !context(request,actual,observedVersion) ||
            now<edit.at || now-edit.at>120_000) {failed=true;return false}
        val count=edit.before[edit.line.name] ?: 0
        val after=when(edit.kind) {Kind.ADD_ONE->1;Kind.INCREASE_ONE->count+1;Kind.DECREASE_ONE->count-1}
        val expected=edit.before+(edit.line.name to after)
        if(after !in 1..99 || quantities(actual)!=expected) {failed=true;return false}
        owned=expected
        pending=null
        attempted=false
        return true
    }
    private fun block(): Step.Blocked {failed=true;return Step.Blocked}
    private fun context(request:CanonicalCart,actual:BasketEvidence,observedVersion:String)=
        request.fingerprint==fingerprint && observedVersion==version && actual.provider==provider &&
            actual.restaurant==restaurant && actual.complete && quantities(actual)?.keys?.all {name->wanted.any {it.name==name}}==true
    companion object {
        private fun quantities(actual:BasketEvidence):Map<String,Int>? {
            if(!actual.complete || actual.lines.isEmpty() || actual.lines.map {it.name}.distinct().size!=actual.lines.size ||
                actual.lines.any {it.quantity !in 1..99 || it.choices.isNotEmpty() || it.lineAmount==null || it.lineAmount<0} ||
                actual.subtotal == null || actual.subtotal < 0) return null
            val sum = runCatching { actual.lines.fold(0L) { total, row ->
                Math.addExact(total, row.lineAmount!!)
            } }.getOrNull() ?: return null
            if (sum != actual.subtotal) return null
            return actual.lines.associate {it.name to it.quantity!!}
        }
        fun create(request:CanonicalCart,actual:BasketEvidence,version:String):PlainBasketPreparationSession? {
            val plan=PlainMenuReadPlan.create(request,actual.provider,version) ?: return null
            if(actual.restaurant!=request.restaurant || quantities(actual)==null ||
                actual.lines.any {row->plan.lines.none {it.name==row.name}}) return null
            return PlainBasketPreparationSession(request.fingerprint,request.restaurant,actual.provider,version,plan.lines,actual)
        }
    }
}
