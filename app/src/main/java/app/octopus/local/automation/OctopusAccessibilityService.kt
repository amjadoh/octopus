package app.octopus.local.automation

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.*
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import app.octopus.local.*
import app.octopus.local.data.DiagnosticReport
import app.octopus.local.domain.*
import app.octopus.local.providers.adapterFor
import app.octopus.local.safety.*
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.UUID

object ReadOnlyServiceBridge {
    private var service = WeakReference<OctopusAccessibilityService>(null)
    fun attach(value: OctopusAccessibilityService?) { service = WeakReference(value) }
    fun start(provider: ProviderId): Boolean = service.get()?.startRead(provider) ?: false
    fun stop() { service.get()?.stopRead("USER_STOPPED", "أوقفت القراءة. لم تُعدّل أي سلة.") }
}

class OctopusAccessibilityService : AccessibilityService() {
    private val graph get() = (application as OctopusApplication).graph
    private val handler = Handler(Looper.getMainLooper())
    private val guard = SafetyGuard()
    private val reader = SnapshotReader()
    private val stability = StabilityGate()
    private var sessionId: String? = null
    private var provider: ProviderId? = null
    private var deadline = 0L
    private var sawTarget = false
    private var lastVisibleNodes = 0
    private var overlay: Button? = null
    private val poll = object : Runnable {
        override fun run() {
            val token = sessionId ?: return
            sample(token)
            if (sessionId == token) handler.postDelayed(this, 650)
        }
    }

