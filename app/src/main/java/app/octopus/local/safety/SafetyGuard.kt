package app.octopus.local.safety

import app.octopus.local.domain.ProviderId
import app.octopus.local.providers.ProviderPolicies
import app.octopus.local.providers.CatalogScreen
import app.octopus.local.providers.CatalogFrame
import app.octopus.local.providers.HungerStationCatalog
import app.octopus.local.automation.ProductCommand
import app.octopus.local.automation.HungerStationProductProgram
import app.octopus.local.automation.BasketOpenCommand
import app.octopus.local.providers.KeetaSearchAction
import app.octopus.local.providers.KeetaMenuReader
import app.octopus.local.providers.CatalogNode

enum class ProposedAction { CLICK, SCROLL, INPUT_TEXT, GESTURE, GLOBAL_ACTION, CREATE_ORDER, PAYMENT }
enum class CatalogAction { CLOSE_HUNGER_VARIANT, OPEN_BROWSE, CLOSE_BROWSE_PROMO, CLOSE_SPIN_PROMO, SCROLL_RESTAURANTS, OPEN_SEARCH, SET_QUERY, SELECT_SUGGESTION, OPEN_RESTAURANT, VIEW_MENU_NOTICE, SCROLL_MENU, OPEN_PRODUCT, SCROLL_PRODUCT, RETURN_TO_CATALOG, ADD_RIDAN, OPEN_BASKET, ADJUST_RIDAN_QUANTITY }

class SafetyGuard {
    fun allowsHungerMenuSearch(action:app.octopus.local.automation.HungerMenuSearchAction,
        session:app.octopus.local.automation.HungerMenuSearchSession,frame:CatalogFrame,node:CatalogNode,
        cart:app.octopus.local.domain.CanonicalCart,foregroundPackage:String?,version:String?,
        consent:Boolean,active:Boolean,now:Long):Boolean = consent && active && frame.complete &&
        foregroundPackage==ProviderId.HUNGERSTATION.packageName && version==HungerStationCatalog.TESTED_VERSION &&
        frame.nodes.none {ProviderAccessRestrictions.detect(it.label)!=null} &&
        session.target(action,frame,cart,version,now)==node

