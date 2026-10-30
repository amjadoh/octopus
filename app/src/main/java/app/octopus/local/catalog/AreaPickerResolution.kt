package app.octopus.local.catalog

/** GPS names are hints; only a unique match in the chosen city's directory can be saved. */
data class AreaPickerResolution(val city: AreaEntry?, val district: AreaEntry?, val area: CatalogArea?, val message: String?) {
    companion object {
        fun resolve(cities: List<AreaEntry>, districts: List<AreaEntry>, cityText: String, districtText: String): AreaPickerResolution {
            val city = AreaNameSearch.exact(cities, cityText)
                ?: return AreaPickerResolution(null, null, null,
                    if (cities.isEmpty()) "أسماء المدن غير متاحة؛ أعد تحميلها لإكمال حفظ الموقع."
                    else "اختر المدينة المطابقة من الاقتراحات لإكمال حفظ الموقع.")
            val district = AreaNameSearch.exact(districts, districtText)
                ?: return AreaPickerResolution(city, null, null, when {
                    districts.isEmpty() -> "أحياء المدينة غير متاحة؛ أعد تحميلها لإكمال حفظ الموقع."
                    districtText.isBlank() -> "اختر الحي من القائمة لإكمال حفظ الموقع."
                    else -> "لم أتمكن من مطابقة اسم الحي مع دليل المطاعم. اختره من الأحياء المتاحة أو أعد تحميلها."
                })
            return AreaPickerResolution(city, district, CatalogArea(city.slug, district.slug), null)
        }
    }
}
