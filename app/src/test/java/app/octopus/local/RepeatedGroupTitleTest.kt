package app.octopus.local

import app.octopus.local.automation.SingleChoiceMeal
import app.octopus.local.domain.*
import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test

/** Labels recorded from the HS 8.0.303 KFC "زنجر ساندويتش" cart edit sheet on 2026-11-01. */
class RepeatedGroupTitleTest {
    private fun frame(rows: List<Pair<String, String>>) = CatalogFrame(buildList {
        add(CatalogNode(0,-1,"pdpCloseButton","","android.widget.ImageView",true,false,false))
        add(CatalogNode(1,-1,"","تمويه","android.view.View",true,false,false))
        add(CatalogNode(2,-1,"","زنجر ساندويتش","android.view.View",false,false,false))
        add(CatalogNode(3,-1,"","","android.widget.ScrollView",false,false,true))
        var next = 10
        rows.forEach { (kind, label) ->
            val index = next; next += 2
            when (kind) {
                "head" -> add(CatalogNode(index,3,"",label,"android.view.View",false,false,false))
                "radio" -> {
                    add(CatalogNode(index,3,"",label,"android.view.View",true,false,false))
                    add(CatalogNode(index+1,index,"","","android.view.View",true,false,false))
                }
                else -> add(CatalogNode(index,3,"",label,kind,true,false,false))
            }
        }
        add(CatalogNode(200,-1,"flutterAddButton","تحديث\n‪§\n26‬","android.widget.Button",true,false,false))
    }, true)
    private val top = frame(listOf("head" to "شطيرة", "head" to "مطلوب", "head" to "اختيار واحد",
        "android.widget.ImageView" to "زنجر ساندويتش\n613\nسعرة",
        "head" to "اختر التوابل التي تفضلها", "head" to "ما يصل إلى 4 خيارات",
        "android.view.View" to "دبل أمريكن تشيز\n‪(§\n5\n+)‬",
        "android.widget.ImageView" to "خس\n1\nسعرة"))
    private val middle = frame(listOf("android.widget.ImageView" to "خس\n1\nسعرة",
        "android.widget.ImageView" to "طماطم\n1\nسعرة", "android.view.View" to "مايونيز حار\n‪(§\n1\n+)‬",
        "head" to "إضافة على", "head" to "ما يصل إلى 3 خيارات",
        "android.widget.ImageView" to "قطعتين استريبس عادي\n312\nسعرة\n‪(§\n8\n+)‬"))
    private val bottom = frame(listOf("head" to "إضافة على", "head" to "ما يصل إلى 3 خيارات",
        "android.widget.ImageView" to "قطعتين استريبس عادي\n312\nسعرة\n‪(§\n8\n+)‬",
        "android.widget.ImageView" to "قطعتين استريبس حار\n320\nسعرة\n‪(§\n8\n+)‬",
        "android.view.View" to "كيك التمر\n‪(§\n9\n+)‬",
        "head" to "شطيرة", "head" to "مطلوب", "head" to "اختيار واحد", "radio" to "شطيرة",
        "head" to "غالبًا ما يتم طلبه مع"))

    private fun read(vararg frames: CatalogFrame): ConfiguredProduct {
        val reader = HungerStationProductReader(LiveRestaurant("كنتاكي",null,"كنتاكي"), LiveMenuItem("زنجر ساندويتش",null,"cart-product"))
        frames.forEach(reader::observe)
        return reader.finish(1000)
    }
    private fun basket(choices: String) = BasketEvidenceReader.hungerStation(CatalogFrame(listOf(
        CatalogNode(0,-1,"FlutterCart_title","السلة\nكنتاكي","android.view.View",false,false,false),
        CatalogNode(1,-1,"FlutterCart_scroll_view","المجموع\n26\n§","android.view.View",false,false,false),
        CatalogNode(2,1,"FlutterCart_cartItem","زنجر ساندويتش\n$choices\nتعديل\n26\n§","android.view.View",true,false,false),
        CatalogNode(3,2,"menuItemActionDualButtonValue","1","android.view.View",false,false,false)),true), true)

    @Test fun repeatedRequiredTitleIsTwoGroupsAndLimitedHeadingsAreOptional() {
        val product = read(top, middle, bottom)
        assertTrue(product.optionsComplete)
        val groups = product.product.groups
        assertEquals(listOf("شطيرة", "اختر التوابل التي تفضلها", "إضافة على", "شطيرة"), groups.map { it.title })
        assertEquals(groups.size, groups.map { it.id }.distinct().size)
        assertEquals(listOf(1, 0, 0, 1), groups.map { it.minimum })
        assertEquals(listOf(listOf("زنجر ساندويتش"), listOf("شطيرة")), groups.filter { it.minimum == 1 }.map { g -> g.choices.map { it.title } })
        assertEquals(4, groups[1].choices.size)
        assertEquals(3, groups[2].choices.size)
    }
    @Test fun cartLineResolvesBothRequiredChoicesAndRejectsSelectedAdditions() {
        val product = read(top, middle, bottom).let { it.copy(source = it.source.copy(capturedAtMillis = 1000)) }
        val meal = SingleChoiceMeal.create(basket("1x زنجر ساندويتش, شطيرة"), product, 1000)
        assertNotNull(meal)
        assertEquals(listOf("زنجر ساندويتش", "شطيرة"), meal!!.cart.items.single().options.map { it.value })
        assertNull(SingleChoiceMeal.create(basket("1x زنجر ساندويتش, خس, شطيرة"), product, 1000))
        assertNull(SingleChoiceMeal.create(basket("1x زنجر ساندويتش"), product, 1000))
    }
    @Test fun aMissingLastPageOrUnlimitedUnknownHeadingIsIncomplete() {
        assertFalse(read(top, middle).optionsComplete)
        val unknown = frame(listOf("head" to "عنوان جديد", "android.widget.ImageView" to "خيار\n1\nسعرة",
            "head" to "غالبًا ما يتم طلبه مع"))
        assertFalse(read(top, middle, bottom, unknown).optionsComplete)
    }
}
