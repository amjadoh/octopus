package app.octopus.local.automation

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import app.octopus.local.domain.ProviderId
import app.octopus.local.providers.*
import app.octopus.local.safety.SafetyGuard
import app.octopus.local.safety.ProviderAccessRestrictions
import kotlinx.coroutines.*

/**
 * Actual Android element I/O for the recorded HS product shape. Keeta/review navigation is not
 * implemented here. Not exposed through a debug broadcast, generic click API or automatic startup.
 */
class AndroidProductElements(
    private val service: AccessibilityService,
    private val program: HungerStationProductProgram,
    private val sessionAllowed: () -> Boolean,
) {
    private data class Screen(val frame: CatalogFrame, val paths: List<List<Int>>, val window: Int, val configuration: Int)
    private var stable: Screen? = null

    suspend fun stableFrame(stop: StopSignal): CatalogFrame = withTimeout(12_000) {
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

    suspend fun execute(frame: CatalogFrame, command: ProductCommand, stop: StopSignal): Boolean {
        checkActive(stop)
        val expected = stable ?: return false
        if (expected.frame != frame || capture() != expected) return false
        if (!SafetyGuard().allowsProductElement(command, frame, ProviderId.HUNGERSTATION.packageName,
                installedVersion(), sessionAllowed(), !stop.stopped, program)) return false
        val node = when (command) {
            is ProductCommand.Choose -> command.node
            is ProductCommand.Scroll -> command.node
            is ProductCommand.Add -> command.node
            else -> return false
        }
        val root = service.rootInActiveWindow ?: return false
        try {
            if (root.packageName?.toString() != ProviderId.HUNGERSTATION.packageName || root.windowId != expected.window ||
                service.resources.configuration.hashCode() != expected.configuration) return false
            @Suppress("DEPRECATION")
            var target = AccessibilityNodeInfo.obtain(root)
            try {
                for (index in expected.paths[node.index]) {
                    val child = target.getChild(index) ?: return false
                    @Suppress("DEPRECATION") target.recycle()
                    target = child
                }
                if (!target.refresh() || !target.isVisibleToUser || !target.isEnabled || target.isEditable || target.isPassword ||
                    (Build.VERSION.SDK_INT >= 34 && target.isAccessibilityDataSensitive) ||
                    target.className?.toString() != node.className ||
                    target.viewIdResourceName.orEmpty() != node.id ||
                    (target.text ?: target.contentDescription)?.toString().orEmpty() != node.label) return false
                checkActive(stop)
                val current = service.rootInActiveWindow ?: return false
                try {
                    if (current.packageName?.toString() != ProviderId.HUNGERSTATION.packageName || current.windowId != expected.window ||
                        service.resources.configuration.hashCode() != expected.configuration) return false
                } finally { @Suppress("DEPRECATION") current.recycle() }
                val action = if (command is ProductCommand.Scroll) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_CLICK
                if (command is ProductCommand.Scroll && !target.isScrollable || command !is ProductCommand.Scroll && !target.isClickable) return false
                if (!program.markAttempted(command, System.currentTimeMillis())) return false
                stable = null // An action consumes this observation, even if Android reports failure.
                return target.performAction(action)
            } finally { @Suppress("DEPRECATION") target.recycle() }
        } finally { @Suppress("DEPRECATION") root.recycle() }
    }

    private suspend fun checkActive(stop: StopSignal) {
        currentCoroutineContext().ensureActive()
        check(!stop.stopped && sessionAllowed())
        check(!(service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked)
        check(installedVersion() == HungerStationCatalog.TESTED_VERSION)
    }

    private fun installedVersion(): String? = runCatching {
        @Suppress("DEPRECATION")
        service.packageManager.getPackageInfo(ProviderId.HUNGERSTATION.packageName, 0).versionName
    }.getOrNull()

    private fun capture(): Screen {
        val root = service.rootInActiveWindow ?: error("No window")
        try {
            check(root.packageName?.toString() == ProviderId.HUNGERSTATION.packageName)
            val nodes = mutableListOf<CatalogNode>()
            val paths = mutableListOf<List<Int>>()
            val privateIds = Regex("address|location|recipient|phone|email|password|otp|verification|contact|card|payment|checkout|cvv", RegexOption.IGNORE_CASE)
            fun visit(node: AccessibilityNodeInfo, parent: Int, path: List<Int>) {
                if (!node.isVisibleToUser) return
                check(path.size <= 40 && nodes.size < 1800 && node.childCount <= 300)
                check(!node.isPassword && !(Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive))
                val id = node.viewIdResourceName.orEmpty()
                check(!privateIds.containsMatchIn(id) && !node.isEditable)
                val label = (node.text ?: node.contentDescription)?.toString().orEmpty()
                check(label.length <= 1000)
                ProviderAccessRestrictions.detect(label)?.let { error(it.userMessage) }
                ProviderAccessRestrictions.detect(node.contentDescription)?.let { error(it.userMessage) }
                check(!Regex("تسجيل الدخول|رمز التحقق|كلمة المرور|captcha|password|verification code", RegexOption.IGNORE_CASE).containsMatchIn(label))
                val index = nodes.size
                nodes += CatalogNode(index, parent, id, label, node.className?.toString().orEmpty(),
                    node.isClickable, false, node.isScrollable, node.isEnabled)
                paths += path
                for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
                    try { visit(child, index, path + i) } finally { @Suppress("DEPRECATION") child.recycle() }
                }
            }
            visit(root, -1, emptyList())
            val frame = CatalogFrame(nodes, true)
            check(HungerStationCatalog.screen(frame) == CatalogScreen.PRODUCT)
            return Screen(frame, paths, root.windowId, service.resources.configuration.hashCode())
        } finally { @Suppress("DEPRECATION") root.recycle() }
    }
}
