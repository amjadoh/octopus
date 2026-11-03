package app.octopus.local.providers

import app.octopus.local.domain.IdentityText

/** The observed delivery-promotion suffix is display copy, not a branch or a verified fee. */
object KeetaRestaurantName {
    private val observedSuffix = Regex("\\s*\\(\\s*توصيل مجاني[\\s\\p{So}\\p{Sk}\\uFE0F\\u200D]*\\)\\s*$")
    private val separators = listOf(" - ", " | ", ": ")
    // A suffix is dropped only when it reads as a promotion; a district or branch name stays for Terra to judge.
    private val promotion = Regex("عرض|عروض|خصم|كوبون|قسيم|توصيل|مجان|جديد|وفر|%|٪|offer|deal|free|new", RegexOption.IGNORE_CASE)
    private val emoji = Regex("[\\p{So}\\p{Sk}]")
    private val pricedEnd = Regex("[0-9]+(?:\\.[0-9]+)?\\s*(?:ريال|﷼|ر\\.س)$")
    private val price = Regex("[0-9]+(?:\\.[0-9]+)?\\s*(?:ريال|﷼|ر\\.س)")
    private val branchWords = Regex("فرع|branch|الفرع", RegexOption.IGNORE_CASE)
    private val approved = java.util.Collections.synchronizedSet(mutableSetOf<Pair<String, String>>())

    /** Terra confirmed, for this app run only, that [displayed] names the same chain as [expected]. */
    fun approve(expected: String, displayed: String) {
        if (expected.isNotBlank() && displayed.length in 1..120 && '\n' !in displayed)
            approved += KeetaNameText.loose(expected) to KeetaNameText.loose(displayed)
    }

    /** Writing differences, or the chain name followed by a promotion title. A branch name is never stripped. */
    private fun looseMatch(expected: String, displayed: String): Boolean {
        val want = KeetaNameText.loose(expected)
        val shown = KeetaNameText.loose(displayed)
        if (want.isEmpty() || shown.isEmpty()) return false
        if (want == shown || (want to shown) in approved) return true
        val separated = separators.firstNotNullOfOrNull { sep -> shown.removePrefix(want).takeIf { shown.startsWith(want + sep) } }
        // S22 / Keeta 3.13.301: "هارديز جولد ستار رابتور 13 ريال" — the chain, a space, then a priced campaign.
        // With only a space, the rest must end in a campaign price; a district or branch name alone never matches.
        val rest = separated ?: shown.removePrefix(want).takeIf { shown.startsWith("$want ") && pricedEnd.containsMatchIn(it) }
            ?: return false
        if ('(' in rest || rest.length > 60 || branchWords.containsMatchIn(rest)) return false
        return promotion.containsMatchIn(rest) || price.containsMatchIn(rest) || emoji.containsMatchIn(displayed)
    }

    fun matches(expected: String, displayed: String): Boolean {
        if (expected.isBlank() || displayed.length !in 1..300) return false
        if (IdentityText.clean(expected) == IdentityText.clean(displayed)) return true
        if ('\n' !in displayed && looseMatch(expected, displayed)) return true
        // Exact campaign heading observed in the native menu, 2026-10-30.
        // Chain recognition only; never proof of delivery address or branch.
        if (IdentityText.clean(expected) == "كنتاكي" &&
            IdentityText.clean(displayed) in setOf("كنتاكي - مخلل كل شي 🥒", "كنتاكي - مخلل كل شي🥒")
                .map(IdentityText::clean)) return true
        // Exact campaign title observed on the actual Herfy menu on 2026-10-26.
        // This identifies the chain name only; it does not establish branch/address equality.
        if (IdentityText.clean(expected)=="هرفي" &&
            IdentityText.clean(displayed)=="هرفي🔥السوبر كامل ومكمل🔥") return true
        // Exact native alias, 2026-10-26; the chain's own https://linktr.ee/Brown_sa1
        // identifies Brown Coffee | براون كافيه. This never strips a branch suffix.
        if(IdentityText.clean(expected)=="براون كوفي" &&
            IdentityText.clean(displayed)=="براون كافيه") return true
        // Observed on Keeta 3.13.203, 2026-10-25. Remove this exact marketing
        // decoration only; never remove arbitrary suffixes or branch names.
        val couponName = Regex("^🔥\\s*(.+?)\\s*-\\s*كوبونات\\s*🔥$").matchEntire(displayed.trim())
        if (couponName != null) return IdentityText.clean(couponName.groupValues[1]) == IdentityText.clean(expected)
        val suffix = observedSuffix.find(displayed) ?: return false
        return IdentityText.clean(displayed.substring(0, suffix.range.first)) == IdentityText.clean(expected)
    }
}
