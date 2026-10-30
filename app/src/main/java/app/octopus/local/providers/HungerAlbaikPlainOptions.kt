package app.octopus.local.providers

import app.octopus.local.domain.ConfiguredProduct
import app.octopus.local.domain.ProviderId

/** Recorded unchecked control shapes, including free choices whose price cannot prove selection. */
object HungerAlbaikPlainOptions {
    private val controls = mapOf("مع الجبنة" to "android.widget.ImageView", "بدون مخلل" to "android.widget.ImageView",
        "بدون سلطة" to "android.view.View", "بدون صلصة" to "android.view.View")
    private val burgerFree = mapOf("بدون مخلل حار" to "android.widget.ImageView", "بدون مخلل" to "android.view.View", "بدون صلصة" to "android.view.View")
    private fun burger(details:ConfiguredProduct):Boolean = details.restaurant=="البيك" && details.product.title=="برجر فيليه دجاج حرّاق" &&
        details.source.provider==ProviderId.HUNGERSTATION && details.source.providerVersion==HungerStationCatalog.TESTED_VERSION && details.optionsComplete &&
        details.product.groups.map{it.title}.toSet()==setOf("اختر من اضافات-مبرده إلى برجر دجاج حراق","اختر من اضافات- من غير صوص إلى برجر دجاج حراق","اختر من اضافات-صوص إلى برجر دجاج حراق") &&
        details.product.groups.all{it.minimum==0 && it.maximum==(if(it.title.contains("من غير صوص"))2 else 1)} &&
        details.product.groups.flatMap{it.choices}.map{it.title}.toSet()==burgerFree.keys+setOf("جبنة شيدر 1 قطعة","جبنة شيدر مع الهالبينو")
    fun supports(details: ConfiguredProduct): Boolean = burger(details) || bigBaik(details)
    private fun bigBaik(details: ConfiguredProduct): Boolean = details.restaurant == "البيك" && details.product.title == "بيج بيك" &&
        details.source.provider == ProviderId.HUNGERSTATION && details.source.providerVersion == HungerStationCatalog.TESTED_VERSION &&
        details.optionsComplete && details.product.groups.size == 3 && details.product.groups.all { group ->
            group.minimum == 0 && group.maximum == if(group.title == "اختر من اضافات- من غير صوص إلى بيج بيك") 2 else 1
        } && details.product.groups.map {it.title}.toSet() == setOf("اختر من اضافات-مبرده إلى بيج بيك",
            "اختر من اضافات- من غير صوص إلى بيج بيك", "اختر من اضافات-صوص إلى بيج بيك") &&
        details.product.groups.flatMap {it.choices}.map {it.title}.toSet() == controls.keys

    fun verified(frame: CatalogFrame, details: ConfiguredProduct, now: Long): Boolean {
        if (!supports(details) || !details.source.fresh(now,120_000) || !frame.complete ||
            HungerStationCatalog.screen(frame) != CatalogScreen.PRODUCT || frame.nodes.any {it.editable || it.enteredText || it.label.trim()=="مطلوب"} ||
            frame.nodes.count {it.label.trim()=="غالبًا ما يتم طلبه مع"} != 1) return false
        // Burger's paid cheese rows may scroll off-screen. consumeAdd separately requires the exact base total;
        // all free choices must still be visibly unchecked because their price cannot prove omission.
        val expected=if(burger(details))burgerFree else controls
        return expected.all { (name,type) ->
            val row=frame.nodes.singleOrNull {it.clickable && it.label.lineSequence().first().trim()==name} ?: return@all false
            row.enabled && row.className==type && frame.descendants(row.index).size==1
        }
    }
}
