package app.octopus.local.automation

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import app.octopus.local.domain.ProviderId
import app.octopus.local.providers.*
import app.octopus.local.safety.ProviderAccessRestrictions
import app.octopus.local.safety.SafetyGuard
import kotlinx.coroutines.*

/** Callable Android path for the known post-add basket control. Does not launch or modify a basket. */
class AndroidHungerStationBasketNavigator(
    private val service: AccessibilityService,
    private val sessionIsAllowed: () -> Boolean,
) {
    suspend fun readVisibleBasket(expectedRestaurant: String, stop: StopSignal): BasketNavigationResult {
        val elements = Elements()
        return try { HungerStationBasketNavigation().read(expectedRestaurant, elements, stop) }
        finally { elements.clear() }
    }

    private data class Screen(val frame: CatalogFrame, val paths: List<List<Int>>, val window: Int, val configuration: Int)

    private inner class Elements : BasketNavigationPort {
        private var stable: Screen? = null
        fun clear() { stable = null }

        override suspend fun stableFrame(stop: StopSignal): CatalogFrame = withTimeout(12_000) {
            var previous: Screen? = null
            var since = 0L
            while (true) {
                checkActive(stop)
                val current = capture()
                if (current == previous) {
                    if (SystemClock.elapsedRealtime() - since >= 650) {
                        stable = current
                        return@withTimeout current.frame
                    }
                } else { previous = current; since = SystemClock.elapsedRealtime() }
                delay(150)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }

        override suspend fun openCart(command: BasketOpenCommand, stop: StopSignal): Boolean {
            checkActive(stop)
            val expected = stable ?: return false
            if (expected.frame != command.frame || capture() != expected) return false
            val node = command.node
            val path = expected.paths.getOrNull(node.index) ?: return false
            val root = service.rootInActiveWindow ?: return false
            try {
                if (root.packageName?.toString() != ProviderId.HUNGERSTATION.packageName || root.windowId != expected.window ||
                    service.resources.configuration.hashCode() != expected.configuration) return false
                @Suppress("DEPRECATION") var target = AccessibilityNodeInfo.obtain(root)
                try {
                    for (index in path) {
                        val child = target.getChild(index) ?: return false
                        @Suppress("DEPRECATION") target.recycle()
                        target = child
                    }
                    if (!target.refresh() || !target.isVisibleToUser || !target.isEnabled || !target.isClickable ||
                        target.isEditable || target.isPassword || (Build.VERSION.SDK_INT >= 34 && target.isAccessibilityDataSensitive) ||
                        target.viewIdResourceName.orEmpty() != node.id || target.className?.toString() != node.className ||
                        (target.text?.toString()?.takeIf(String::isNotBlank) ?: target.contentDescription?.toString()).orEmpty() != node.label) return false
                    checkActive(stop)
                    // Re-read the whole window immediately before consuming the only permitted action.
                    if (capture() != expected) return false
                    if (!SafetyGuard().allowsBasketOpen(command, expected.frame, root.packageName?.toString(),
                            installedVersion(), sessionIsAllowed(), !stop.stopped)) return false
                    if (!command.consume(expected.frame, installedVersion(), sessionIsAllowed() && !stop.stopped, System.currentTimeMillis())) return false
                    stable = null
                    return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } finally { @Suppress("DEPRECATION") target.recycle() }
            } catch (failure: Exception) {
                stable = null
                throw failure
            } finally { @Suppress("DEPRECATION") root.recycle() }
        }

        private suspend fun checkActive(stop: StopSignal) {
            currentCoroutineContext().ensureActive()
            check(!stop.stopped && sessionIsAllowed())
            check(!(service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked)
            check(installedVersion() == HungerStationCatalog.TESTED_VERSION)
        }

        private fun capture(): Screen {
            val root = service.rootInActiveWindow ?: error("No window")
            try {
                check(root.packageName?.toString() == ProviderId.HUNGERSTATION.packageName)
                val nodes = mutableListOf<CatalogNode>()
                val paths = mutableListOf<List<Int>>()
                val privateIds = Regex("address|location|recipient|phone|email|password|otp|verification|contact|wallet|card|payment|checkout|cvv", RegexOption.IGNORE_CASE)
                fun visit(node: AccessibilityNodeInfo, parent: Int, path: List<Int>) {
                    if (!node.isVisibleToUser) return
                    check(path.size <= 40 && nodes.size < 1800 && node.childCount <= 300)
                    check(!node.isPassword && !(Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive))
                    val id = node.viewIdResourceName.orEmpty()
                    val omitted = node.isEditable || privateIds.containsMatchIn(id)
                    // Do not read text from private or editable fields, including cart instructions.
                    val text = if (omitted) null else node.text?.toString()
                    val description = if (omitted) null else node.contentDescription?.toString()
                    check(ProviderAccessRestrictions.detect(text) == null && ProviderAccessRestrictions.detect(description) == null)
                    val label = text?.takeIf(String::isNotBlank) ?: description.orEmpty()
                    check(label.length <= 1000)
                    check(!Regex("تسجيل الدخول|رمز التحقق|كلمة المرور|captcha|password|verification code", RegexOption.IGNORE_CASE).containsMatchIn(label))
                    val index = nodes.size
                    nodes += CatalogNode(index, parent, id, label, node.className?.toString().orEmpty(),
                        node.isClickable, node.isEditable, node.isScrollable, node.isEnabled)
                    paths += path
                    if (omitted) return
                    for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
                        try { visit(child, index, path + i) } finally { @Suppress("DEPRECATION") child.recycle() }
                    }
                }
                visit(root, -1, emptyList())
                val frame = CatalogFrame(nodes, true)
                check(HungerStationCatalog.screen(frame) in setOf(CatalogScreen.MENU, CatalogScreen.CART))
                return Screen(frame, paths, root.windowId, service.resources.configuration.hashCode())
            } catch (failure: Exception) {
                stable = null
                throw failure
            } finally { @Suppress("DEPRECATION") root.recycle() }
        }
    }

    private fun installedVersion(): String? = runCatching {
        @Suppress("DEPRECATION") service.packageManager.getPackageInfo(ProviderId.HUNGERSTATION.packageName, 0).versionName
    }.getOrNull()
}
