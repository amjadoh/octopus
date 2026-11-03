package app.octopus.local

import app.octopus.local.providers.CatalogFrame
import app.octopus.local.providers.CatalogNode
import app.octopus.local.providers.KeetaForeignMenuRecovery
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeetaForeignDrawerTest {
    private val pkg = "com.sankuai.sailor.afooddelivery:id/"
    private fun n(i: Int, label: String = "", id: String = "", editable: Boolean = false) =
        CatalogNode(i, 0, if (id.isEmpty()) "" else pkg + id, label, "android.widget.TextView", false, editable, false)
    private fun drawer(vararg extra: CatalogNode) = CatalogFrame(listOf(n(0, id = "card_root"), n(1, "وجبة تويستر - وسط", "product_name"),
        n(2, "الأصناف المضافة"), n(3, "تفريغ السلة"), n(4, "إتمام الدفع")) + extra, true)

    @Test fun recognizesAnOpenCartDrawerOverAnotherMenu() =
        assertTrue(KeetaForeignMenuRecovery.drawerOverForeignMenu(drawer(), "3.13.301"))

    @Test fun refusesReviewInputOrAMissingDrawerMarker() {
        assertFalse(KeetaForeignMenuRecovery.drawerOverForeignMenu(drawer(n(5, "تأكيد الطلب")), "3.13.301"))
        assertFalse(KeetaForeignMenuRecovery.drawerOverForeignMenu(drawer(n(5, editable = true)), "3.13.301"))
        assertFalse(KeetaForeignMenuRecovery.drawerOverForeignMenu(CatalogFrame(drawer().nodes.filter { it.index != 3 }, true), "3.13.301"))
        assertFalse(KeetaForeignMenuRecovery.drawerOverForeignMenu(drawer().copy(complete = false), "3.13.301"))
    }
}
