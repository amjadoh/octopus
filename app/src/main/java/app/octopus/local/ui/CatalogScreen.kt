package app.octopus.local.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import app.octopus.local.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.octopus.local.AppGraph
import app.octopus.local.automation.LiveCatalogBridge
import app.octopus.local.domain.*
import app.octopus.local.providers.*
import app.octopus.local.pricing.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.math.BigDecimal

private fun money(value: Long) = BigDecimal.valueOf(value, 2).stripTrailingZeros().toPlainString() + " ر.س"

@Composable
fun CatalogScreen(graph: AppGraph, onSetup: () -> Unit, onProviders: () -> Unit, comparisonOnly: Boolean = false,
    onRestaurants: () -> Unit = {}, onOpenComparison: () -> Unit = {}, onSettings: () -> Unit = {}) {
    val observedState by graph.catalog.collectAsStateWithLifecycle()
    val state = observedState.copy(busy = observedState.busy || observedState.comparisonRunning)
    val settings by graph.settings.collectAsStateWithLifecycle()
    val connected by graph.serviceConnected.collectAsStateWithLifecycle()
    val draft by graph.draft.collectAsStateWithLifecycle()
    val ready by graph.draftReady.collectAsStateWithLifecycle()
    val storageError by graph.draftStorageError.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var menuQuery by rememberSaveable(state.menu?.restaurant?.name) { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("الكل") }
    var browsing by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf(false) }
    var showProduct by remember { mutableStateOf(true) }
    var localError by remember { mutableStateOf<String?>(null) }
    var quoteTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); quoteTime = System.currentTimeMillis() } }
    val currentQuoteTime = maxOf(quoteTime, System.currentTimeMillis())
    fun search() {
        if (!(if (browsing) LiveCatalogBridge.browseRestaurants() else LiveCatalogBridge.search(query)))
            localError = "تعذر البدء. تحقق من اتصال خدمة الأخطبوط والسماح لهنقرستيشن."
    }
    fun start(browse: Boolean) {
        browsing = browse; localError = null; category = "الكل"
        when {
            !connected -> onSetup()
            ProviderId.HUNGERSTATION !in settings.allowed -> onProviders()
            !settings.catalogConsent -> consent = true
            else -> search()
        }
    }
    Column(Modifier.fillMaxSize()) {
    if (!comparisonOnly && state.menu == null) MosabHomeHeader(query,
        onQuery = { query = it.take(100) }, enabled = !state.busy,
        onSearch = { start(false) }, onBrowse = { start(true) }, onProviders = onProviders,
        onCart = onOpenComparison, onSettings = onSettings, allowed = settings.allowed.size)
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (comparisonOnly) {
            item {
                MosabJourneyHero("قارن طلبك", "كل التفاصيل قدامك. اختر المنصة بعد مراجعة سعرها.", 3)
            }
            if (draft == null) item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(Icons.Outlined.ShoppingBag, null, Modifier.size(36.dp), tint = OctopusPalette.Petrol)
                        Text("وش طلبك؟", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        Text("اختار مطعمك ووجباتك أولًا، ثم ارجع هنا للمقارنة. ما تتغير سلة أي تطبيق عند حفظ طلبك داخل الأخطبوط.", color = OctopusPalette.Muted)
                        Button(onClick = onRestaurants) { Text("استعرض المطاعم") }
                    }
                }
            }
        } else if (state.menu != null) {
        item {
            MosabJourneyHero(if (state.menu == null) "وش ودّك اليوم؟" else state.menu!!.restaurant.name,
                if (state.menu == null) "طلب واحد، وخيارات أكثر. ابدأ بمطعمك." else "اختر وجباتك وخصص طلبك على مزاجك.",
                if (state.menu == null) 1 else 2)
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(Icons.Outlined.LocationOn, null, Modifier.size(18.dp), tint = OctopusPalette.Petrol)
                Text("حسب عنوانك النشط في هنقرستيشن", fontSize = 12.sp, color = OctopusPalette.Muted)
            }
        }
        item {
            Button(onClick = { start(true) }, enabled = !state.busy, shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Restaurant, null, Modifier.size(21.dp)); Spacer(Modifier.width(9.dp))
                Text(if (state.restaurants.isEmpty()) "استعرض المطاعم" else "حدّث المطاعم", fontWeight = FontWeight.Bold)
            }
        }
        item {
            OutlinedTextField(value = query, onValueChange = { if (it.length <= 100) query = it }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(18.dp),
                placeholder = { Text("أو ابحث باسم مطعمك…", fontSize = 14.sp) }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { IconButton(onClick = { start(false) }, enabled = !state.busy && query.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, "ابحث عن المطاعم")
                } }, colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White,
                    focusedContainerColor = Color.White, unfocusedBorderColor = OctopusPalette.Line))
        }
        }
        storageError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (comparisonOnly) draft?.let { cart ->
            if (!ComparisonRequestSelection.matches(state.selectedRestaurant, cart)) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = OctopusPalette.Soft)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(state.selectedRestaurant?.let { "المطعم المختار: $it" } ?: "لم تختر طلبًا جديدًا بعد", fontWeight = FontWeight.Bold)
                            Text("اختر مطعمك ووجباتك لبدء المقارنة.")
                            Text("يوجد طلب سابق محفوظ من ${cart.restaurant}؛ لا يدخل المقارنة تلقائيًا.", fontSize = 12.sp)
                            if (state.selectedRestaurant == null) TextButton(onClick = {
                                graph.catalog.value = graph.catalog.value.copy(selectedRestaurant = cart.restaurant)
                            }) { Text("استئناف الطلب المحفوظ") }
                            OutlinedButton(onClick = onRestaurants) { Text("اختيار مطعم جديد") }
                        }
                    }
                }
            } else {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.ShoppingBag, null, tint = OctopusPalette.Petrol)
                            Spacer(Modifier.width(8.dp)); Text("طلبك • ${cart.restaurant}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                        Text("راجع الكميات، ثم اقرأ أسعار الوجبات أو جهّز سلال المقارنة.", color = OctopusPalette.Muted, fontSize = 12.sp)
                        cart.items.forEach { line ->
                            Text(listOfNotNull(line.name, line.size).joinToString(" • "), fontWeight = FontWeight.Medium)
                            if (line.options.isNotEmpty()) Text(line.options.joinToString("، ") { "${it.group}: ${it.value}" }, fontSize = 12.sp)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                                fun change(amount: Int) {
                                    changing = true
                                    graph.scope.launch {
                                        try {
                                            val result = graph.changeDraftQuantity(CartIdentity.itemKey(line), amount)
                                            if (result is DraftChange.Rejected) localError = result.reason
                                        } catch (_: Exception) { localError = "تعذر حفظ تعديل الطلب." }
                                        finally { changing = false }
                                    }
                                }
                                FilledTonalIconButton(onClick = { change(line.quantity - 1) }, enabled = !changing && !state.busy) {
                                    Icon(if (line.quantity == 1) Icons.Outlined.DeleteOutline else Icons.Outlined.Remove, "تقليل الكمية أو حذف الصنف")
                                }
                                Text("${line.quantity}", fontWeight = FontWeight.Bold)
                                FilledTonalIconButton(onClick = { change(line.quantity + 1) }, enabled = !changing && !state.busy && line.quantity < 99) {
                                    Icon(Icons.Outlined.Add, "زيادة الكمية")
                                }
                            }
                        }
                        Button(onClick = {
                            localError = null
                            if (!LiveCatalogBridge.compareDraft() && graph.catalog.value.error == null) localError = "تعذر بدء المقارنة؛ تحقق من اتصال الخدمة."
                        }, enabled = !state.busy && !changing, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Outlined.CompareArrows, null); Spacer(Modifier.width(8.dp)); Text("قارن أسعار الوجبات")
                        }
                        Text("أسعار الوجبات فقط؛ التوصيل والخدمة والخصومات المشروطة غير متحققة.", fontSize = 12.sp, color = OctopusPalette.Muted)
                        OutlinedButton(onClick = {
                            localError = null
                            if (!LiveCatalogBridge.compareAndPrepare()) localError = "تعذر بدء تجهيز السلتين؛ تحقق من الخدمة والطلب."
                        }, enabled = !state.busy && !changing, modifier = Modifier.fillMaxWidth()) {
                            Text("جهّز السلتين لمقارنة الإجمالي")
                        }
                        Text("يجهّز السلتين ويقرأ إجمالي المراجعة إذا تعرّف على مسارها، دون تأكيد طلب أو دفع. النتيجة غير المقروءة لا تُقدّر.", fontSize = 12.sp, color = OctopusPalette.Muted)
                        state.comparisonProgress.values.forEach { result ->
                            Text("${result.provider.title}: ${result.detail ?: "جارٍ تجهيز السلة والتحقق منها…"}", fontSize = 12.sp)
                        }
                        ProviderId.entries.forEach { id ->
                            if (state.preparedBaskets.forRequest(cart, id) != null) TextButton(onClick = {
                                val opened = if (id == ProviderId.KEETA) LiveCatalogBridge.prepareComparedKeeta(true)
                                    else LiveCatalogBridge.openPreparedRidanBasket()
                                if (!opened) localError = "تعذر بدء إعادة التحقق من السلة."
                            }, enabled = !state.busy) { Text("افتح وأعد فحص سلة ${id.title}") }
                        }
                    }
                }
            }
            if (state.finalTotals.isNotEmpty()) {
                item { Text("إجماليات مراجعة الطلب", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                ProviderId.entries.forEach { id ->
                    state.finalTotals[id]?.let { quote -> item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(22.dp)) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(id.title, fontWeight = FontWeight.Bold)
                                Text(money(quote.total), fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                                Text(if (quote.fresh(cart, currentQuoteTime)) "إجمالي مقروء من مراجعة التطبيق وقت المقارنة" else "قراءة سابقة — يلزم تحديثها", fontSize = 12.sp)
                                quote.fields.filterKeys { it != PriceField.ORDER_TOTAL }.forEach { (field, amount) ->
                                    Text("${when(field) {
                                        PriceField.SUBTOTAL -> "الأصناف"; PriceField.DELIVERY -> "التوصيل"; PriceField.SERVICE -> "الخدمة"
                                        PriceField.SMALL_ORDER -> "رسوم الطلب الصغير"; PriceField.TAX -> "الضريبة"; PriceField.DISCOUNT -> "الخصم"
                                        PriceField.WALLET -> "تغطية المحفظة"; PriceField.AMOUNT_DUE -> "المطلوب للدفع"; else -> "الإجمالي"
                                    }}: ${money(amount)}", fontSize = 12.sp)
                                }
                                Text("السلة متحققة. تطابق العنوان والفرع بين المنصتين يحتاج تحققًا مستقلًا؛ لم يُعتمد ترتيب الأرخص.", fontSize = 11.sp, color = OctopusPalette.Muted)
                            }
                        }
                    } }
                }
            }
            if (state.previews.isNotEmpty()) {
                item { Text("مقارنة أسعار الوجبات", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                item { Text("قراءة قبل تجهيز السلة. الإجمالي النهائي ومطابقة العنوان والفرع بين التطبيقين غير متحققة؛ لم أحدد الأرخص.", fontSize = 12.sp, color = OctopusPalette.Muted) }
                item { ComparisonSummary(state.previews, cart, currentQuoteTime) }
                items(state.previews, key = { it.provider }) { preview ->
                    val fresh = preview.fresh(cart, currentQuoteTime)
                    CostPreviewCard(preview, fresh, !state.busy, currentQuoteTime, onSelect = {
                        val started = if (preview.provider == ProviderId.KEETA) LiveCatalogBridge.prepareComparedKeeta()
                            else LiveCatalogBridge.prepareComparedHungerStation()
                        if (!started) localError = "انتهت صلاحية القراءة أو تغير الطلب؛ أعد المقارنة."
                    })
                }
            }
            }
        }
        if (state.busy) item {
            Card(colors = CardDefaults.cardColors(containerColor = OctopusPalette.Soft), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth()); Text(state.message, fontSize = 13.sp)
                    TextButton(onClick = { LiveCatalogBridge.stop() }) { Text("إيقاف") }
                }
            }
        } else item { Text(state.message, fontSize = 12.sp, color = OctopusPalette.Muted) }
        (localError ?: state.error)?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) } }
        if (!comparisonOnly && state.menu == null && state.restaurants.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("اكتشف المطاعم", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("${state.restaurants.size} مطعم", color = OctopusPalette.Petrol, fontSize = 12.sp)
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("الكل", "مشويات", "برجر", "قهوة")) { value ->
                        FilterChip(selected = category == value, onClick = { category = value }, label = { Text(value) }, shape = RoundedCornerShape(20.dp))
                    }
                }
            }
            val visible = state.restaurants.filter { category == "الكل" || (it.name + " " + it.description.orEmpty()).contains(category) }
            if (visible.isEmpty()) item { Text("ما ظهر مطعم بهذا التصنيف في الجزء المقروء. جرّب «الكل» أو البحث بالاسم.", fontSize = 13.sp) }
            items(visible, key = { it.name }) { restaurant ->
                Card(onClick = { if (!LiveCatalogBridge.openMenu(restaurant)) localError = "تعذر فتح قائمة المطعم." }, enabled = !state.busy,
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(22.dp)) {
                    Box(Modifier.fillMaxWidth().height(72.dp).background(Brush.horizontalGradient(
                        listOf(OctopusPalette.Ink, OctopusPalette.SeaMid))).padding(16.dp)) {
                        Icon(Icons.Outlined.Restaurant, null, Modifier.align(Alignment.CenterEnd).size(34.dp), tint = Color.White.copy(alpha = .3f))
                        Surface(color = Color.White.copy(alpha = .12f), shape = RoundedCornerShape(20.dp)) {
                            Text("القائمة من ${restaurant.source.title}", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = Color.White, fontSize = 11.sp)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(64.dp).background(OctopusPalette.Soft, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                            Text(restaurant.name.take(1), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = OctopusPalette.Petrol)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(restaurant.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            restaurant.description?.let { Text(it, color = OctopusPalette.Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            Text("هنقرستيشن • افتح القائمة", fontSize = 11.sp, color = OctopusPalette.Petrol)
                        }
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp), tint = OctopusPalette.Petrol)
                    }
                }
            }
        }
        if (!comparisonOnly) state.menu?.let { menu ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            TextButton(onClick = { graph.catalog.value = state.copy(menu = null, product = null) }, enabled = !state.busy) { Text("العودة للمطاعم") }
                    Text(menu.restaurant.name, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
                    Text("اختر وجباتك وخصصها داخل الأخطبوط", color = OctopusPalette.Muted, fontSize = 13.sp)
                    Text("${menu.items.size} صنف مقروء • ${menu.restaurant.source.title}", color = OctopusPalette.Petrol, fontSize = 12.sp)
                    OutlinedTextField(value = menuQuery, onValueChange = { menuQuery = it.take(100) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(18.dp),
                        placeholder = { Text("ابحث في الوجبات المقروءة") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) })
                }
            }
            val visibleMeals = menu.items.filter { menuQuery.isBlank() || it.name.contains(menuQuery.trim(), ignoreCase = true) }
            if (visibleMeals.isEmpty()) item { Text("لم تظهر وجبة بهذا الاسم في القائمة المقروءة.", fontSize = 13.sp) }
            items(visibleMeals, key = { it.selector }) { item ->
                Card(onClick = {
                    showProduct = true
                    if (!LiveCatalogBridge.openProduct(menu.restaurant, item)) localError = "تعذرت قراءة خيارات الوجبة."
                }, enabled = !state.busy && ready, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text(item.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(item.listedPrice?.let(::money) ?: "السعر غير مقروء", color = OctopusPalette.Petrol, fontWeight = FontWeight.Bold)
                            Text("عرض الخيارات", color = OctopusPalette.Muted, fontSize = 11.sp)
                            draft?.takeIf { it.restaurant == menu.restaurant.name }?.items
                                ?.filter { it.name == item.name }?.sumOf { it.quantity }?.takeIf { it > 0 }?.let {
                                    Text("في طلبك: $it", color = OctopusPalette.Petrol, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                        }
                        Icon(Icons.Outlined.AddCircleOutline, null, Modifier.size(29.dp), tint = OctopusPalette.Petrol)
                    }
                }
            }
        }
        item { Text("اختر المطعم والوجبة. تُقبل الخيارات عند اكتمال قراءتها؛ المقارنة وتجهيز السلة التلقائي حاليًا لصنف واحد دون خيارات أو ملاحظة. الأصناف والتوصيل لا يمثلان الإجمالي النهائي مع الرسوم.", fontSize = 11.sp, color = OctopusPalette.Muted) }
    }
    if (!comparisonOnly) draft?.takeIf { ComparisonRequestSelection.matches(state.selectedRestaurant, it) }?.let { cart ->
        Surface(shadowElevation = 6.dp, color = Color.White) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(cart.restaurant, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${cart.items.sumOf { it.quantity }} وجبة في طلبك", color = OctopusPalette.Muted, fontSize = 12.sp)
                }
                Button(onClick = onOpenComparison, enabled = !state.busy && !changing,
                    shape = RoundedCornerShape(16.dp)) { Text("راجع الطلب وقارن") }
            }
        }
    }
    }
    state.product?.takeIf { showProduct && !state.busy && !comparisonOnly }?.let { details ->
        ProductConfigurationSheet(graph, details, onDismiss = { showProduct = false }, onSaved = {
            localError = null
            graph.catalog.value = graph.catalog.value.copy(product = null)
            onOpenComparison()
        })
    }
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("السماح باستعراض المطاعم") },
        text = { Text("يفتح الأخطبوط هنقرستيشن لقراءة المطاعم والوجبات الظاهرة ثم يعود إليك. المقارنة تقرأ كيتا وهنقرستيشن دون تعديل سلالهما. تجهيز السلة يبدأ فقط بعد اختيارك. لا يُؤكد طلب أو دفع. تبقى البيانات محلية ويمكن إيقاف التنقل من الزر فوق المنصة.") },
        confirmButton = { TextButton(onClick = {
            consent = false
            graph.scope.launch { graph.settingsStore.acceptCatalogConsent(); graph.settings.first { it.catalogConsent }; search() }
        }) { Text("ابدأ الاستعراض") } }, dismissButton = { TextButton(onClick = { consent = false }) { Text("إلغاء") } })
}

