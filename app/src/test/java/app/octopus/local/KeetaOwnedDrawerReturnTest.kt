package app.octopus.local

import app.octopus.local.automation.KeetaOwnedDrawerReturn
import app.octopus.local.domain.*
import app.octopus.local.providers.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

/** Actual 3.13.301 drawer -> Android Back -> search results -> reopen -> unchanged basket. */
class KeetaOwnedDrawerReturnTest {
    private val request = CanonicalCart("كودو", null, listOf(CanonicalItem("بطاطس وسط", null, emptyList(), 3)))
    private val version = "3.13.301"
    private val reader = KeetaMenuReader(request.restaurant, request.items.single().name, version)
    private val basket = KeetaBasketReader(reader)
    private fun recorded(phase: String): CatalogFrame = Gson().fromJson(
        javaClass.getResource("/recorded-keeta/keeta-3.13.301-$phase-redacted.json")!!.readText(), CatalogFrame::class.java)
    private val source = recorded("drawer-before-back")
    private val results = recorded("menu-after-back") // The actual frame is search results, despite the capture filename.
    private val menu = recorded("menu-reopened-after-back")
    private val evidence = checkNotNull(basket.evidence(source))
    private fun command() = checkNotNull(KeetaOwnedDrawerReturn.create(source, evidence, request, version, 1000))

    @Test fun recordedBackAndSingleReopenReturnToSameCountAndUnchangedCompleteRows() {
        val command = command()
        assertTrue(command.consumeBack(source, request, version, 1100))
        assertFalse(command.menuMatches(results, request, version, 1200))
        val target = checkNotNull(command.searchTarget(results, request, version, 1200))
        assertEquals(41, target.index)
        assertTrue(command.consumeReopen(results, target, request, version, 1300))
        assertTrue(command.menuMatches(menu, request, version, 1400))
        assertTrue(command.finish(menu, request, version, 1400))
        assertEquals(evidence, basket.evidence(recorded("drawer-after-reopen")))
        assertFalse(command.finish(menu, request, version, 1500))
        assertNull(command.searchTarget(results, request, version, 1500))
    }

    @Test fun secondBackOrReopenCannotBeConsumed() {
        val back = command()
        assertTrue(back.consumeBack(source, request, version, 1100))
        assertFalse(back.consumeBack(source, request, version, 1200))
        assertNull(back.searchTarget(results, request, version, 1300))
        val reopen = command()
        assertTrue(reopen.consumeBack(source, request, version, 1100))
        val target = checkNotNull(reopen.searchTarget(results, request, version, 1200))
        assertTrue(reopen.consumeReopen(results, target, request, version, 1300))
        assertFalse(reopen.consumeReopen(results, target, request, version, 1400))
        assertFalse(reopen.finish(menu, request, version, 1500))
    }

    @Test fun changedSourceRequestVersionAndExpiredPermitCannotDispatchBack() {
        assertFalse(command().consumeBack(source.copy(complete = false), request, version, 1100))
        assertFalse(command().consumeBack(source, request.copy(restaurant = "آخر"), version, 1100))
        assertFalse(command().consumeBack(source, request, "3.13.203", 1100))
        assertFalse(command().consumeBack(source, request, version, 999))
        assertFalse(command().consumeBack(source, request, version, 31_001))
    }

    @Test fun foreignIncompleteModifiedOrMultiItemBasketsNeverAuthorizeBack() {
        assertNull(KeetaOwnedDrawerReturn.create(source.copy(complete = false), evidence, request, version, 1000))
        assertNull(KeetaOwnedDrawerReturn.create(source, evidence.copy(complete = false), request, version, 1000))
        assertNull(KeetaOwnedDrawerReturn.create(source, evidence.copy(subtotal = 1), request, version, 1000))
        assertNull(KeetaOwnedDrawerReturn.create(source, evidence, request.copy(restaurant = "آخر"), version, 1000))
        assertNull(KeetaOwnedDrawerReturn.create(source, evidence, request.copy(items = request.items +
            CanonicalItem("جبنة الناتشوز", null, emptyList(), 1)), version, 1000))
        assertNull(KeetaOwnedDrawerReturn.create(source, evidence, request, "3.13.203", 1000))
        assertNull(KeetaOwnedDrawerReturn.create(source, evidence, request, "3.13.302", 1000))
    }

    @Test fun unknownQueryOrChangedMenuCountCannotCompleteReturn() {
        val command = command()
        assertTrue(command.consumeBack(source, request, version, 1100))
        val unknownQuery = results.copy(nodes = results.nodes.map {
            if (it.id.endsWith(":id/tv_keyword")) it.copy(label = "آخر") else it
        })
        assertNull(command.searchTarget(unknownQuery, request, version, 1200))
        val changedCount = menu.copy(nodes = menu.nodes.map { if (it.label == "3") it.copy(label = "2") else it })
        assertFalse(command.menuMatches(changedCount, request, version, 1300))
        assertFalse(command.finish(changedCount, request, version, 1300))
        assertFalse(command.finish(menu, request, version, 1400))
    }
}
