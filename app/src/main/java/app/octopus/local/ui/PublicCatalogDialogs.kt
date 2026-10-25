package app.octopus.local.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.octopus.local.AppGraph
import app.octopus.local.catalog.*
import app.octopus.local.domain.DraftChange
import kotlinx.coroutines.launch
import java.math.BigDecimal

@Composable
fun CatalogAreaDialog(graph: AppGraph, onDismiss: () -> Unit) {
    val publicState by graph.publicCatalog.state.collectAsStateWithLifecycle()
    val current = publicState.area
    var city by remember { mutableStateOf(current?.city.orEmpty()) }
    var district by remember { mutableStateOf(current?.district.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("منطقة قائمة المطاعم") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("اكتب المدينة والحي كما يظهران في هنقرستيشن. يُحفظ الاختيار للتحديث التلقائي؛ لا يحتاج صلاحية الموقع.")
            OutlinedTextField(city,{city=it.take(100)},label={Text("المدينة")},singleLine=true,enabled=!saving)
            OutlinedTextField(district,{district=it.take(100)},label={Text("الحي")},singleLine=true,enabled=!saving)
            Text("هذه منطقة الاستعراض. يُتحقق من عنوان التوصيل الفعلي داخل التطبيقين عند المقارنة.",style=MaterialTheme.typography.bodySmall)
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled=!saving,onClick={
            val area = runCatching { CatalogArea(city.trim(),district.trim()) }.getOrNull()
            if (area == null) error="أدخل اسم مدينة وحي صحيحين، دون روابط أو علامات مسار."
            else {
                saving=true
                scope.launch {
                    try {
                        graph.settingsStore.setCatalogArea(area)
                        if (current == area) graph.refreshPublicRestaurants()
                        onDismiss()
                    } catch (_: Exception) { error="تعذر حفظ المنطقة." }
                    finally { saving=false }
                }
            }
        }) { Text("حفظ وتحديث قائمة المطاعم") } },
        dismissButton = { TextButton(enabled=!saving,onClick=onDismiss) { Text("إلغاء") } })
}

@Composable
fun PublicMealDialog(graph: AppGraph, menu: PublicMenu, meal: PublicMeal, onDismiss: () -> Unit) {
    var quantity by remember(menu.restaurant.id,meal.id) { mutableIntStateOf(1) }
    var replacing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope=rememberCoroutineScope()
    fun save(replace: Boolean) {
        saving=true
        scope.launch {
            try {
                when(val change=graph.addPublicMeal(menu,meal,quantity,replace)) {
                    is DraftChange.Changed -> onDismiss()
                    DraftChange.RestaurantReplacementRequired -> replacing=true
                    is DraftChange.Rejected -> error=change.reason
                }
            } catch (_: Exception) { error="تعذر حفظ اختيار الوجبة." }
            finally { saving=false }
        }
    }
    AlertDialog(onDismissRequest={if(!saving)onDismiss()},title={Text(meal.name)},text={
        Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(menu.restaurant.name + " • " + meal.category)
            if(meal.description.isNotBlank()) Text(meal.description)
            Text(meal.price?.let { BigDecimal.valueOf(it,2).stripTrailingZeros().toPlainString()+" ر.س • سعر قائمة من الموقع" }
                ?: "سعر القائمة غير متاح")
            Text("يُحفظ اختيارك داخل الأخطبوط الآن. الموقع لا يعرض جميع خيارات الوجبة؛ تُقرأ الخيارات الإلزامية والسعر الفعلي عند المقارنة، وقد يحتاج اختيار إضافي.",style=MaterialTheme.typography.bodySmall)
            if(menu.restaurant.status != "OPEN") Text("الموقع لا يؤكد فتح هذا الفرع الآن؛ يُعاد التحقق عند المقارنة.",style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick={quantity--},enabled=!saving&&quantity>1) { Text("−") }
                Text("$quantity",Modifier.padding(top=12.dp))
                OutlinedButton(onClick={quantity++},enabled=!saving&&quantity<99) { Text("+") }
            }
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        }
    },confirmButton={TextButton(onClick={save(false)},enabled=!saving) { Text("أضف إلى طلبي") }},
        dismissButton={TextButton(onClick=onDismiss,enabled=!saving) { Text("رجوع") }})
    if(replacing) AlertDialog(onDismissRequest={replacing=false},title={Text("تغيير مطعم الطلب؟")},
        text={Text("يوجد طلب محلي من مطعم آخر. استبداله يغيّر طلب الأخطبوط فقط.")},
        confirmButton={TextButton(onClick={replacing=false;save(true)},enabled=!saving) { Text("استبدل الطلب المحلي") }},
        dismissButton={TextButton(onClick={replacing=false}) {Text("إلغاء")} })
}
