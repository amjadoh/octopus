package app.octopus.local.providers

import app.octopus.local.domain.IdentityText

/** A saved area never changes the origin of a selected restaurant or product. */
object CatalogSources {
    fun restaurants(public: List<LiveRestaurant>, manual: List<LiveRestaurant>): List<LiveRestaurant> =
        listOf(public, manual).flatMap { rows ->
            rows.groupBy { it.source to IdentityText.clean(it.name) }.values
                .filter { it.size == 1 }.map { it.single() }
        }.distinctBy { it.catalogKey }
}
