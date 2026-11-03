package app.octopus.local

import app.octopus.local.providers.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** S22 / HS 8.0.304, Hardee's combo edit sheet: "الاختيار من الاضافات" says "ما يصل إلى 15 خيار" over 7 rows. */
class HungerStationOptionalLimitTest {
    @Test fun optionalLimitAboveItsRowsStillCompletesTheProduct() {
        val type = object : com.google.gson.reflect.TypeToken<List<CatalogFrame>>() {}.type
        val text = javaClass.classLoader!!.getResourceAsStream("recorded-hs/hs-hardees-combo-optional-limit.json")!!.reader(Charsets.UTF_8).readText()
        val frames: List<CatalogFrame> = com.google.gson.Gson().fromJson(text, type)
        val reader = HungerStationProductReader(LiveRestaurant("هارديز", null, "هارديز"), LiveMenuItem("كومبو بيج ديلوكس برجر", null, "cart-product"))
        frames.forEach(reader::observe)
        val product = reader.finish(System.currentTimeMillis())
        assertTrue(product.optionsComplete)
        val extras = product.product.groups.single { it.title == "الاختيار من الاضافات" }
        assertEquals(0, extras.minimum); assertEquals(7, extras.maximum); assertEquals(7, extras.choices.size)
        assertEquals(listOf(1, 1, 1, 1, 1), product.product.groups.filter { it.minimum > 0 }.map { it.maximum })
    }
}
