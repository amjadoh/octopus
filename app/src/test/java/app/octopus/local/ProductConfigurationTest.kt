package app.octopus.local

import app.octopus.local.domain.*
import app.octopus.local.providers.*
import app.octopus.local.safety.*
import org.junit.Assert.*
import org.junit.Test

/** Minimal shapes transcribed from prior sanitized observations; not fresh provider-device tests. */
class ProductConfigurationTest {
    private val restaurant = LiveRestaurant("المضياف العربي", null, "المضياف")
    private val item = LiveMenuItem("دجاج مظبي", 3300, "gridMenuCell-0-0")
    private fun frame(vararg rows: Pair<String, Boolean>, disabled: String? = null): CatalogFrame {
        val body = rows.mapIndexed { i, (text, click) -> CatalogNode(i+1, -1, "", text,
            if (click) "android.widget.ImageView" else "android.view.View", click, false, false, text != disabled) }
        return CatalogFrame(listOf(CatalogNode(0,-1,"pdpCloseButton","","android.widget.ImageView",true,false,false)) + body +
            CatalogNode(body.size+1,-1,"flutterAddButton","إضافة\n§\n21","android.widget.Button",true,false,false), true)
    }
    private fun read(complete: Boolean = true, disabledRice: Boolean = false): ConfiguredProduct {
        val reader = HungerStationProductReader(restaurant, item)
        reader.observe(frame("دجاج مظبي" to false, "اختر الحجم" to false, "مطلوب" to false, "اختيار واحد" to false,
            "نص\n720+ طلب" to true, "إضافات" to false, "مطلوب" to false, "اختيار واحد" to false,
            "رز بشاور\n490+ طلب\n(§\n15\n+)" to true, disabled = if (disabledRice) "رز بشاور\n490+ طلب\n(§\n15\n+)" else null))
        if (complete) reader.observe(frame("دجاج مظبي" to false, "إضافات" to false, "مطلوب" to false, "اختيار واحد" to false,
            "رز بشاور\n490+ طلب\n(§\n15\n+)" to true, "رز شعبي\n(§\n12\n+)" to true,
            "رز مندي\n140+ طلب\n(§\n12\n+)" to true, "غالبًا ما يتم طلبه مع" to false,
            "سلطة حار\n§\n3" to true, disabled = if (disabledRice) "رز بشاور\n490+ طلب\n(§\n15\n+)" else null))
        return reader.finish(1000)
    }
    private fun selection() = ProductSelection(item.selector, mapOf("اختر الحجم" to setOf("نص"), "إضافات" to setOf("رز بشاور")), 1)

