package app.octopus.local.domain

import app.octopus.local.providers.PlainMenuReadPlan

/** Admission for actual two-row executors. Empty/foreign carts still fail closed at runtime. */
object PlainComparisonSupport {
    fun existingTwoRow(cart:CanonicalCart):Boolean =
        PlainMenuReadPlan.create(cart,ProviderId.HUNGERSTATION,"admission")?.lines?.size==2
}
