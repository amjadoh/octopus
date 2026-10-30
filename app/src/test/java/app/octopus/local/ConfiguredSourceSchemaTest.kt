package app.octopus.local

import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test

class ConfiguredSourceSchemaTest {
    @Test fun existingCartEditRadioRowsNeedTheKnownBlankChildAndRequiredSingleChoiceHeader() {
        fun frame(withChild: Boolean) = CatalogFrame(listOf(
            CatalogNode(0,-1,"pdpCloseButton","","android.widget.Button",true,false,false),
            CatalogNode(1,-1,"","وجبة تجريبية","android.view.View",false,false,false),
            CatalogNode(2,-1,"","","android.widget.ScrollView",false,false,true),
            CatalogNode(3,2,"","الاختيار","android.view.View",false,false,false),
            CatalogNode(4,2,"","مطلوب","android.view.View",false,false,false),
            CatalogNode(5,2,"","اختيار واحد","android.view.View",false,false,false),
            CatalogNode(6,2,"","صنف أ","android.view.View",true,false,false),
            CatalogNode(8,2,"","غالبًا ما يتم طلبه مع","android.view.View",false,false,false),
            CatalogNode(9,-1,"flutterAddButton","تحديث\n§\n20","android.widget.Button",true,false,false)
        ) + if(withChild) listOf(CatalogNode(7,6,"","","android.view.View",true,false,false)) else emptyList(),true)
        fun read(frame: CatalogFrame): Boolean {
            val reader=HungerStationProductReader(LiveRestaurant("مطعم تجريبي",null,"مطعم تجريبي"),LiveMenuItem("وجبة تجريبية",null,"test"))
            reader.observe(frame);return reader.finish(1000).optionsComplete
        }
        assertTrue(read(frame(true)))
        assertFalse(read(frame(false)))
        assertFalse(read(frame(true).copy(nodes=frame(true).nodes.map { if(it.index==7) it.copy(label="غير معروف") else it })))
        val overlap=HungerStationProductReader(LiveRestaurant("مطعم تجريبي",null,"مطعم تجريبي"),LiveMenuItem("وجبة تجريبية",null,"test"))
        overlap.observe(frame(true).copy(nodes=frame(true).nodes.filter { it.index!=8 }))
        overlap.observe(frame(false).copy(nodes=frame(false).nodes.filter { it.index !in 3..5 }))
        assertTrue(overlap.finish(1000).optionsComplete)
    }
    @Test fun readsFourteenOptionalChoicesAcrossClippedPagesWithoutGuessingARequiredGroup() {
        fun frame(labels: List<Pair<String,Boolean>>) = CatalogFrame(labels.mapIndexed { i,(label,click) ->
            CatalogNode(i,-1,"",label,"android.view.View",click,false,false)
        } + listOf(CatalogNode(100,-1,"pdpCloseButton","","android.widget.Button",true,false,false),
            CatalogNode(101,-1,"flutterAddButton","تحديث","android.widget.Button",true,false,false)),true)
        val first = frame(listOf("وجبة تجريبية" to false,"اضافة" to false,"ما يصل إلى 14 خيار" to false) +
            (1..7).map { "إضافة $it\n1\nسعرة" to true } + listOf("الطبق الجانبي" to false,"مطلوب" to false))
        val second = frame(listOf("وجبة تجريبية" to false) + (7..14).map { "إضافة $it\n1\nسعرة" to true } +
            listOf("الطبق الجانبي" to false,"مطلوب" to false,"اختيار واحد" to false,"جانب أ\n1\nسعرة" to true,
                "غالبًا ما يتم طلبه مع" to false))
        val reader=HungerStationProductReader(LiveRestaurant("مطعم تجريبي",null,"مطعم تجريبي"), LiveMenuItem("وجبة تجريبية",null,"test"))
        reader.observe(first)
        assertFalse(reader.finish(1000).optionsComplete)
        reader.observe(second)
        val result=reader.finish(2000)
        assertTrue(result.optionsComplete)
        assertEquals(14,result.product.groups.first().choices.size)
        assertEquals(14,result.product.groups.first().maximum)
        assertEquals(1,result.product.groups.last().minimum)
    }
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
