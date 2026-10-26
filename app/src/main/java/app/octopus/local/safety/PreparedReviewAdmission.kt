package app.octopus.local.safety
import app.octopus.local.domain.*
import app.octopus.local.providers.*
import java.util.concurrent.atomic.AtomicBoolean

/** Short-lived, one-navigation admission from a complete just-verified plain basket. */
class PreparedReviewAdmission private constructor(
    internal val sourceFrame:CatalogFrame,
    internal val record:PreparedBasketRecord,
    private val provider:ProviderId,
    private val fingerprint:String,
    private val version:String,
    private val at:Long,
) {
    private val used=AtomicBoolean(false)
    internal fun validFor(id:ProviderId,cart:CanonicalCart,observedVersion:String,now:Long):Boolean =
        !used.get() && id==provider && cart.fingerprint==fingerprint && observedVersion==version &&
            now>=at && now-at<=15_000 && now>=record.observedAtMillis && now-record.observedAtMillis<=15_000
    fun consume(frame:CatalogFrame,target:CatalogNode,id:ProviderId,cart:CanonicalCart,observedVersion:String,now:Long):Boolean =
        validFor(id,cart,observedVersion,now) &&
            ReviewNavigationGuard.target(id,frame,cart,observedVersion,this,now)==target && used.compareAndSet(false,true)
    companion object {
        fun create(id:ProviderId,cart:CanonicalCart,record:PreparedBasketRecord,source:CatalogFrame,version:String,now:Long):PreparedReviewAdmission? {
            if(cart.items.size<2 || !record.matches(cart) || record.providerVersion!=version || record.evidence.provider!=id ||
                !record.evidence.complete || !source.complete || now<record.observedAtMillis || now-record.observedAtMillis>15_000 ||
                !BasketItemMatcher.compare(cart,record.evidence).matches)return null
            val actual=when(id) {
                ProviderId.HUNGERSTATION -> {
                    if(version!=HungerStationCatalog.TESTED_VERSION || HungerStationCatalog.screen(source)!=CatalogScreen.CART)return null
                    BasketEvidenceReader.hungerStation(source,true)
                }
                ProviderId.KEETA -> {
                    if(version!="3.13.203")return null
                    val reader=KeetaMenuReader(cart.restaurant,cart.items.first().name,version)
                    KeetaBasketReader(reader,cart.items.map {it.name}.toSet()).evidence(source) ?: return null
                }
            }
            if(actual!=record.evidence)return null
            return PreparedReviewAdmission(source,record,id,cart.fingerprint,version,now)
        }
    }
}
