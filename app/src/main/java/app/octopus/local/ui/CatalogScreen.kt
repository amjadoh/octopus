package app.octopus.local.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.octopus.local.AppGraph
import app.octopus.local.automation.LiveCatalogBridge
import app.octopus.local.domain.ProviderId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.math.BigDecimal

@Composable
fun CatalogScreen(graph: AppGraph, onSetup: () -> Unit, onProviders: () -> Unit) {
    val state by graph.catalog.collectAsStateWithLifecycle()
    val settings by graph.settings.collectAsStateWithLifecycle()
    val connected by graph.serviceConnected.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    fun search() {
        if (!LiveCatalogBridge.search(query)) localError = "لم يبدأ البحث. تحقق من اتصال الخدمة والسماح لهنقرستيشن وإصداره المدعوم."
    }
    LazyColumn(contentPadding = PaddingValues(21.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("المطاعم", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { Text("ابحث عن مطعمك واختر من قائمته المقروءة من التطبيق.") }
        item { Text("مصدر القائمة: هنقرستيشن • عنوان التوصيل النشط في المنصة", style = MaterialTheme.typography.bodySmall) }
        item { OutlinedCard(Modifier.fillMaxWidth()) {
            Text("إصدار تجريبي لبحث المطاعم والقوائم. المقارنة بين كيتا وهنقرستيشن وتجهيز السلتين لم تكتمل بعد.",
                modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
        } }
        item { OutlinedTextField(value = query, onValueChange = { if (it.length <= 100) query = it },
            enabled = !state.busy, singleLine = true, label = { Text("اسم المطعم") }, modifier = Modifier.fillMaxWidth()) }
        item { Button(onClick = {
            localError = null
            when {
                !connected -> onSetup()
                ProviderId.HUNGERSTATION !in settings.allowed -> onProviders()
                !settings.catalogConsent -> consent = true
                else -> search()
            }
        }, enabled = query.isNotBlank() && !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (!connected) "إكمال إعداد الخدمة" else "ابحث عن المطاعم")
        } }
        if (state.busy) item {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.message)
            OutlinedButton(onClick = { LiveCatalogBridge.stop() }, modifier = Modifier.fillMaxWidth()) { Text("إيقاف البحث") }
        } else item { Text(state.message, style = MaterialTheme.typography.bodySmall) }
        (localError ?: state.error)?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
        if (state.menu == null) items(state.restaurants, key = { it.name }) { restaurant ->
            OutlinedCard(modifier = Modifier.fillMaxWidth(), onClick = {
                if (!LiveCatalogBridge.openMenu(restaurant)) localError = "تعذر فتح القائمة. تحقق من الخدمة أو أعد البحث."
            }, enabled = !state.busy) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(restaurant.name, fontWeight = FontWeight.Bold)
                    restaurant.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text("استعرض القائمة", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        state.menu?.let { menu ->
            item { Text(menu.restaurant.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            item { Text(if (menu.reachedEnd) "قائمة مقروءة من المنصة" else "قائمة جزئية", style = MaterialTheme.typography.bodySmall) }
            items(menu.items, key = { it.selector }) { item ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(item.name, modifier = Modifier.weight(1f))
                        Text(item.listedPrice?.let { BigDecimal.valueOf(it, 2).toPlainString() + " ر.س" } ?: "السعر غير مقروء")
                    }
                }
            }
            item { Text("أسعار القائمة لا تشمل بالضرورة رسوم التوصيل. تجهيز السلة والمقارنة بين المنصتين قيد التطوير، ولن يُعرض إجمالي مؤكد قبل التحقق.", style = MaterialTheme.typography.bodySmall) }
        }
        if (!state.busy && state.restaurants.isEmpty() && state.menu == null) item {
            Text("تفتح المنصة مؤقتًا لجلب نتائج بحثك، ثم تعود إلى الأخطبوط. لا توجد قوائم أو أسعار تجريبية.", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("السماح ببحث المطاعم والقوائم") },
        text = { Text("سيفتح الأخطبوط هنقرستيشن، ويدخل اسم المطعم ويقرأ النتائج والقائمة الظاهرة ثم يعود إليك. تسمح هذه الموافقة بالتنقل والبحث والتمرير فقط؛ لا تشمل تعديل السلة أو إنشاء طلب أو دفع. تبقى البيانات محلية، ويمكن الإيقاف من الزر الظاهر فوق المنصة.") },
        confirmButton = { TextButton(onClick = {
            consent = false
            graph.scope.launch {
                graph.settingsStore.acceptCatalogConsent()
                graph.settings.first { it.catalogConsent }
                search()
            }
        }) { Text("أوافق، ابدأ البحث") } },
        dismissButton = { TextButton(onClick = { consent = false }) { Text("إلغاء") } })
}
