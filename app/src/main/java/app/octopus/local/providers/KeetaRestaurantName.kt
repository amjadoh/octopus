package app.octopus.local.providers

import app.octopus.local.domain.IdentityText

/** The observed delivery-promotion suffix is display copy, not a branch or a verified fee. */
object KeetaRestaurantName {
    private val observedSuffix = Regex("\\s*\\(\\s*توصيل مجاني[\\s\\p{So}\\p{Sk}\\uFE0F\\u200D]*\\)\\s*$")
    fun matches(expected: String, displayed: String): Boolean {
        if (expected.isBlank() || displayed.length !in 1..300) return false
        if (IdentityText.clean(expected) == IdentityText.clean(displayed)) return true
        val suffix = observedSuffix.find(displayed) ?: return false
        return IdentityText.clean(displayed.substring(0, suffix.range.first)) == IdentityText.clean(expected)
    }
}
