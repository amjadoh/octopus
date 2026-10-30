package app.octopus.local.automation

import app.octopus.local.domain.ProviderId
import app.octopus.local.providers.*
import java.util.concurrent.atomic.AtomicBoolean

/** Open the observed edit surface once; never press its Update/Add control. */
class HungerStationCartProductCommand private constructor(
    private val frame: CatalogFrame, val target: CatalogNode, private val at: Long,
) {
    private val used = AtomicBoolean(false)
    fun consume(current: CatalogFrame, node: CatalogNode, version: String?, now: Long): Boolean =
        version == HungerStationCatalog.TESTED_VERSION && now >= at && now - at <= 15_000 &&
            current == frame && node == target && used.compareAndSet(false, true)

    companion object {
        fun create(frame: CatalogFrame, source: BasketEvidence, now: Long): HungerStationCartProductCommand? {
            val row = source.lines.singleOrNull() ?: return null
            if (!frame.complete || !source.complete || source.provider != ProviderId.HUNGERSTATION ||
                row.quantity != 1 || row.choices.isEmpty() || frame.nodes.any { it.enteredText } ||
                HungerStationCatalog.screen(frame) != CatalogScreen.CART ||
                BasketEvidenceReader.hungerStation(frame, true) != source) return null
            val target = frame.nodes.singleOrNull { it.id == "FlutterCart_cartItem" } ?: return null
            if (!target.enabled || !target.clickable || target.editable || target.omittedText ||
                target.label.lineSequence().first().trim() != row.name ||
                target.label.lineSequence().none { it.trim() == "تعديل" }) return null
            return HungerStationCartProductCommand(frame, target, now)
        }
    }
}
