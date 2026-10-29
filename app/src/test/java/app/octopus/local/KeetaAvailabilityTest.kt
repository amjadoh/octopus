package app.octopus.local
import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test
class KeetaAvailabilityTest {
    @Test fun minimumOrderNoticeIsRecognizedWithoutAcceptingItsFee() {
        val notice=CatalogFrame(listOf(
            CatalogNode(0,-1,"","لم يبلغ الطلب الحد الأدنى","android.widget.TextView",false,false,false),
            CatalogNode(1,-1,"","إضافة أصناف أخرى","android.widget.TextView",false,false,false)),true)
        assertTrue(KeetaAvailability.minimumOrderNotice(notice))
        val reader=KeetaMenuReader("كودو","بطاطا صغيرة","3.13.203")
        notice.nodes.forEach {assertEquals(it.label,reader.functionalLabel(it.label))}
        assertFalse(KeetaAvailability.minimumOrderNotice(notice.copy(complete=false)))
        assertFalse(KeetaAvailability.minimumOrderNotice(notice.copy(nodes=notice.nodes.drop(1))))
        assertFalse(KeetaAvailability.minimumOrderNotice(notice.copy(nodes=notice.nodes.map {it.copy(editable=true)})))
        assertFalse(KeetaAvailability.minimumOrderNotice(notice.copy(nodes=notice.nodes+notice.nodes[0].copy(index=2))))
    }
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
    @Test fun disabledMenuBannerStopsBeforeLookingForAnEnabledProduct() {
        val banner="المتجر مغلق وغير متاح مؤقتًا"
        val menu=CatalogFrame(listOf(
            CatalogNode(0,-1,"com.sankuai.sailor.afooddelivery:id/card_root","","android.view.ViewGroup",false,false,false),
            CatalogNode(1,-1,"",banner,"android.widget.TextView",false,false,false)),true)
        assertTrue(KeetaAvailability.closedMenu(menu))
        assertEquals(banner,KeetaMenuReader("مطاعم فوال العربي","فول","3.13.203").functionalLabel(banner))
        assertFalse(KeetaAvailability.closedMenu(menu.copy(complete=false)))
        assertFalse(KeetaAvailability.closedMenu(menu.copy(nodes=menu.nodes.drop(1))))
        assertFalse(KeetaAvailability.closedMenu(menu.copy(nodes=menu.nodes+menu.nodes.last().copy(index=2))))
        assertFalse(KeetaAvailability.unavailableText("المتجر متاح الآن"))
    }
}
