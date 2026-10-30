package app.octopus.local

import app.octopus.local.pricing.GroupedReviewFields
import app.octopus.local.domain.PriceField
import org.junit.Assert.*
import org.junit.Test

class GroupedReviewFieldsTest {
    @Test fun savedHungerFeesRemainPartialWithoutInventingTotal() {
        val values = GroupedReviewFields.read("رسوم التوصيل\n§\n20.00\nرسوم الخدمة\n§\n1.90")
        assertEquals(listOf(PriceField.DELIVERY to 2000L, PriceField.SERVICE to 190L), values)
        assertFalse(values.any { it.first == PriceField.ORDER_TOTAL })
    }
    @Test fun rejectsUnlabelledNumbersAndDoesNotExtractPrivateContent() {
        assertTrue(GroupedReviewFields.read("1234\nعنوان محفوظ\n42.90").isEmpty())
        assertTrue(GroupedReviewFields.read("الإجمالي\nعنوان محفوظ\n§ 42.90").isEmpty())
        assertTrue(GroupedReviewFields.read("الإجمالي\n42.90").isEmpty())
    }
    @Test fun conflictingTotalsArePreservedForDownstreamRejection() {
        assertEquals(2, GroupedReviewFields.read("الإجمالي\n§ 42.90\nالإجمالي\n§ 43.90").size)
    }
}
