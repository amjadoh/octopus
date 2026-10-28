package app.octopus.local

import app.octopus.local.providers.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class KeetaOptionalPlainSessionTest {
    private fun frame(name: String): CatalogFrame = Gson().fromJson(javaClass.getResource(
        "/recorded-keeta/keeta-3.13.301-albaik-$name-redacted.json")!!.readText(), CatalogFrame::class.java)
    private fun reader() = KeetaMenuReader("البيك", "بيج بيك", "3.13.301")
    @Test fun recordedDefaultNeedsTopSkuAndCompleteUnselectedGroupsBeforeAdd() {
        val r = reader(); val now = System.currentTimeMillis()
        val top = frame("bigbaik-top"); val bottom = frame("bigbaik-default")
        val session = checkNotNull(KeetaOptionalPlainSession.start(top, r, now))
        assertNull(r.productBody(bottom))
        assertFalse(r.acceptOptionalPlain(top, session, now))
        assertFalse(session.blocked)
        val accepted = r.acceptOptionalPlain(bottom, session, now)
        assertTrue("$session body=${KeetaOptionalPlainSession.readBody(bottom,r.restaurant,r.title)?.index} price=${r.price(bottom)}", accepted)
        assertNotNull(r.productBody(bottom))
        assertNotNull(KeetaBasketReader(r).addTarget(bottom, 1750))
        assertNull(KeetaBasketReader(r).addTarget(bottom, 1950))
    }
    @Test fun paidAndFreeSelectedIndicatorsNeverAuthorizeAPlainAdd() {
        for (name in listOf("bigbaik-cheese", "bigbaik-free")) {
            val r = reader(); val now = System.currentTimeMillis()
            val session = checkNotNull(KeetaOptionalPlainSession.start(frame("bigbaik-top"),r,now))
            assertFalse(r.acceptOptionalPlain(frame(name),session,now+1))
            assertTrue(session.blocked)
            assertNull(r.productBody(frame(name)))
        }
    }
    @Test fun missingChoiceUnknownTextRequiredGroupOrWrittenNoteCannotPassCoverage() {
        val bottom = frame("bigbaik-default")
        for (changed in listOf(
            bottom.copy(complete=false),
            bottom.copy(nodes=bottom.nodes.map { if(it.label=="بدون سلطة") it.copy(label="",omittedText=true) else it }),
            bottom.copy(nodes=bottom.nodes.map { if(it.label=="اختياري") it.copy(label="إلزامي") else it }),
            bottom.copy(nodes=bottom.nodes.map { if(it.label=="0/70") it.copy(label="1/70") else it }),
        )) {
            val r=reader();val now=System.currentTimeMillis()
            val session=checkNotNull(KeetaOptionalPlainSession.start(frame("bigbaik-top"),r,now))
            assertFalse(r.acceptOptionalPlain(changed,session,now+1))
            assertNull(r.productBody(changed))
        }
    }
}
