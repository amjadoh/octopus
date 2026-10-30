package app.octopus.local.domain

/** Permission to adjust only a structurally matching existing menu row, never clear/replace a cart. */
data class ExistingQuantityConsent(val requestFingerprint:String, val issuedAt:Long) {
    fun permits(cart:CanonicalCart,now:Long):Boolean = requestFingerprint==cart.fingerprint &&
        issuedAt>=0 && now>=issuedAt && now-issuedAt<=300_000
}
