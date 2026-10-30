package app.octopus.local
import app.octopus.local.domain.*
import org.junit.Assert.*
import org.junit.Test
class ExistingQuantityConsentTest {
    private val cart=CanonicalCart("مطعم",null,listOf(CanonicalItem("وجبة",null,emptyList(),3)))
    @Test fun permissionIsBoundToExactRequestAndExpires() {
        val consent=ExistingQuantityConsent(cart.fingerprint,1000)
        assertTrue(consent.permits(cart,1000))
        assertTrue(consent.permits(cart,301000))
        assertFalse(consent.permits(cart,301001))
        assertFalse(consent.permits(cart,999))
        assertFalse(consent.permits(cart.copy(restaurant="آخر"),1000))
        assertFalse(consent.permits(cart.copy(items=listOf(cart.items.single().copy(quantity=4))),1000))
    }
}