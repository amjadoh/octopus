package app.octopus.local
import app.octopus.local.domain.*
import org.junit.Assert.*
import org.junit.Test
class PlainComparisonSupportTest {
    private val a=CanonicalItem("بطاطس وسط",null,emptyList(),3)
    private val b=CanonicalItem("جبنة الناتشوز",null,emptyList(),1)
    private fun cart(vararg lines:CanonicalItem)=CanonicalCart("كودو",null,lines.toList())
    @Test fun twoDistinctPlainLinesOnly() {
        assertTrue(PlainComparisonSupport.existingTwoRow(cart(a,b)))
        assertFalse(PlainComparisonSupport.existingTwoRow(cart(a)))
        assertFalse(PlainComparisonSupport.existingTwoRow(cart(a,a)))
        assertFalse(PlainComparisonSupport.existingTwoRow(cart(a,b,b.copy(name="آخر"))))
        assertFalse(PlainComparisonSupport.existingTwoRow(cart(a,b.copy(size="كبير"))))
        assertFalse(PlainComparisonSupport.existingTwoRow(cart(a,b.copy(note="إضافة"))))
    }
}
