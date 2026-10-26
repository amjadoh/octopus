package app.octopus.local
import app.octopus.local.providers.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
class KeetaForeignMenuRecoveryTest {
    private val actual=Gson().fromJson(javaClass.getResource("/recorded-keeta/keeta-3.13.203-foreign-herfy-menu-redacted.json")!!.readText(),CatalogFrame::class.java)
    @Test fun actualPublicMenuAllowsOnlyLauncherRecoveryWithSupportedVersion() {
        assertTrue(KeetaForeignMenuRecovery.permits(actual,"3.13.203"))
        assertFalse(KeetaForeignMenuRecovery.permits(actual,"unknown"))
        assertFalse(KeetaForeignMenuRecovery.permits(actual.copy(complete=false),"3.13.203"))
    }
    @Test fun productBasketPaymentAndUnknownViewsNeverAdmitRecovery() {
        for(label in listOf("إضافة إلى السلة","إتمام الدفع","تأكيد الطلب","الأصناف المضافة","تفريغ السلة")) {
            val extra=CatalogNode(1000,0,"",label,"android.widget.TextView",false,false,false)
            assertFalse(KeetaForeignMenuRecovery.permits(actual.copy(nodes=actual.nodes+extra),"3.13.203"))
        }
        assertFalse(KeetaForeignMenuRecovery.permits(actual.copy(nodes=actual.nodes.map {it.copy(id="")}),"3.13.203"))
        val editor=CatalogNode(1000,0,"","","android.widget.EditText",true,true,false)
        assertFalse(KeetaForeignMenuRecovery.permits(actual.copy(nodes=actual.nodes+editor),"3.13.203"))
    }
}
