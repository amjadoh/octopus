package app.octopus.local

import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test

class ConfiguredSourceSchemaTest {
    @Test fun clippedLimitRequiresAnAlreadyObservedChoiceBeforeResumingItsGroup() {
        fun frame(labels: List<Pair<String,Boolean>>) = CatalogFrame(labels.mapIndexed { i,(label,click) ->
            CatalogNode(i,-1,"",label,"android.view.View",click,false,false)
        } + CatalogNode(100,-1,"pdpCloseButton","","android.widget.Button",true,false,false),true)
        fun reader() = HungerStationProductReader(LiveRestaurant("مطعم تجريبي",null,"مطعم تجريبي"), LiveMenuItem("وجبة تجريبية",2000,"test"))
        val first = frame(listOf("وجبة تجريبية" to false,"المشروب" to false,"مطلوب" to false,"اختيار واحد" to false,"مشروب أ\n1\nسعرة" to true))
        val next = frame(listOf("وجبة تجريبية" to false,"اختيار واحد" to false,"مشروب أ\n1\nسعرة" to true,"مشروب ب\n2\nسعرة" to true,"غالبًا ما يتم طلبه مع" to false))
        val session=reader(); session.observe(first); session.observe(next)
        assertTrue(session.finish(2000).optionsComplete)
        assertEquals(2,session.finish(2000).product.groups.single().choices.size)
        val unknown=reader(); unknown.observe(next)
        assertFalse(unknown.finish(2000).optionsComplete)
    }
    @Test fun recognizesVariantCalorieRowsAndExplicitOptionalLimitWithoutConfusingGroupAndProductHeadings() {
        val labels = listOf(
            "وجبة تجريبية" to false,
            "اختر العنصر" to false, "مطلوب" to false, "اختيار واحد" to false,
            "وجبة تجريبية - وسط" to true, "وجبة تجريبية - كبير" to true,
            "وجبة تجريبية" to false, "مطلوب" to false, "اختيار واحد" to false,
            "صنف أ\n120\nسعرة" to true, "صنف ب\n10+ طلب" to true,
            "اضافة" to false, "ما يصل إلى 3 خيارات" to false,
            "إضافة أ\n1\nسعرة" to true, "إضافة ب\n2\nسعرة" to true, "إضافة ج\n3\nسعرة" to true,
            "غالبًا ما يتم طلبه مع" to false)
        val nodes = labels.mapIndexed { i, (label, click) -> CatalogNode(i,-1,"",label,"android.view.View",click,false,false) } +
            CatalogNode(100,-1,"pdpCloseButton","","android.widget.Button",true,false,false)
        val reader = HungerStationProductReader(LiveRestaurant("مطعم تجريبي",null,"مطعم تجريبي"),
            LiveMenuItem("وجبة تجريبية", 2000, "test"))
        reader.observe(CatalogFrame(nodes,true))
        val result = reader.finish(2000)
        assertTrue(result.optionsComplete)
        assertEquals(3,result.product.groups.size)
        assertEquals(0,result.product.groups.last().minimum)
        assertEquals(3,result.product.groups.last().maximum)
    }
}
