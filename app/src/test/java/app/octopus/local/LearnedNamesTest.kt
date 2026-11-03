package app.octopus.local

import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class LearnedNamesTest {
    // Saving uses org.json, which local unit tests only stub; the device run read the saved file back.
    @Test fun answersAreKeptPerRestaurantAndMeal() {
        val file = Files.createTempDirectory("learned").resolve("learned-names.json").toFile()
        val again = LearnedNames(file).apply {
            rememberKeetaMeal("هارديز", "كومبو بيج ديلوكس برجر - وسط", "بيج ديلوكس وجبة")
            rememberChoice("هارديز", "كومبو بيج ديلوكس برجر - وسط", "وسط", null)
            rememberChoice("هارديز", "كومبو بيج ديلوكس برجر - وسط", "بيبسي علبة", "بيبسي")
            rememberIgnored("هارديز", "كومبو بيج ديلوكس برجر - وسط", listOf("بدون اضافات"))
        }
        assertEquals("بيج ديلوكس وجبة", again.keetaMeal("هارديز", "كومبو بيج ديلوكس برجر - وسط"))
        assertNull(again.keetaMeal("كنتاكي", "كومبو بيج ديلوكس برجر - وسط"))
        val c = again.choices("هارديز", "كومبو بيج ديلوكس برجر - وسط")
        assertEquals(setOf("وسط"), c.extras); assertEquals(mapOf("بيبسي علبة" to "بيبسي"), c.aliases); assertEquals(setOf("بدون اضافات"), c.ignored)
    }

    @Test fun aKeetaRowReadsWithTheUsersMatches() {
        val wanted = setOf("بيج ديلوكس برجر", "بطاطس وسط", "بيبسي", "بدون اضافات")
        val learned = LearnedNames.Choices(extras = setOf("وسط"), aliases = mapOf("بيبسي علبة" to "بيبسي"), ignored = setOf("بدون اضافات"))
        val menu = KeetaMenuReader("هارديز", "كومبو بيج ديلوكس برجر - وسط", "3.13.301", wanted, learned = learned, keetaTitle = "بيج ديلوكس وجبة")
        val read = menu.configuredChoices("بيج ديلوكس وجبة، بيج ديلوكس برجر، وسط، بطاطس وسط، بيبسي علبة")!!.map { it.value }.toSet()
        assertEquals(wanted, read)
        assertNull(menu.configuredChoices("بيج ديلوكس وجبة، بيج ديلوكس برجر، كبير، بطاطس وسط، بيبسي علبة"))
    }

    @Test fun closestKeetaMealsShareWords() {
        val seen = listOf("بيج ديلوكس وجبة", "سوبر ستار برجر وجبة - وسط", "صندوق لاما", "ديلوكس رابتور بوكس")
        val best = MealCandidates.closest("كومبو بيج ديلوكس برجر - وسط", seen)
        assertEquals("بيج ديلوكس وجبة", best.first()); assertFalse("صندوق لاما" in best)
    }
}
