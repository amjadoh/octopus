package app.octopus.local.automation

import app.octopus.local.domain.ConfiguredProduct
import app.octopus.local.providers.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class ProductReadResult(val details: ConfiguredProduct, val lastFrame: CatalogFrame)

/** Shared by browsing, comparison and preparation; none may scroll past the options end. */
class HungerStationProductReadSession(private val maxPages: Int = 30) {
    init { require(maxPages in 1..50) }

    suspend fun read(
        restaurant: LiveRestaurant,
        item: LiveMenuItem,
        frame: suspend () -> CatalogFrame,
        scroll: suspend (CatalogFrame) -> Boolean,
        now: () -> Long = System::currentTimeMillis,
    ): ProductReadResult {
        val reader = HungerStationProductReader(restaurant, item)
        var previous: CatalogFrame? = null
        var last: CatalogFrame? = null
        var startedAtTop = false
        for (page in 0 until maxPages) {
            currentCoroutineContext().ensureActive()
            val current = frame()
            currentCoroutineContext().ensureActive()
            reader.observe(current)
            if (page == 0) startedAtTop = current.nodes.any { it.scrollable && it.className == "android.widget.ScrollView" &&
                it.scrollBackward == false && it.scrollForward == true }
            if (startedAtTop && page > 0) reader.observeEditingEnd(current)
            last = current
            if (reader.optionsEndObserved || current == previous || page == maxPages - 1) break
            previous = current
            currentCoroutineContext().ensureActive()
            if (!scroll(current)) break
        }
        return ProductReadResult(reader.finish(now()), checkNotNull(last))
    }
}