    fun allowsSpinPromoClose(frame: CatalogFrame, node: CatalogNode, foregroundPackage: String?,
        version: String?, consent: Boolean, active: Boolean): Boolean =
        consent && active && foregroundPackage == ProviderId.HUNGERSTATION.packageName &&
            version == HungerStationCatalog.TESTED_VERSION &&
            app.octopus.local.providers.HungerStationSpinPromo.closeTarget(frame) == node &&
            !Regex("تأكيد|دفع|شراء|pay|checkout|payment|confirm", RegexOption.IGNORE_CASE).containsMatchIn(node.label) &&
            CatalogFinancialBoundary.classify(node.id, node.className, node.clickable, node.editable,
                ProviderId.HUNGERSTATION) == CatalogFinancialBoundary.Decision.NORMAL
    fun allowsKeetaSearch(action: KeetaSearchAction, reader: KeetaMenuReader, frame: CatalogFrame,
        node: CatalogNode, foregroundPackage: String?, version: String?, consent: Boolean, active: Boolean): Boolean =
        consent && active && frame.complete && foregroundPackage == ProviderId.KEETA.packageName &&
            version == reader.VERSION && frame.nodes.none { ProviderAccessRestrictions.detect(it.label) != null } &&
            reader.searchTarget(frame, action) == node && node.enabled &&
            if (action == KeetaSearchAction.ENTER_QUERY) node.editable else node.clickable && !node.editable
    // Unscoped actions stay denied; typed operations must pass their own bounded admission checks.
    fun allowsAction(@Suppress("UNUSED_PARAMETER") action: ProposedAction): Boolean = false
    /** Opening the evidenced menu basket control never authorizes checkout or basket mutation. */
    fun allowsBasketOpen(command: BasketOpenCommand, frame: CatalogFrame, foregroundPackage: String?,
        version: String?, consent: Boolean, active: Boolean, now: Long = System.currentTimeMillis()): Boolean =
        foregroundPackage == ProviderId.HUNGERSTATION.packageName &&
            frame.nodes.none { ProviderAccessRestrictions.detect(it.label) != null } &&
            command.authorizes(frame, version, consent && active, now)
    /** Narrow product actions only. This does not authorize checkout, basket replacement or global clicks. */
    fun allowsProductElement(command: ProductCommand, frame: CatalogFrame, foregroundPackage: String?,
        version: String?, consent: Boolean, active: Boolean, program: HungerStationProductProgram? = null,
        now: Long = System.currentTimeMillis()): Boolean {
        if (!consent || !active || foregroundPackage != ProviderId.HUNGERSTATION.packageName ||
            frame.nodes.any { ProviderAccessRestrictions.detect(it.label) != null } ||
            version != HungerStationCatalog.TESTED_VERSION || !frame.complete ||
            HungerStationCatalog.screen(frame) != CatalogScreen.PRODUCT || program?.authorizes(command, now) != true ||
            frame.nodes.count { !it.clickable && it.label.trim() == "دجاج مظبي" } != 1) return false
        val node = when (command) {
            is ProductCommand.Choose -> command.node
            is ProductCommand.Scroll -> command.node
            is ProductCommand.Add -> command.node
            else -> return false
        }
        if (!node.enabled || frame.nodes.singleOrNull { it.index == node.index } != node) return false
        return when (command) {
            is ProductCommand.Choose -> node.clickable && node.id.isEmpty() &&
                command.group in setOf("اختر الحجم", "إضافات") &&
                (if (command.group == "اختر الحجم") command.value == "نص"
                    else command.value in setOf("رز بشاور", "رز شعبي", "رز مندي")) &&
                node.label.lineSequence().first().trim() == command.value
            is ProductCommand.Scroll -> node.scrollable && node.className == "android.widget.ScrollView"
            is ProductCommand.Add -> node.clickable && node.className == "android.widget.Button" &&
                node.id == "flutterAddButton" && HungerStationProductProgram.isAddLabel(node.label)
            else -> false
        }
    }
    fun allowsCatalogAction(action: CatalogAction, screen: CatalogScreen, foregroundPackage: String?,
        consent: Boolean, active: Boolean, complete: Boolean, label: String): Boolean {
        if (!consent || !active || !complete || foregroundPackage != ProviderId.HUNGERSTATION.packageName) return false
        if (Regex("تأكيد|إتمام|اتمام|ادفع|دفع|شراء|اشتراك|place order|pay now|confirm|subscribe", RegexOption.IGNORE_CASE).containsMatchIn(label)) return false
        return when (action) {
            CatalogAction.ADD_RIDAN, CatalogAction.OPEN_BASKET, CatalogAction.ADJUST_RIDAN_QUANTITY,
            CatalogAction.CLOSE_HUNGER_VARIANT, CatalogAction.CLOSE_SPIN_PROMO -> false // Separately issued typed checks only.
            CatalogAction.OPEN_BROWSE -> screen == CatalogScreen.HOME
            CatalogAction.CLOSE_BROWSE_PROMO -> screen == CatalogScreen.BROWSE_PROMO
            CatalogAction.SCROLL_RESTAURANTS -> screen == CatalogScreen.BROWSE
            CatalogAction.OPEN_SEARCH -> screen == CatalogScreen.HOME
            CatalogAction.SET_QUERY, CatalogAction.SELECT_SUGGESTION -> screen == CatalogScreen.SEARCH
            CatalogAction.OPEN_RESTAURANT -> screen == CatalogScreen.RESULTS
            CatalogAction.VIEW_MENU_NOTICE -> screen == CatalogScreen.RESTAURANT_NOTICE && label == "عرض قائمة المطعم"
            CatalogAction.SCROLL_MENU -> screen == CatalogScreen.MENU
            CatalogAction.OPEN_PRODUCT -> screen == CatalogScreen.MENU
            CatalogAction.SCROLL_PRODUCT -> screen == CatalogScreen.PRODUCT
            CatalogAction.RETURN_TO_CATALOG -> screen in setOf(CatalogScreen.PRODUCT, CatalogScreen.CART,
                CatalogScreen.MENU, CatalogScreen.RESULTS, CatalogScreen.SEARCH, CatalogScreen.BROWSE)
        }
    }
    fun allowsRead(provider: ProviderId, foregroundPackage: String?, active: Boolean, consent: Boolean, allowed: Boolean): Boolean =
        ProviderPolicies.forProvider(provider).permitsDiagnosticRead &&
            active && consent && allowed && foregroundPackage == provider.packageName
}

class StabilityGate(private val minimumDurationMillis: Long = 1_000) {
    private var fingerprint: String? = null
    private var firstSeen = 0L
    fun observe(value: String, atMillis: Long): Boolean {
        if (value != fingerprint) { fingerprint = value; firstSeen = atMillis; return false }
        return atMillis - firstSeen >= minimumDurationMillis
    }
    fun reset() { fingerprint = null; firstSeen = 0L }
}
