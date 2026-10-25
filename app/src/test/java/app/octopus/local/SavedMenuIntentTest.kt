package app.octopus.local

import app.octopus.local.domain.*
import org.junit.Assert.*
import org.junit.Test

class SavedMenuIntentTest {
    private val product = ConfiguredProduct("مطعم محفوظ",
        MenuProduct("meal", "وجبة", null, true, 1200,
            listOf(MenuChoiceGroup("size", "الحجم", 1, 1,
                listOf(MenuChoice("large", "كبير", true))))),
        CatalogProvenance(ProviderId.HUNGERSTATION, "8.0.303", 1_000, "original-reading"), true)
    private val selection = ProductSelection("meal", mapOf("size" to setOf("large")), 3)

    @Test fun savedSelectionDoesNotRenewEvidenceOrPermitAStaleAutomaticAction() {
        val validator = MenuSelectionValidator()
        val intended = validator.selectIntent(product, selection) as SelectionResult.Accepted
        assertEquals(3, intended.item.quantity)
        assertEquals(listOf(CartOption("الحجم", "كبير")), intended.item.options)
        assertEquals(SelectionResult.Rejected(SelectionError.STALE_MENU),
            validator.selectConfigured(product, selection, 1_000_000))
        assertEquals(1_000L, product.source.capturedAtMillis)
        assertEquals("original-reading", product.source.screenFingerprint)
    }

    @Test fun missingRequiredChoicesAndPartialOptionsCannotBeSavedAsAPlainMeal() {
        val validator = MenuSelectionValidator()
        assertEquals(SelectionResult.Rejected(SelectionError.REQUIRED_CHOICES),
            validator.selectIntent(product, selection.copy(choices = emptyMap())))
        assertEquals(SelectionResult.Rejected(SelectionError.PARTIAL_MENU),
            validator.selectIntent(product.copy(optionsComplete = false), selection))
    }

    @Test fun unknownProductAndOutOfRangeQuantitiesAreStillRejectedLocally() {
        val validator = MenuSelectionValidator()
        assertEquals(SelectionResult.Rejected(SelectionError.PRODUCT_MISSING),
            validator.selectIntent(product, selection.copy(productId = "other")))
        assertEquals(SelectionResult.Rejected(SelectionError.INVALID_QUANTITY),
            validator.selectIntent(product, selection.copy(quantity = 100)))
    }
}
