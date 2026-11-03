package app.octopus.local.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.octopus.local.*
import app.octopus.local.R
import app.octopus.local.automation.LiveCatalogBridge
import app.octopus.local.automation.BasketMutationJournal
import app.octopus.local.domain.CanonicalCart
import app.octopus.local.domain.ProviderId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.math.BigDecimal

@Composable fun NativeComparisonHome(graph: AppGraph, onSettings: () -> Unit, onSetup: () -> Unit) {
    val state by graph.catalog.collectAsStateWithLifecycle()
    val settings by graph.settings.collectAsStateWithLifecycle()
    val connected by graph.serviceConnected.collectAsStateWithLifecycle()
    val ready by graph.catalogReady.collectAsStateWithLifecycle()
    val cart by graph.draft.collectAsStateWithLifecycle()
    var consent by remember { mutableStateOf(false) }
    var clearedCart by remember { mutableStateOf<CanonicalCart?>(null) }
    var pickedRow by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val busy = state.busy || state.comparisonRunning
    val context = LocalContext.current
    // Keeta is left on its review and its launcher intent resumes it; HungerStation's launcher
    // reopens its home, so Octopus navigates back to the compared cart (read-only).
    fun continueIn(id: ProviderId) {
        if (id == ProviderId.HUNGERSTATION) {
            if (!LiveCatalogBridge.openComparedHungerCart() && graph.catalog.value.error == null)
                graph.catalog.value = graph.catalog.value.copy(error = "تعذر فتح سلة هنقرستيشن؛ افتحها من التطبيق مباشرة.")
            return
        }
        val launch = context.packageManager.getLaunchIntentForPackage(id.packageName)
        if (launch == null) graph.catalog.value = graph.catalog.value.copy(error = "${id.title} غير مثبت.")
        else context.startActivity(launch)
    }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(5000) } }
    fun start() {
        if (!connected) { onSetup(); return }
        if (!settings.catalogConsent) { consent = true; return }
        if (state.manualCaptureActive) LiveCatalogBridge.endManualCatalog()
        if (!LiveCatalogBridge.beginNativeCartComparison()) graph.catalog.value = graph.catalog.value.copy(
            error = "تعذر بدء هنقرستيشن؛ تحقق من ربط خدمة الأخطبوط والسماح للتطبيقين.")
    }
    fun money(amount: Long) = BigDecimal.valueOf(amount, 2).toPlainString() + " ر.س"
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
        .padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_octopus_selected), null, Modifier.size(44.dp))
            Spacer(Modifier.width(10.dp))
            Text("الأخطبوط", Modifier.weight(1f), fontSize = 27.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "الإعدادات") }
        }
        Text("جهّز طلبك في هنقرستيشن، وقارن سعره مع كيتا.", color = OctopusPalette.Muted, fontSize = 15.sp)
        // Always say in red why a comparison stopped: the general error, or else a provider's own failure reason.
        val failures = if (busy) emptyList() else state.comparisonProgress.values.filter {
            it.step in setOf(app.octopus.local.automation.CartComparisonStep.PREPARATION_FAILED,
                app.octopus.local.automation.CartComparisonStep.REVIEW_UNAVAILABLE) }
        val shownError = state.error ?: failures.takeIf { it.isNotEmpty() }?.joinToString("\n") {
            it.provider.title + ": " + (it.detail ?: "توقفت دون سبب مقروء.") }
        shownError?.let { error ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    // The message may carry the HungerStation result in front of the Keeta reason.
                    if (!busy && cart != null && setOf(BasketMutationJournal.HIDDEN,
                            BasketMutationJournal.UNRESOLVED, BasketMutationJournal.LEGACY_UNRESOLVED,
                            BasketMutationJournal.KEETA_UNREADABLE, BasketMutationJournal.KEETA_DIFFERENT).any { error.endsWith(it) }) {
                        TextButton(onClick = { clearedCart = cart }) { Text("أفرغت سلة كيتا وأريد البدء من جديد") }
                    }
                    // Keeta names the meal differently: the user picks it once; Octopus remembers it for this
                    // restaurant and compares again. Every price and cart check still applies after the pick.
                    val question = state.mealQuestion
                    if (!busy && question != null) {
                        Spacer(Modifier.height(10.dp))
                        Text("أي وجبة في كيتا هي «${question.hungerMeal}»؟", fontWeight = FontWeight.Bold)
                        question.candidates.forEach { name ->
                            OutlinedButton(onClick = {
                                graph.learnedNames.rememberKeetaMeal(question.restaurant, question.hungerMeal, name)
                                graph.catalog.value = graph.catalog.value.copy(mealQuestion = null, error = null)
                                if (!LiveCatalogBridge.compareCapturedNativeBasket() && graph.catalog.value.error == null)
                                    graph.catalog.value = graph.catalog.value.copy(error = "حفظت اختيارك؛ ابدأ من هنقرستيشن واضغط «قارن».")
                            }, modifier = Modifier.fillMaxWidth()) { Text(name) }
                        }
                        TextButton(onClick = { graph.catalog.value = graph.catalog.value.copy(mealQuestion = null) }) {
                            Text("ولا وحدة منها")
                        }
                    }
                    // A Keeta choice group HungerStation names differently or lacks: the user picks its row, then
                    // says which HungerStation choice it is (or none). Remembered for this restaurant and meal.
                    val choice = state.choiceQuestion
                    if (!busy && choice != null) {
                        fun learn(row: String, hunger: String?) {
                            graph.learnedNames.rememberChoice(choice.restaurant, choice.hungerMeal, row, hunger)
                            pickedRow = null
                            graph.catalog.value = graph.catalog.value.copy(choiceQuestion = null, error = null)
                            if (!LiveCatalogBridge.compareCapturedNativeBasket() && graph.catalog.value.error == null)
                                graph.catalog.value = graph.catalog.value.copy(error = "حفظت اختيارك؛ ابدأ من هنقرستيشن واضغط «قارن».")
                        }
                        Spacer(Modifier.height(10.dp))
                        val row = pickedRow
                        if (choice.rows.isEmpty()) {
                            Text("هذي من طلبك في هنقرستيشن وما لها مكان في «${choice.hungerMeal}» بكيتا: " +
                                choice.unmatchedHunger.joinToString("، ") { "«$it»" } + ". نتجاهلها في كيتا؟", fontWeight = FontWeight.Bold)
                            OutlinedButton(onClick = {
                                graph.learnedNames.rememberIgnored(choice.restaurant, choice.hungerMeal, choice.unmatchedHunger)
                                graph.catalog.value = graph.catalog.value.copy(choiceQuestion = null, error = null)
                                if (!LiveCatalogBridge.compareCapturedNativeBasket() && graph.catalog.value.error == null)
                                    graph.catalog.value = graph.catalog.value.copy(error = "حفظت اختيارك؛ ابدأ من هنقرستيشن واضغط «قارن».")
                            }, modifier = Modifier.fillMaxWidth()) { Text("إيه، تجاهلها") }
                        } else if (row == null) {
                            Text("في كيتا، «${choice.hungerMeal}» فيها «${choice.group}». وش تختار؟", fontWeight = FontWeight.Bold)
                            choice.rows.forEach { name ->
                                OutlinedButton(onClick = { if (choice.unmatchedHunger.isEmpty()) learn(name, null) else pickedRow = name },
                                    modifier = Modifier.fillMaxWidth()) { Text(name) }
                            }
                        } else {
                            Text("«$row» يقابل أي اختيار من طلبك في هنقرستيشن؟", fontWeight = FontWeight.Bold)
                            choice.unmatchedHunger.forEach { name ->
                                OutlinedButton(onClick = { learn(row, name) }, modifier = Modifier.fillMaxWidth()) { Text(name) }
                            }
                            OutlinedButton(onClick = { learn(row, null) }, modifier = Modifier.fillMaxWidth()) {
                                Text("ما له مقابل (اختيار زيادة في كيتا)")
                            }
                        }
                        TextButton(onClick = { pickedRow = null; graph.catalog.value = graph.catalog.value.copy(choiceQuestion = null) }) {
                            Text("ألغِ")
                        }
                    }
                }
            }
        }
        Button(onClick = ::start, enabled = !busy && ready, modifier = Modifier.fillMaxWidth().height(62.dp),
            shape = RoundedCornerShape(18.dp)) { Text("ابدأ في هنقرستيشن", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        FilledTonalButton(onClick = {
            if (!connected) onSetup()
            else if (!LiveCatalogBridge.compareCapturedNativeBasket() && graph.catalog.value.error == null)
                graph.catalog.value = graph.catalog.value.copy(error = "تعذر بدء المقارنة؛ ابدأ من هنقرستيشن وافتح سلتك أولًا.")
        }, enabled = !busy && ready, modifier = Modifier.fillMaxWidth().height(62.dp), shape = RoundedCornerShape(18.dp)) {
            Text("قارن", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.message, fontSize = 15.sp)
            state.comparisonProgress.values.forEach { progress -> Text(progress.provider.title + ": " +
                (progress.detail ?: "جارٍ تجهيز السلة وقراءة الإجمالي…"), fontSize = 13.sp) }
            TextButton(onClick = { LiveCatalogBridge.stop() }) { Text("إيقاف المقارنة") }
        } else if (state.manualCaptureActive) {
            Text("أضف وجبتك وافتح السلة في هنقرستيشن، ثم اضغط قارن.", color = OctopusPalette.Muted)
        }
        val request = cart
        val currentTime = maxOf(now, System.currentTimeMillis())
        // Keep the last result for this exact order on screen; freshness only changes its label.
        val hunger = request?.let { state.finalTotals[ProviderId.HUNGERSTATION]?.takeIf { total -> total.cartFingerprint == it.fingerprint } }
        val keeta = request?.let { state.finalTotals[ProviderId.KEETA]?.takeIf { total -> total.cartFingerprint == it.fingerprint } }
        val stale = request != null && listOfNotNull(hunger, keeta).any { !it.fresh(request, currentTime) }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = OctopusPalette.Soft)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("المقارنة", fontWeight = FontWeight.Bold, fontSize = 21.sp)
                Row { Text("هنقرستيشن", Modifier.weight(1f)); Text(hunger?.let { money(it.total) } ?: "بانتظار القراءة") }
                Row { Text("كيتا", Modifier.weight(1f)); Text(keeta?.let { money(it.total) } ?: if (!busy && state.comparisonProgress[ProviderId.KEETA]?.step ==
                    app.octopus.local.automation.CartComparisonStep.PREPARATION_FAILED) "توقفت" else "بانتظار القراءة") }
                if (hunger != null && keeta != null) {
                    HorizontalDivider()
                    val difference = hunger.total - keeta.total
                    Text(when {
                        difference > 0 -> "كيتا أوفر بـ ${money(difference)}"
                        difference < 0 -> "هنقرستيشن أوفر بـ ${money(-difference)}"
                        else -> "السعر متساوٍ"
                    }, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = OctopusPalette.Petrol)
                    Text("راجع تطابق عنوان التوصيل والفرع في التطبيقين.", color = OctopusPalette.Muted, fontSize = 12.sp)
                    val cheaper = when {
                        difference > 0 -> listOf(ProviderId.KEETA)
                        difference < 0 -> listOf(ProviderId.HUNGERSTATION)
                        else -> listOf(ProviderId.HUNGERSTATION, ProviderId.KEETA)
                    }
                    cheaper.forEach { id ->
                        Button(onClick = { continueIn(id) }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(58.dp),
                            shape = RoundedCornerShape(18.dp)) { Text("أكمل الطلب في ${id.title}", fontSize = 19.sp, fontWeight = FontWeight.Bold) }
                    }
                    Text("يفتح سلتك الجاهزة في ${cheaper.joinToString(" أو ") { it.title }}؛ تأكد من الإجمالي وأكمل الدفع بنفسك.",
                        color = OctopusPalette.Muted, fontSize = 12.sp)
                }
                if (stale) {
                    val readAt = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                        .format(java.util.Date(listOfNotNull(hunger, keeta).minOf { it.capturedAt }))
                    Text("نتيجة مقارنة الساعة $readAt؛ قد تتغير الأسعار، أعد المقارنة قبل الطلب.",
                        color = OctopusPalette.Muted, fontSize = 12.sp)
                }
            }
        }
        if (!connected) TextButton(onClick = onSetup) { Text("ربط خدمة الأخطبوط") }
    }
    clearedCart?.let { confirmed -> AlertDialog(onDismissRequest = { clearedCart = null },
        title = { Text("تأكيد إفراغ سلة كيتا") },
        text = { Text("أكد فقط إذا حذفت جميع أصناف ${confirmed.restaurant} من كيتا بنفسك. سيعيد الأخطبوط فحص السلة عند المقارنة قبل أي إضافة.") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            try { graph.confirmProviderCartCleared(ProviderId.KEETA, confirmed.fingerprint) }
            catch (_: Exception) { graph.catalog.value = graph.catalog.value.copy(error = "تغير الطلب أو توجد عملية نشطة؛ لم أعتمد إفراغ السلة.") }
            clearedCart = null
        }) { Text("نعم، حذفت جميع أصناف هذا المطعم") } },
        dismissButton = { TextButton(onClick = { clearedCart = null }) { Text("رجوع") } }) }
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("السماح بالمقارنة") },
        text = { Text("يفتح الأخطبوط هنقرستيشن ويقرأ السلة التي تختارها، ثم يجهز المطابق في كيتا ويقرأ الإجماليين. لا يؤكد طلبًا أو دفعًا. يمكنك إيقاف المقارنة أثناء التنفيذ.") },
        confirmButton = { TextButton(onClick = { consent = false; graph.scope.launch {
            graph.settingsStore.acceptCatalogConsent(); graph.settings.first { it.catalogConsent }
            LiveCatalogBridge.beginNativeCartComparison()
        } }) { Text("أوافق وأبدأ") } }, dismissButton = { TextButton(onClick = { consent = false }) { Text("إلغاء") } })
}
