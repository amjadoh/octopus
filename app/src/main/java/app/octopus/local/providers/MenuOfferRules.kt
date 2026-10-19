package app.octopus.local.providers

import app.octopus.local.domain.ProviderId
import app.octopus.local.pricing.*

/** Only the observed promotion formats are admitted. Unknown terms never imply a saving. */
object MenuOfferRules {
    private fun clean(raw: String) = raw.replace(Regex("[\\u200e\\u200f\\u061c\\u202a-\\u202e\\u2066-\\u2069]"), "")
        .replace('٪','%').replace(Regex("[\\s\\u00a0]+"), " ").trim()
    private val amount = "([0-9٠-٩]+(?:[.٫][0-9٠-٩]{1,2})?)"
    fun fromFrame(provider: ProviderId, frame: CatalogFrame): List<OfferRule> {
        val result = mutableListOf<OfferRule>()
        if (provider == ProviderId.HUNGERSTATION && HungerStationCatalog.screen(frame) == CatalogScreen.MENU) {
            frame.nodes.forEach { node ->
                val text = clean(node.label)
                val match = Regex("^تطبيق تلقائي (?:[§﷼] )?$amount(?: [§﷼])? رسوم التوصيل الحد الأدنى[:：]? (?:[§﷼] )?$amount(?: [§﷼])?$").matchEntire(text)
                if (match != null) {
                    val price = MoneyParser.parse(match.groupValues[1])
                    val minimum = MoneyParser.parse(match.groupValues[2])
                    if (price != null && minimum != null) result += OfferRule("hs-delivery-$price-$minimum", text,
                        OfferTarget.DELIVERY, minimum, deliveryPrice=price)
                }
            }
        }
        if (provider == ProviderId.KEETA) {
            frame.nodes.filter { clean(it.label) == "توصيل مجاني" }.forEach { title ->
                var parent = frame.nodes.firstOrNull { it.index == title.parent }
                repeat(5) {
                    val group = parent ?: return@repeat
                    val descendants = frame.descendants(group.index)
                    val minimums = descendants.mapNotNull { child ->
                        Regex("^الحد الأدنى $amount (?:﷼|ريال)$").matchEntire(clean(child.label))?.groupValues?.get(1)?.let(MoneyParser::parse)
                    }.distinct()
                    if (descendants.size <= 30 && minimums.size == 1 && descendants.count { clean(it.label)=="توصيل مجاني" } == 1) {
                        val minimum = minimums.single()
                        result += OfferRule("keeta-free-$minimum", "توصيل مجاني • الحد الأدنى ${minimum / 100} ريال",
                            OfferTarget.DELIVERY, minimum, deliveryPrice=0,
                            termsComplete=descendants.none { Regex("اشتراك|مشترك|عضوية|قسيمة|كوبون|أول طلب|الجدد").containsMatchIn(it.label) })
                        parent = null
                    } else parent = frame.nodes.firstOrNull { it.index == group.parent }
                }
            }
            frame.nodes.forEach { node ->
                val text=clean(node.label)
                val match=Regex("^خصم $amount% من $amount (?:﷼|ريال)$").matchEntire(text)
                if (match != null) {
                    val percent=MoneyParser.parse(match.groupValues[1])?.toInt()
                    val minimum=MoneyParser.parse(match.groupValues[2])
                    if (percent != null && percent in 1..10000 && minimum != null) result += OfferRule(
                        "keeta-percent-$percent-$minimum",text,OfferTarget.ITEMS,minimum,
                        percentBasisPoints=percent,termsComplete=false,requiresCoupon=true)
                }
            }
        }
        return result.distinctBy { it.id }.take(30)
    }
}
