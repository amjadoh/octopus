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
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val busy = state.busy || state.comparisonRunning
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
        state.error?.let { error ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    if (!busy && cart != null && error in setOf(BasketMutationJournal.HIDDEN,
                            BasketMutationJournal.UNRESOLVED, BasketMutationJournal.LEGACY_UNRESOLVED)) {
                        TextButton(onClick = { clearedCart = cart }) { Text("أفرغت سلة كيتا وأريد البدء من جديد") }
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
        val hunger = request?.let { state.finalTotals[ProviderId.HUNGERSTATION]?.takeIf { total -> total.fresh(it, currentTime) } }
        val keeta = request?.let { state.finalTotals[ProviderId.KEETA]?.takeIf { total -> total.fresh(it, currentTime) } }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = OctopusPalette.Soft)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("المقارنة", fontWeight = FontWeight.Bold, fontSize = 21.sp)
                Row { Text("هنقرستيشن", Modifier.weight(1f)); Text(hunger?.let { money(it.total) } ?: "بانتظار القراءة") }
                Row { Text("كيتا", Modifier.weight(1f)); Text(keeta?.let { money(it.total) } ?: "بانتظار القراءة") }
                if (hunger != null && keeta != null) {
                    HorizontalDivider()
                    val difference = hunger.total - keeta.total
                    Text(when {
                        difference > 0 -> "كيتا أوفر بـ ${money(difference)}"
                        difference < 0 -> "هنقرستيشن أوفر بـ ${money(-difference)}"
                        else -> "السعر متساوٍ"
                    }, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = OctopusPalette.Petrol)
                    Text("راجع تطابق عنوان التوصيل والفرع في التطبيقين.", color = OctopusPalette.Muted, fontSize = 12.sp)
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
