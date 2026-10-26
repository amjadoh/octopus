package app.octopus.local

import app.octopus.local.providers.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class KeetaSingleSkuProductTest {
    private val actual=Gson().fromJson(javaClass.getResource("/recorded-keeta/keeta-3.13.203-herfy-single-sku-redacted.json")!!.readText(),CatalogFrame::class.java)
    private val reader=KeetaMenuReader("هرفي","سوبر هرفي","3.13.203")
    @Test fun actualSingleExactSkuCanBeReadAndClosedAndHasGuardedAdd() {
        assertNotNull(KeetaSingleSkuProduct.body(actual,"هرفي","سوبر هرفي"))
        assertTrue(reader.product(actual))
        assertNotNull(reader.productBody(actual))
        assertNotNull(reader.closeProduct(actual))
        assertEquals(3100L,reader.unitPrice(actual))
        assertNotNull(KeetaBasketReader(reader).addTarget(actual,3100))
        assertNull(KeetaBasketReader(reader).addTarget(actual,3200))
    }
    @Test fun unknownSecondSkuOmittedOptionOrPriceMismatchCannotAuthorizeAdd() {
        fun changed(index:Int,change:(CatalogNode)->CatalogNode)=actual.copy(nodes=actual.nodes.map {if(it.index==index)change(it) else it})
        assertNull(reader.productBody(changed(209){it.copy(label="حجم آخر",omittedText=true)}))
        assertNull(reader.productBody(changed(213){it.copy(label="32.00 ﷼")}))
        assertNull(reader.productBody(changed(203){it.copy(omittedText=true)}))
        assertNull(reader.productBody(changed(189){it.copy(scrollable=true)}))
        val extra=actual.nodes.single {it.index==206}.copy(index=1000,parent=205)
        assertNull(reader.productBody(actual.copy(nodes=actual.nodes+extra)))
        assertNull(reader.productBody(actual.copy(complete=false)))
        assertNull(KeetaMenuReader("مطعم آخر","سوبر هرفي","3.13.203").productBody(actual))
    }
    @Test fun quantityAndFooterMustAgreeWithSoleSkuUnit() {
        val two=actual.copy(nodes=actual.nodes.map {when(it.index) {
            227->it.copy(label="2")
            237->it.copy(label="62.00 ﷼")
            else->it
        }})
        assertEquals(3100L,reader.unitPrice(two))
        assertNull(KeetaBasketReader(reader).addTarget(two,3100)) // first Add remains exactly one
    }
    @Test fun actualLongSkuIsRecognizedForCloseOnlyWithoutAdmittingItsUnknownOptions() {
        val frame=Gson().fromJson(javaClass.getResource("/recorded-keeta/keeta-3.13.203-herfy-long-sku-redacted.json")!!.readText(),CatalogFrame::class.java)
        val selected=KeetaMenuReader("هرفي","دبل سوبر هرفي","3.13.203")
        assertTrue(selected.product(frame))
        assertNotNull(selected.closeProduct(frame))
        assertNull(selected.productBody(frame))
        assertNull(KeetaBasketReader(selected).addTarget(frame,3800))
    }
}