    @Test fun requiredChoicesAreReadAcrossPagesAndRecommendationsAreExcluded() {
        val details = read()
        assertTrue(details.optionsComplete)
        assertEquals(listOf("اختر الحجم", "إضافات"), details.product.groups.map { it.title })
        assertEquals(listOf("رز بشاور", "رز شعبي", "رز مندي"), details.product.groups.last().choices.map { it.title })
        assertFalse(details.product.groups.any { group -> group.choices.any { it.title == "سلطة حار" } })
    }
    @Test fun exactSizeAndRiceAreCanonicalAndNoPriceOrAddressIsInvented() {
        val result = MenuSelectionValidator().selectConfigured(read(), selection(), 2000) as SelectionResult.Accepted
        assertEquals(CanonicalItem("دجاج مظبي", "نص", listOf(CartOption("إضافات", "رز بشاور")), 1), result.item)
        val draft = (DraftEditor.add(null, restaurant.name, result.item) as DraftChange.Changed).cart!!
        assertNull(draft.addressReference); assertNull(draft.branch)
        assertEquals(draft, app.octopus.local.data.CanonicalCartCodec.decode(app.octopus.local.data.CanonicalCartCodec.encode(draft)))
    }
    @Test fun missingRicePartialOptionsStaleAndDisabledChoiceCannotBeSaved() {
        val validator = MenuSelectionValidator()
        assertEquals(SelectionResult.Rejected(SelectionError.REQUIRED_CHOICES), validator.selectConfigured(read(), selection().copy(choices=mapOf("اختر الحجم" to setOf("نص"))),2000))
        assertEquals(SelectionResult.Rejected(SelectionError.PARTIAL_MENU), validator.selectConfigured(read(false),selection(),2000))
        assertEquals(SelectionResult.Rejected(SelectionError.STALE_MENU),validator.selectConfigured(read(),selection(),400_000))
        assertEquals(SelectionResult.Rejected(SelectionError.UNAVAILABLE),validator.selectConfigured(read(disabledRice=true),selection(),2000))
    }
    @Test fun unsupportedOptionalRulesAndWrongProductNeverBecomeComplete() {
        val reader = HungerStationProductReader(restaurant,item)
        reader.observe(frame("دجاج مظبي" to false,"اختياري" to false,"غالبًا ما يتم طلبه مع" to false))
        assertFalse(reader.finish(1000).optionsComplete)
        val wrong = HungerStationProductReader(restaurant,item)
        wrong.observe(frame("وجبة أخرى" to false,"غالبًا ما يتم طلبه مع" to false))
        assertThrows(IllegalArgumentException::class.java) { wrong.finish(1000) }
    }
    @Test fun anUnobservedProductCannotBorrowTheRecordedConfigurationSupport() {
        val other = HungerStationProductReader(restaurant,item.copy(name="وجبة أخرى"))
        other.observe(frame("وجبة أخرى" to false,"غالبًا ما يتم طلبه مع" to false))
        assertFalse(other.finish(1000).optionsComplete)
    }
    @Test fun oversizedInterfaceMetadataIsRejectedWithoutCrashingTheSelectionUi() {
        val details=read()
        val malformed=details.copy(product=details.product.copy(title="س".repeat(513)))
        assertEquals(SelectionResult.Rejected(SelectionError.INVALID_METADATA),MenuSelectionValidator().selectConfigured(malformed,selection(),2000))
        val conflicting=details.copy(product=details.product.copy(size="كامل"))
        assertEquals(SelectionResult.Rejected(SelectionError.INVALID_METADATA),MenuSelectionValidator().selectConfigured(conflicting,selection(),2000))
    }
    @Test fun addingSameVariantMergesQuantityAndDifferentSizesStaySeparate() {
        val small=CanonicalItem("وجبة","صغير",emptyList(),1)
        val first=(DraftEditor.add(null,"مطعم",small) as DraftChange.Changed).cart
        val twice=(DraftEditor.add(first,"مطعم",small) as DraftChange.Changed).cart!!
        assertEquals(2,twice.items.single().quantity)
        val variants=(DraftEditor.add(twice,"مطعم",small.copy(size="كبير")) as DraftChange.Changed).cart!!
        assertEquals(2,variants.items.size)
    }
    @Test fun restaurantReplacementAndQuantityLimitsPreserveTheOriginalDraft() {
        val item=CanonicalItem("وجبة",null,emptyList(),99)
        val cart=CanonicalCart("مطعم أ","فرع",listOf(item),"address-reference")
        assertEquals(DraftChange.RestaurantReplacementRequired,DraftEditor.add(cart,"مطعم ب",item))
        assertTrue(DraftEditor.add(cart,"مطعم أ",item.copy(quantity=1)) is DraftChange.Rejected)
        val replaced=(DraftEditor.add(cart,"مطعم ب",item,true) as DraftChange.Changed).cart!!
        assertNull(replaced.branch); assertNull(replaced.addressReference)
        assertNull((DraftEditor.quantity(cart,CartIdentity.itemKey(item),0) as DraftChange.Changed).cart)
        assertEquals(99,cart.items.single().quantity)
    }
    @Test fun productNavigationDoesNotAuthorizeAddingOrCheckout() {
        val guard=SafetyGuard(); val pkg=ProviderId.HUNGERSTATION.packageName
        assertTrue(guard.allowsCatalogAction(CatalogAction.OPEN_PRODUCT,CatalogScreen.MENU,pkg,true,true,true,"وجبة"))
        assertFalse(guard.allowsCatalogAction(CatalogAction.OPEN_PRODUCT,CatalogScreen.CART,pkg,true,true,true,"وجبة"))
        assertFalse(guard.allowsCatalogAction(CatalogAction.SCROLL_PRODUCT,CatalogScreen.PRODUCT,ProviderId.KEETA.packageName,true,true,true,""))
        assertFalse(guard.allowsAction(ProposedAction.CREATE_ORDER))
        assertFalse(guard.allowsAction(ProposedAction.PAYMENT))
        assertFalse(guard.allowsAction(ProposedAction.CLICK))
    }
}
