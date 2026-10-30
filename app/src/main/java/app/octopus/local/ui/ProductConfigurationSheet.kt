package app.octopus.local.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.octopus.local.AppGraph
import app.octopus.local.domain.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductConfigurationSheet(graph: AppGraph, details: ConfiguredProduct, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var choices by remember(details.source.screenFingerprint) { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    var quantity by remember(details.source.screenFingerprint) { mutableIntStateOf(1) }
    var replacing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val selection = ProductSelection(details.product.id, choices, quantity)
    val valid = MenuSelectionValidator().selectIntent(details, selection) is SelectionResult.Accepted
    fun save(replace: Boolean) {
        saving = true
        scope.launch {
            try {
                when (val result = graph.addDraftProduct(details, selection, replace)) {
                    is DraftChange.Changed -> { onSaved(); onDismiss() }
                    DraftChange.RestaurantReplacementRequired -> replacing = true
                    is DraftChange.Rejected -> error = result.reason
                }
            } catch (_: Exception) { error = "تعذر حفظ الطلب محليًا؛ لم تتغير سلال التطبيقات." }
            finally { saving = false }
        }
    }
    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 580.dp), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(details.product.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            item { Text("${details.restaurant} • اختيار للطلب داخل الأخطبوط؛ لا تُعدّل سلة المنصة الآن.", style = MaterialTheme.typography.bodySmall) }
            item { Text("خيارات من قائمة محفوظة؛ يُعاد التحقق من السعر والتوفر عند المقارنة.", style = MaterialTheme.typography.bodySmall) }
            if (!details.optionsComplete) item { Text("خيارات الوجبة لم تُقرأ كاملة أو قواعدها غير مدعومة. لا يمكن إضافتها بخيارات ناقصة.", color = MaterialTheme.colorScheme.error) }
            if (!details.product.available) item { Text("الوجبة غير متاحة في القراءة الحالية.", color = MaterialTheme.colorScheme.error) }
            details.product.groups.forEach { group ->
                item(key = "group:${group.id}") {
                    Text("${group.title} — " + if (group.minimum == 0) "اختياري، اختيار واحد كحد أقصى" else "اختيار إلزامي", fontWeight = FontWeight.Bold)
                    group.choices.forEach { choice ->
                        val selected = choice.id in choices[group.id].orEmpty()
                        fun choose() {
                            choices = choices + (group.id to if (group.minimum == 0 && selected) emptySet() else setOf(choice.id))
                            error = null
                        }
                        Row(Modifier.fillMaxWidth().clickable(enabled = !saving && choice.available) {
                            choose()
                        }) {
                            if (group.minimum == 0) Checkbox(checked = selected, enabled = !saving && choice.available,
                                onCheckedChange = { choose() })
                            else RadioButton(selected = selected, enabled = !saving && choice.available, onClick = { choose() })
                            Text(choice.title + if (!choice.available) " (غير متاح)" else "", Modifier.padding(top = 13.dp))
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    OutlinedButton(onClick = { quantity-- }, enabled = quantity > 1 && !saving) { Text("−") }
                    Text("الكمية: $quantity", Modifier.padding(top = 13.dp))
                    OutlinedButton(onClick = { quantity++ }, enabled = quantity < 99 && !saving) { Text("+") }
                }
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item {
                Button(onClick = { save(false) }, enabled = valid && !saving, modifier = Modifier.fillMaxWidth()) {
                    Text(if (saving) "جارٍ الحفظ…" else "أضف إلى طلبي في الأخطبوط")
                }
            }
            item { Text("عند الضغط على «قارن الإجمالي وجهّز السلتين» يبدأ التنقل للتحقق من طلبك والأسعار. لا يُؤكد طلب أو دفع.", style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (replacing) AlertDialog(onDismissRequest = { replacing = false }, title = { Text("تغيير مطعم الطلب المحلي؟") },
        text = { Text("طلبك المحفوظ داخل الأخطبوط من مطعم آخر. هل تستبدله بهذا المطعم؟ لا تُحذف أو تتغير أي سلة داخل كيتا أو هنقرستيشن.") },
        confirmButton = { TextButton(onClick = { replacing = false; save(true) }, enabled = !saving) { Text("استبدل الطلب المحلي") } },
        dismissButton = { TextButton(onClick = { replacing = false }) { Text("إلغاء") } })
}