@Composable
private fun MosabHomeHeader(query: String, onQuery: (String) -> Unit, enabled: Boolean,
    onSearch: () -> Unit, onBrowse: () -> Unit, onProviders: () -> Unit, onCart: () -> Unit, onSettings: () -> Unit, allowed: Int) {
    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(
        OctopusPalette.Ink, OctopusPalette.Petrol, OctopusPalette.SeaMid)),
        RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp)).statusBarsPadding()
        .padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_octopus_selected), "الأخطبوط", Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("التوصيل إلى", color = Color.White.copy(alpha = .65f), fontSize = 11.sp)
                Text("عنوانك النشط في هنقرستيشن", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "الإعدادات", tint = Color.White) }
            Surface(color = Color.White.copy(alpha = .12f), shape = CircleShape) {
                IconButton(onClick = onCart) { Icon(Icons.Outlined.ShoppingBag, "طلبك", tint = Color.White) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("اطلب مرة، قارن الكل.", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("اختياراتك من كيتا وهنقرستيشن، في مكان واحد", color = Color.White.copy(alpha = .75f), fontSize = 12.sp)
        }
        OutlinedTextField(value = query, onValueChange = onQuery, enabled = enabled,
            modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp),
            placeholder = { Text("ابحث عن مطعم…", fontSize = 14.sp) }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { IconButton(onClick = onSearch, enabled = enabled && query.isNotBlank()) {
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, "ابحث")
            } }, colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White,
                focusedContainerColor = Color.White, unfocusedBorderColor = Color.Transparent))
        Surface(onClick = onProviders, color = Color.White.copy(alpha = .09f), shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(color = Color(0xFFFFE500), shape = RoundedCornerShape(10.dp)) {
                    Text("كيتا", Modifier.padding(7.dp), color = OctopusPalette.Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Surface(color = Color(0xFFFFCC00), shape = RoundedCornerShape(10.dp)) {
                    Text("هنقر", Modifier.padding(7.dp), color = OctopusPalette.Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f)) {
                    Text("$allowed تطبيق مسموح", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("تُقرأ الأسعار عند المقارنة", color = Color.White.copy(alpha = .65f), fontSize = 10.sp)
                }
                Icon(Icons.Outlined.Tune, "إدارة التطبيقات", tint = Color.White, modifier = Modifier.size(19.dp))
            }
        }
        Button(onClick = onBrowse, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Text("استعرض المطاعم", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MosabJourneyHero(title: String, subtitle: String, active: Int) {
    Box(Modifier.fillMaxWidth().background(Brush.linearGradient(
        listOf(OctopusPalette.Ink, OctopusPalette.Petrol, OctopusPalette.SeaMid)), RoundedCornerShape(28.dp)).padding(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.size(7.dp).background(OctopusPalette.Coral, CircleShape))
                Text("الأخطبوط • اختياراتك أوضح", color = Color(0xFFBED4E0), fontSize = 12.sp)
            }
            Text(title, color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.ExtraBold)
            Text(subtitle, color = Color(0xFFD8E6EE), fontSize = 14.sp)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("المطعم", "طلبك", "المقارنة").forEachIndexed { index, label ->
                    val selected = active == index + 1
                    Surface(Modifier.weight(1f), color = if (selected) OctopusPalette.Coral else Color.White.copy(alpha = .09f),
                        shape = RoundedCornerShape(14.dp)) {
                        Text("${index + 1} $label", Modifier.padding(vertical = 10.dp), textAlign = TextAlign.Center,
                            color = if (selected) OctopusPalette.Ink else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun CostPreviewCard(preview: MenuCostPreview, fresh: Boolean, enabled: Boolean, now: Long, onSelect: () -> Unit) {
    var allOffers by remember(preview) { mutableStateOf(false) }
    val accent = if (preview.provider == ProviderId.KEETA) Color(0xFFF9D941) else Color(0xFFFFCA45)
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(38.dp).background(accent, CircleShape), contentAlignment = Alignment.Center) {
                    Text(if (preview.provider == ProviderId.KEETA) "K" else "H", color = Color(0xFF222C2E), fontWeight = FontWeight.ExtraBold)
                }
                Text(preview.provider.title, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("سعر الأصناف")
                Text(if (!fresh) "قراءة قديمة" else preview.itemSubtotal?.let(::money) ?: "غير مقروء", fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("التوصيل المعروض")
                Text(if (fresh && preview.offers.any { it == "مجاني" || it == "توصيل مجاني" }) "مجاني — بشروط"
                    else if (fresh) preview.deliveryBeforeOffers?.let(::money) ?: "غير مؤكد" else "قراءة قديمة",
                    color = OctopusPalette.Muted, fontSize = 13.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("رسوم الخدمة"); Text("غير مقروءة", color = OctopusPalette.Muted, fontSize = 13.sp)
            }
            preview.minimumOrder?.takeIf { fresh }?.let { minimum ->
                Text("الحد الأدنى للطلب: ${money(minimum)}", fontSize = 12.sp, color = OctopusPalette.Muted)
            }
            (if (allOffers) preview.offers else preview.offers.take(3)).forEach { offer ->
                Surface(color = OctopusPalette.SoftAmber, shape = RoundedCornerShape(12.dp)) {
                    Text(offer, Modifier.fillMaxWidth().padding(10.dp), fontSize = 12.sp, color = OctopusPalette.Amber)
                }
            }
            if (preview.offers.size > 3) TextButton(onClick = { allOffers = !allOffers }) { Text(if (allOffers) "اختصر العروض" else "كل العروض (${preview.offers.size})") }
            preview.calculationAt(now)?.takeIf { fresh }?.let { calculation ->
                calculation.decisions.forEach { decision ->
                    Text(when (decision.status) {
                        OfferStatus.ELIGIBLE -> decision.rule.title + if (decision.rule.id in calculation.selected) " • محسوب حسب الشروط المقروءة" else " • بديل غير مجموع مع العرض المختار"
                        OfferStatus.BELOW_MINIMUM -> decision.rule.title + " • ينقص طلبك ${money(decision.remaining)}"
                        else -> decision.rule.title + " • " + decision.reason
                    }, fontSize = 12.sp, color = if (decision.status == OfferStatus.ELIGIBLE) OctopusPalette.Petrol else OctopusPalette.Muted)
                }
                if (calculation.itemDiscount > 0) Text("الخصم المحسوب: ${money(calculation.itemDiscount)}", fontWeight = FontWeight.Bold)
                calculation.delivery?.let { Text("التوصيل المحسوب: ${money(it)}", fontWeight = FontWeight.Bold) }
                calculation.knownPartTotal?.let { Text("الأصناف والتوصيل بعد العرض: ${money(it)}", fontWeight = FontWeight.Bold, color = OctopusPalette.Petrol) }
            }
            if (preview.offers.isNotEmpty()) Text("هذا حساب للشروط المقروءة، وليس إثبات تطبيق العرض. الشروط غير المتحققة لا تدخل الحساب، ولا تُجمع عروض دون توثيق السماح بذلك.", fontSize = 11.sp, color = OctopusPalette.Muted)
            HorizontalDivider(color = OctopusPalette.Line)
            Text("الإجمالي النهائي غير متحقق", fontWeight = FontWeight.Bold)
            preview.error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = onSelect,
                enabled = enabled && fresh && preview.itemSubtotal != null, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp)) {
                Text("اختر ${preview.provider.title} وجهّز السلة")
            }
        }
    }
}


@Composable
private fun ComparisonSummary(previews: List<MenuCostPreview>, cart: CanonicalCart, now: Long) {
    val sources = listOf(ProviderId.HUNGERSTATION, ProviderId.KEETA).map { id -> previews.firstOrNull { it.provider == id } }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text("نظرة سريعة", Modifier.weight(1.2f), fontWeight = FontWeight.Bold)
                Text("هنقرستيشن", Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("كيتا", Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            HorizontalDivider(color = OctopusPalette.Line)
            listOf("الأصناف", "الخصم المحسوب", "التوصيل المحسوب", "الأصناف والتوصيل", "الإجمالي النهائي").forEachIndexed { index, label ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1.2f), fontSize = 12.sp, color = OctopusPalette.Muted)
                    sources.forEach { source ->
                        val value = if (source?.fresh(cart, now) != true) "غير مقروء" else when (index) {
                            0 -> source.itemSubtotal?.let(::money) ?: "غير مقروء"
                            1 -> source.calculationAt(now)?.itemDiscount?.let(::money) ?: "غير مقروء"
                            2 -> source.calculationAt(now)?.delivery?.let(::money) ?: "غير مؤكد"
                            3 -> source.calculationAt(now)?.knownPartTotal?.let(::money) ?: "غير مكتمل"
                            else -> "غير متحقق"
                        }
                        Text(value, Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 12.sp,
                            fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal,
                            color = if (index == 0) OctopusPalette.Petrol else OctopusPalette.Muted)
                    }
                }
            }
            Text("التوصيل معروض قبل التحقق من تطبيق العروض. رسوم الخدمة ومطابقة العنوان والفرع لم تُتحقق.", fontSize = 10.sp, color = OctopusPalette.Muted)
        }
    }
}
