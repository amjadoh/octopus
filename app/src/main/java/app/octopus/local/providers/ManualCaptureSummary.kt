package app.octopus.local.providers

/** Counts this reading session only, independently of previously cached menus. */
class ManualCaptureSummary {
    private val restaurants = mutableSetOf<String>()
    private val meals = mutableSetOf<Pair<String, String>>()
    fun record(update: ManualCatalogUpdate) {
        restaurants += update.restaurants.map { it.name }
        update.menu?.let { menu ->
            restaurants += menu.restaurant.name
            meals += menu.items.map { menu.restaurant.name to it.selector }
        }
    }
    val message: String get() = when {
        meals.isNotEmpty() -> "حُفظت ${restaurants.size} مطاعم و${meals.size} وجبات من هذه القراءة؛ اختر منها داخل الأخطبوط."
        restaurants.isNotEmpty() -> "حُفظت أسماء ${restaurants.size} مطاعم فقط؛ لم تُقرأ قائمة وجبات في هذه الجلسة."
        else -> "لم تُحفظ مطاعم أو وجبات من هذه القراءة؛ لم يتعرف القارئ على قائمة قابلة للقراءة."
    }
}
