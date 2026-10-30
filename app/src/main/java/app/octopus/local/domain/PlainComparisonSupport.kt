package app.octopus.local.domain

import app.octopus.local.providers.PlainMenuReadPlan

/** Admission for actual two-row executors. Unsupported cart shapes still fail closed at runtime. */
object PlainComparisonSupport {
    fun existingTwoRow(cart:CanonicalCart):Boolean =
        PlainMenuReadPlan.create(cart,ProviderId.HUNGERSTATION,"admission")?.lines?.size==2
}
