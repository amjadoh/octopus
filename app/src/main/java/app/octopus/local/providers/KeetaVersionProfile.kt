package app.octopus.local.providers

/** Exact installed-version admission, separate from the UI schema shared by known releases.
 * Reader/record/command versions remain the actual installed version, never a schema alias.
 * New versions and capabilities require their own evidence; no prefix/range admission exists.
 */
class KeetaVersionProfile private constructor(
    val version: String,
    val schema: Schema,
    private val capabilities: Set<Capability>,
) {
    enum class Schema { LEGACY_312, CURRENT_313 }
    private enum class Capability {
        READ_MENU, READ_MULTIPLE_ITEMS, PREPARE_CART, PREPARE_OWNED_PLAIN_CART,
        PREPARE_MULTIPLE_ITEMS, REVIEW, REVIEW_MULTIPLE_ITEMS, OWNED_DRAWER_RETURN, DIRECT_DRAWER_REVIEW,
    }

    val usesCurrentLayout: Boolean get() = schema == Schema.CURRENT_313
    val allowsOwnedPlainPreparation: Boolean get() = Capability.PREPARE_OWNED_PLAIN_CART in capabilities
    val allowsOwnedDrawerReturn: Boolean get() = Capability.OWNED_DRAWER_RETURN in capabilities
    val allowsDirectDrawerReview: Boolean get() = Capability.DIRECT_DRAWER_REVIEW in capabilities
    fun allowsReading(multipleItems: Boolean = false): Boolean = Capability.READ_MENU in capabilities &&
        (!multipleItems || Capability.READ_MULTIPLE_ITEMS in capabilities)
    fun allowsPreparation(multipleItems: Boolean = false): Boolean = Capability.PREPARE_CART in capabilities &&
        (!multipleItems || Capability.PREPARE_MULTIPLE_ITEMS in capabilities)
    fun allowsReview(multipleItems: Boolean = false): Boolean = Capability.REVIEW in capabilities &&
        (!multipleItems || Capability.REVIEW_MULTIPLE_ITEMS in capabilities)

    companion object {
        private val profiles = listOf(
            KeetaVersionProfile("3.12.501", Schema.LEGACY_312, setOf(
                Capability.READ_MENU, Capability.PREPARE_CART, Capability.REVIEW,
            )),
            KeetaVersionProfile("3.13.203", Schema.CURRENT_313, setOf(
                Capability.READ_MENU, Capability.READ_MULTIPLE_ITEMS,
                Capability.PREPARE_CART, Capability.PREPARE_OWNED_PLAIN_CART, Capability.PREPARE_MULTIPLE_ITEMS,
                Capability.REVIEW, Capability.REVIEW_MULTIPLE_ITEMS,
            )),
            // Current-layout plain-item executor; multi-row admission is verified against
            // the real two-item cart before releasing this working branch.
            KeetaVersionProfile("3.13.301", Schema.CURRENT_313, setOf(
                Capability.READ_MENU, Capability.READ_MULTIPLE_ITEMS, Capability.PREPARE_CART,
                Capability.PREPARE_OWNED_PLAIN_CART, Capability.PREPARE_MULTIPLE_ITEMS,
                Capability.REVIEW_MULTIPLE_ITEMS, Capability.REVIEW, Capability.OWNED_DRAWER_RETURN, Capability.DIRECT_DRAWER_REVIEW,
            )),
        ).associateBy { it.version }

        fun resolve(installedVersion: String?): KeetaVersionProfile? = profiles[installedVersion]
    }
}