    override fun onServiceConnected() {
        stopRead("SERVICE_RECONNECTED", "أعيد اتصال الخدمة؛ لن تُستأنف القراءة تلقائيًا.")
        ReadOnlyServiceBridge.attach(this)
        graph.serviceConnected.value = true
        // A service reconnect must never resume a previous session.
        graph.session.value = SessionState()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Contents of AccessibilityEvent are not copied or logged.
        // Sampling verifies the current foreground package independently.
    }
    override fun onInterrupt() { stopRead("SERVICE_INTERRUPTED", "توقفت الخدمة؛ ابدأ اختبارًا جديدًا عند العودة.") }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        stopRead("LAYOUT_CHANGED", "تغير تخطيط الشاشة؛ أعد الاختبار بعد استقرار وضع الجهاز.")
    }
    override fun onDestroy() {
        stopRead("SERVICE_DISCONNECTED", "انقطع اتصال الخدمة.")
        graph.serviceConnected.value = false
        ReadOnlyServiceBridge.attach(null)
        super.onDestroy()
    }

    fun startRead(selected: ProviderId): Boolean {
        if (sessionId != null) return false
        val settings = graph.settings.value
        graph.refreshInstallations()
        if (!settings.consent || selected !in settings.allowed || graph.installations.value.none { it.provider == selected && it.installed }) return false
        val launch = packageManager.getLaunchIntentForPackage(selected.packageName) ?: return false
        provider = selected
        sessionId = UUID.randomUUID().toString()
        deadline = SystemClock.elapsedRealtime() + 90_000
        sawTarget = false
        lastVisibleNodes = 0
        stability.reset()
        graph.session.value = SessionState(SessionPhase.WAITING_FOR_SCREEN, selected, "افتح شاشة مراجعة الطلب يدويًا؛ القراءة فقط نشطة.", 90)
        if (!showStopControl()) {
            stopRead("STOP_CONTROL_UNAVAILABLE", "لم يمكن إظهار زر الإيقاف؛ لم تبدأ القراءة.")
            return false
        }
        return try {
            launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
            handler.postDelayed(poll, 700)
            true
        } catch (_: Exception) {
            stopRead("APP_OPEN_FAILED", "تعذر فتح التطبيق. لا توجد قراءة مؤكدة.")
            false
        }
    }

    private fun sample(token: String) {
        if (sessionId != token) return
        val selected = provider ?: return
        val seconds = ((deadline - SystemClock.elapsedRealtime()) / 1000).toInt().coerceAtLeast(0)
        if (seconds == 0) { stopRead("TIMEOUT", "انتهت المهلة دون إجمالي مستقر قابل للقراءة."); return }
        graph.session.value = graph.session.value.copy(remainingSeconds = seconds)
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (keyguard.isKeyguardLocked || !power.isInteractive) { stopRead("DEVICE_LOCKED", "توقفت القراءة لأن الشاشة مقفلة."); return }
        val settings = graph.settings.value
        if (!settings.consent || selected !in settings.allowed) { stopRead("CONSENT_REVOKED", "توقفت القراءة بعد تغيير السماح."); return }
        val root = rootInActiveWindow ?: run { stability.reset(); return }
        try {
            val foregroundPackage = root.packageName?.toString()
            if (!guard.allowsRead(selected, foregroundPackage, sessionId == token, settings.consent, selected in settings.allowed)) {
                stability.reset()
                if (sawTarget) stopRead("LEFT_TARGET_APP", "توقفت القراءة بعد مغادرة التطبيق المستهدف.")
                return
            }
            sawTarget = true
            val snapshot = reader.read(root)
            lastVisibleNodes = snapshot.visibleNodeCount
            if (snapshot.sensitiveScreen) { stopRead("SENSITIVE_SCREEN", "شاشة دخول أو بيانات حساسة؛ أكمل يدويًا ثم ابدأ اختبارًا جديدًا."); return }
            val candidate = adapterFor(selected).extract(snapshot)
            val candidateTotal = candidate.total
            if (candidateTotal == null || candidateTotal < 0 || snapshot.truncated) {
                stability.reset()
                graph.session.value = graph.session.value.copy(phase = SessionPhase.WAITING_FOR_SCREEN, message = "بانتظار إجمالي واضح في شاشة مراجعة الطلب؛ لن ينفذ التطبيق أي نقرة.")
                return
            }
            graph.session.value = graph.session.value.copy(phase = SessionPhase.READING, message = "وُجد إجمالي مرشح؛ جارٍ التحقق من استقرار القراءة.")
            if (stability.observe(candidate.fingerprint, SystemClock.elapsedRealtime()) && sessionId == token) {
                finish("STABLE_CANDIDATE", "حُفظ إجمالي مرشح. السلة والعنوان والسعر النهائي لم تُتحقق بعد.", candidate)
            }
        } finally { @Suppress("DEPRECATION") root.recycle() }
    }

    fun stopRead(code: String, message: String) { if (sessionId != null) finish(code, message, null) }

    private fun finish(code: String, message: String, candidate: QuoteCandidate?) {
        val id = sessionId ?: return
        val selected = provider ?: return
        // Invalidate before callbacks, database writes, or UI changes.
        sessionId = null
        provider = null
        handler.removeCallbacks(poll)
        hideStopControl()
        stability.reset()
        val installation = graph.installations.value.firstOrNull { it.provider == selected }
        val fields = JSONObject()
        candidate?.fields?.forEach { (key, value) -> fields.put(key.name, value) }
        val report = DiagnosticReport(id, selected.name, installation?.version ?: "unknown", adapterFor(selected).adapterVersion,
            System.currentTimeMillis(), code, candidate?.total, fields.toString(), lastVisibleNodes)
        graph.scope.launch { graph.recorder.record(report) }
        graph.session.value = SessionState(if (candidate != null) SessionPhase.COMPLETED else SessionPhase.STOPPED, selected, message)
    }

    private fun showStopControl(): Boolean = try {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val button = Button(this).apply {
            text = "إيقاف القراءة ×"
            contentDescription = "إيقاف جلسة قراءة الأخطبوط فورًا"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.rgb(147, 43, 43))
            setOnClickListener { stopRead("USER_STOPPED", "أوقفت القراءة. لم تُعدّل أي سلة.") }
        }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (52 * resources.displayMetrics.density).toInt() }
        windowManager.addView(button, params)
        overlay = button
        true
    } catch (_: Exception) { false }

    private fun hideStopControl() {
        overlay?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        overlay = null
    }
}
