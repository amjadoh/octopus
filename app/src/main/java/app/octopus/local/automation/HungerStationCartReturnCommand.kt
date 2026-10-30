package app.octopus.local.automation
import app.octopus.local.providers.*
import java.util.concurrent.atomic.AtomicBoolean

/** One Android Back from an exactly rechecked, completely observed CART. No target lookup or checkout. */
class HungerStationCartReturnCommand private constructor(private val frame:CatalogFrame,private val at:Long) {
    private val used=AtomicBoolean(false)
    fun consume(current:CatalogFrame,version:String?,now:Long):Boolean =
        version==HungerStationCatalog.TESTED_VERSION && now>=at && now-at<=15_000 && current==frame && used.compareAndSet(false,true)
    companion object {
        fun create(frame:CatalogFrame,evidence:BasketEvidence,version:String?,now:Long):HungerStationCartReturnCommand? {
            if(version!=HungerStationCatalog.TESTED_VERSION || now<0 || !evidence.complete || !frame.complete ||
                HungerStationCatalog.screen(frame)!=CatalogScreen.CART || BasketEvidenceReader.hungerStation(frame,true)!=evidence)return null
            return HungerStationCartReturnCommand(frame,now)
        }
    }
}
