package app.octopus.local

import app.octopus.local.providers.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class KeetaFullScreenAdvertTest {
    private val advert = Gson().fromJson(javaClass.getResource("/recorded-keeta/keeta-3.13.301-fullscreen-advert.json")!!.readText(), CatalogFrame::class.java)

    @Test fun recordedAdvertClosesOnlyThroughItsSmallCloseImage() {
        val close = KeetaFullScreenAdvert.closeTarget(advert)
        assertNotNull(close)
        assertEquals("android.widget.ImageView", close!!.className)
    }

    @Test fun anyTextEditFieldOrRealScreenIsNotTheAdvert() {
        val withText = advert.copy(nodes = advert.nodes.map { if (it.index == 5) it.copy(label = "إتمام الدفع") else it })
        assertNull(KeetaFullScreenAdvert.closeTarget(withText))
        val withEditor = advert.copy(nodes = advert.nodes + advert.nodes.last().copy(index = 500, className = "android.widget.EditText", editable = true, clickable = false))
        assertNull(KeetaFullScreenAdvert.closeTarget(withEditor))
        assertNull(KeetaFullScreenAdvert.closeTarget(advert.copy(complete = false)))
        val menu = Gson().fromJson(javaClass.getResource("/recorded-keeta/keeta-3.13.301-albaik-single-sku-notes-redacted.json")!!.readText(), CatalogFrame::class.java)
        assertNull(KeetaFullScreenAdvert.closeTarget(menu))
    }
}
