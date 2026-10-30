package app.octopus.local

import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test

class HungerStationHomePromoTest {
    private fun frame(label:String="إغلاق")=CatalogFrame(listOf(
        CatalogNode(0,-1,"com.hungerstation.android.web:id/new_search_card_view","","android.view.ViewGroup",true,false,false),
        CatalogNode(1,-1,"com.hungerstation.android.web:id/com_braze_inappmessage_html","","android.widget.RelativeLayout",true,false,false),
        CatalogNode(2,1,"","","android.webkit.WebView",false,false,false),
        CatalogNode(3,2,"","","android.webkit.WebView",false,false,false),
        CatalogNode(4,3,"","","android.view.View",false,false,false),
        CatalogNode(5,4,"",label,"android.widget.Button",true,false,false),
    ),true)
    @Test fun observedHomeAdvertAllowsOnlyItsExplicitCloseButton() {
        val f=frame()
        assertEquals(5,HungerStationSpinPromo.closeTarget(f)!!.index)
        assertEquals(CatalogScreen.SPIN_PROMO,HungerStationCatalog.screen(f))
        assertNull(HungerStationSpinPromo.closeTarget(f.copy(complete=false)))
    }
    @Test fun securityOrUnlabelledButtonIsNotAnAdvertCloseAction() {
        assertNull(HungerStationSpinPromo.closeTarget(frame("تحقق من أنك إنسان")))
        assertNull(HungerStationSpinPromo.closeTarget(frame("")))
        assertNull(HungerStationSpinPromo.closeTarget(frame("تأكيد الطلب")))
    }
}
