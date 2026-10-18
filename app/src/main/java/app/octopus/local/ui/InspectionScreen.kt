package app.octopus.local.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.octopus.local.AppGraph
import app.octopus.local.automation.ReadOnlyServiceBridge
import app.octopus.local.diagnostics.*
import app.octopus.local.domain.ProviderId

@Composable
fun InspectionScreen(graph: AppGraph, onBack: () -> Unit, onSetup: () -> Unit) {
    val settings by graph.settings.collectAsStateWithLifecycle()
    val connected by graph.serviceConnected.collectAsStateWithLifecycle()
    val session by graph.session.collectAsStateWithLifecycle()
    val reports by graph.inspections.collectAsStateWithLifecycle()
    val installations by graph.installations.collectAsStateWithLifecycle()
    var provider by rememberSaveable { mutableStateOf(ProviderId.HUNGERSTATION) }
    var stage by rememberSaveable { mutableStateOf(InspectionStage.REVIEW) }
    var restaurant by rememberSaveable { mutableStateOf("المضياف العربي") }
    var requestItem by rememberSaveable { mutableStateOf("دجاج مضبي") }
    var confirm by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding = PaddingValues(21.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("فحص عناصر الشاشة", style = MaterialTheme.typography.headlineSmall) }
        item { Text("فحص يدوي لمرة الإعداد، لمعرفة سبب فشل القراءة. ليس مقارنة آلية ولا تجهيزًا للسلة.") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProviderId.entries.forEach { value -> FilterChip(selected = provider == value,
                onClick = { provider = value }, enabled = !session.active, label = { Text(value.title) }) }
        } }
        item { OutlinedTextField(value = restaurant, onValueChange = { if (it.length <= 100) restaurant = it },
            label = { Text("اسم المطعم كما يظهر في التطبيق") }, singleLine = true, enabled = !session.active, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(value = requestItem, onValueChange = { if (it.length <= 100) requestItem = it },
            label = { Text("اسم الوجبة كما يظهر في التطبيق") }, singleLine = true, enabled = !session.active, modifier = Modifier.fillMaxWidth()) }
        item { Text("الشاشة التي ستفحصها — هذا اختيارك، وليس تعرّفًا آليًا على الشاشة:") }
        items(InspectionStage.entries.size) { index ->
            val value = InspectionStage.entries[index]
            FilterChip(selected = stage == value, onClick = { stage = value }, enabled = !session.active, label = { Text(value.title) })
        }
        item { Text("ابدأ من «مراجعة الطلب قبل التأكيد». بعد فتح المنصة، انتقل للشاشة المطلوبة ثم اضغط الزر العائم «افحص هذه الشاشة». لا تُسجّل الشاشة قبل ضغطك عليه. تجنب شاشات الدخول أو بيانات الدفع.") }
        item { Text("المحفوظ: ${reports.count { it.provider == provider.name }} فحصًا لهذه المنصة. لا تُحفظ نصوص الحقول أو الصور أو بيانات الحساب.") }
        if (session.active) item {
            Text(session.message)
            Text("متبقي ${session.remainingSeconds} ثانية")
            OutlinedButton(onClick = { ReadOnlyServiceBridge.stop() }) { Text("إيقاف الفحص") }
        } else item {
            Text(session.message)
            Button(onClick = { if (!connected || !settings.consent) onSetup() else confirm = true },
                enabled = restaurant.isNotBlank() && requestItem.isNotBlank() && provider in settings.allowed &&
                    installations.any { it.provider == provider && it.installed }, modifier = Modifier.fillMaxWidth()) {
                Text(if (!connected || !settings.consent) "إكمال إعداد الخدمة" else "ابدأ فحص ${provider.title}")
            }
        }
        item { Text("بعد حفظ الفحص، ارجع إلى السجل وصدّر ملف التشخيص. سيشمل الفحص المنقح مع الإصدار الفعلي للتطبيق والجهاز.") }
        item { TextButton(onClick = onBack, enabled = !session.active) { Text("العودة إلى القراءة والسجل") } }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("السماح بفحص هذه الشاشة؟") },
        text = { Text("سيحفظ الأخطبوط محليًا معرفات العناصر وأدوارها ومواقعها وتسميات وظيفية معروفة فقط، بعد ضغط «افحص هذه الشاشة». لا يحفظ النص الخام أو العنوان أو كلمات المرور أو صور الشاشة، ولا يرسل شيئًا تلقائيًا. لن ينقر أو يجهز سلة. تنتهي الجلسة خلال 90 ثانية، ويمكن إيقافها فورًا.") },
        confirmButton = { TextButton(onClick = {
            confirm = false
            if (!ReadOnlyServiceBridge.inspect(provider, InspectionRequest(stage, restaurant, requestItem))) error = "لم يبدأ الفحص. تحقق من الخدمة والسماح للمنصة ثم أعد المحاولة."
        }) { Text("أوافق، افتح التطبيق") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("إلغاء") } })
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("لم يبدأ الفحص") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text("حسنًا") } }) }
}
