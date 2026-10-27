package app.octopus.local.providers

/** Rotating home promotions must not prevent a restaurant list from becoming ready. */
object CatalogStability {
    fun fingerprint(frame: CatalogFrame): String = when (HungerStationCatalog.screen(frame)) {
        CatalogScreen.HOME -> frame.nodes.filter { node ->
            listOf("new_search_card_view", "input_csc", "home_modules_parent").any { node.id.endsWith(":id/$it") }
        }.joinToString { "${it.id}:${it.parent}:${it.enabled}" } + ":entry=" + HungerStationCatalog.browseEntry(frame)?.index
        CatalogScreen.BROWSE -> frame.nodes.filter { it.id.endsWith(":id/vendor_name") }
            .joinToString { "${it.id}:${it.label}:${it.enabled}" }
        // A PDP may keep its text while controls finish loading or entered values change.
        // Every captured state field participates; an unchanged label is not stable evidence.
        CatalogScreen.PRODUCT -> frame.nodes.joinToString(prefix = "PRODUCT:complete=${frame.complete}:") { it.toString() }
        else -> frame.nodes.joinToString { it.id + it.label + it.parent }
    }
}
