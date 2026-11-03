package app.octopus.local

import app.octopus.local.providers.CatalogFrame
import app.octopus.local.providers.CatalogNode
import app.octopus.local.providers.HungerStationLocationPrompt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HungerStationLocationPromptTest {
    private fun node(i: Int, label: String, cls: String = "android.widget.TextView", clickable: Boolean = false, editable: Boolean = false) =
        CatalogNode(i, 0, "", label, cls, clickable, editable, false)
    private fun prompt(vararg extra: CatalogNode) = CatalogFrame(listOf(
        node(0, ""), node(1, HungerStationLocationPrompt.TITLE), node(2, "موقع التوصيل لا يطابق موقعك الحالي."),
        node(3, "تغيير الموقع", "android.widget.Button", true), node(4, "تأكيد الطلب للموقع الحالي", "android.widget.Button", true)) + extra, true)

    @Test fun recognizesTheDifferentLocationPrompt() = assertTrue(HungerStationLocationPrompt.shown(prompt()))

    @Test fun ignoresScreensWithoutBothButtonsOrWithInput() {
        assertFalse(HungerStationLocationPrompt.shown(CatalogFrame(prompt().nodes.filter { it.index != 4 }, true)))
        assertFalse(HungerStationLocationPrompt.shown(prompt(node(5, "", "android.widget.EditText", editable = true))))
        assertFalse(HungerStationLocationPrompt.shown(CatalogFrame(listOf(node(0, "السلة"), node(1, "تغيير الموقع")), true)))
    }
}
