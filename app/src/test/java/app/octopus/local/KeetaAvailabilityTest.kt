package app.octopus.local
import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test
class KeetaAvailabilityTest {
    private fun frame(text:String="هذا المتجر مغلق حاليًا. ألقِ نظرة\nعلى المتاجر الأخرى.")=CatalogFrame(listOf(
        CatalogNode(0,-1,"",text,"android.widget.TextView",false,false,false),
        CatalogNode(1,-1,"","موافق","android.widget.Button",true,false,false)),true)
    @Test fun recordedClosedStoreMessageStopsWithoutAdmittingAction() {
        assertTrue(KeetaAvailability.closedNotice(frame()))
        val reader=KeetaMenuReader("كودو","بطاطس وسط","3.13.203")
        assertEquals(frame().nodes[0].label,reader.functionalLabel(frame().nodes[0].label))
        assertEquals("موافق",reader.functionalLabel("موافق"))
    }
    @Test fun genericConfirmationOtherMessageOrIncompleteViewIsNotAvailabilityEvidence() {
        assertFalse(KeetaAvailability.closedNotice(frame("هل تريد حذف جميع المنتجات من السلة؟")))
        assertFalse(KeetaAvailability.closedNotice(frame("تأكيد الطلب")))
        assertFalse(KeetaAvailability.closedNotice(frame().copy(complete=false)))
        assertFalse(KeetaAvailability.closedNotice(frame().copy(nodes=frame().nodes+frame().nodes.last().copy(index=2))))
    }
}
