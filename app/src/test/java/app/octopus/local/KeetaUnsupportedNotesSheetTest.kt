package app.octopus.local

import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic rejection cases only; NOT a recorded Fatera tree or proof of supported notes. */
class KeetaUnsupportedNotesSheetTest {
    private val restaurant="مطعم اختبار"
    private val title="صنف اختبار"
    private val version="3.13.203"
    private fun n(i:Int,text:String="",click:Boolean=false)=CatalogNode(i,-1,"",text,"android.view.View",click,false,false)
    private val frame=CatalogFrame(listOf(n(0,restaurant),n(1,title),n(2,"طلب خاص"),n(3,"إضافة إلى السلة",true),
        n(4).copy(className="android.widget.EditText",editable=true)),true)
    private fun matches(f:CatalogFrame=frame,known:Boolean=false)=KeetaUnsupportedNotesSheet.matches(f,restaurant,title,version,known)
    @Test fun unrecognizedNotesAnchorsOnlyClassifyFailureAndNeverAuthorizeAddOrPrice() {
        assertTrue(matches())
        val reader=KeetaMenuReader(restaurant,title,version)
        assertFalse(reader.product(frame))
        assertNull(reader.productBody(frame))
        assertNull(reader.unitPrice(frame))
        assertNull(KeetaBasketReader(reader).addTarget(frame,700))
    }
    @Test fun alreadyRecognizedProductsAndOtherVersionsDoNotBecomeNewRejections() {
        assertFalse(matches(known=true))
        assertFalse(KeetaUnsupportedNotesSheet.matches(frame,restaurant,title,"3.12.501",false))
        val actual=com.google.gson.Gson().fromJson(javaClass.getResource("/recorded-keeta/keeta-3.13.203-herfy-single-sku-redacted.json")!!.readText(),CatalogFrame::class.java)
        assertFalse(matches(actual))
    }
    @Test fun changedRestaurantTitleMissingAnchorsOrDuplicateHeadingsCannotClaimThisSpecificFailure() {
        assertFalse(KeetaUnsupportedNotesSheet.matches(frame,"مطعم آخر",title,version,false))
        assertFalse(KeetaUnsupportedNotesSheet.matches(frame,restaurant,"صنف آخر",version,false))
        assertFalse(matches(frame.copy(nodes=frame.nodes.filterNot{it.label=="طلب خاص"})))
        assertFalse(matches(frame.copy(nodes=frame.nodes+n(6,title))))
    }
    @Test fun incompleteFramesAndMultipleEditorsAreNotAssumedToBeThisPublicFoodSheet() {
        assertFalse(matches(frame.copy(complete=false)))
        assertFalse(matches(frame.copy(nodes=frame.nodes+frame.nodes.last().copy(index=7))))
    }
    @Test fun userEnteredNotesCannotBeClassifiedAsAnEmptyNotesLayout() {
        assertFalse(matches(frame.copy(nodes=frame.nodes.map{if(it.editable)it.copy(enteredText=true)else it})))
    }
}
