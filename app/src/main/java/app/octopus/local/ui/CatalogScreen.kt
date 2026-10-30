package app.octopus.local.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
    val publicState by graph.publicCatalog.state.collectAsStateWithLifecycle()
    val publicMeal by graph.publicMealSelection.collectAsStateWithLifecycle()
    var areaDialog by remember { mutableStateOf(false) }
    val state = observedState.copy(busy = observedState.busy || observedState.comparisonRunning)
    val settings by graph.settings.collectAsStateWithLifecycle()
    val installations by graph.installations.collectAsStateWithLifecycle()
    val connected by graph.serviceConnected.collectAsStateWithLifecycle()
    val draft by graph.draft.collectAsStateWithLifecycle()
    val ready by graph.draftReady.collectAsStateWithLifecycle()
    val storageError by graph.draftStorageError.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var menuQuery by rememberSaveable(state.menu?.restaurant?.name) { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("الكل") }
    var consent by remember { mutableStateOf(false) }
    var nativeCartRequested by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf(false) }
    var allowMatchingQuantity by remember(draft?.fingerprint) { mutableStateOf(false) }
    var showProduct by remember(state.product?.source?.screenFingerprint) { mutableStateOf(true) }
    var localError by remember { mutableStateOf<String?>(null) }
    var manageCarts by remember { mutableStateOf(false) }
    var clearedCartConfirmation by remember { mutableStateOf<Pair<ProviderId, CanonicalCart>?>(null) }
    var cartToDelete by remember { mutableStateOf<CanonicalCart?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var quoteTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val listState = rememberLazyListState()
    val menuIdentity = if (comparisonOnly) null else state.menu?.restaurant?.catalogKey
    // Navigation resets position; a refreshed menu or progress update keeps the user's scroll.
    LaunchedEffect(comparisonOnly, menuIdentity) { listState.scrollToItem(0) }
    LaunchedEffect(state.error, localError) {
        if (state.error != null || localError != null) listState.scrollToItem(0)
    }
    LaunchedEffect(Unit) { while (true) { delay(15_000); quoteTime = System.currentTimeMillis() } }
    val currentQuoteTime = maxOf(quoteTime, System.currentTimeMillis())
    fun finishManualReading() {
        if (graph.catalog.value.manualCaptureActive) LiveCatalogBridge.endManualCatalog()
    }
    fun beginManualReading() {
        localError = null
        if (!(if (nativeCartRequested) LiveCatalogBridge.beginNativeCartComparison() else LiveCatalogBridge.beginManualCatalog()))
            localError = graph.catalog.value.error ?: "تعذر فتح القراءة؛ تحقق من اتصال خدمة الأخطبوط والسماح لهنقرستيشن."
    }
    fun requestManualReading(nativeCart: Boolean = false) {
        nativeCartRequested = nativeCart
        localError = null
        when {
            !connected -> onSetup()
            ProviderId.HUNGERSTATION !in settings.allowed -> onProviders()
            !settings.catalogConsent -> consent = true
            else -> beginManualReading()
        }
    }
    fun showRestaurants(clearSearch: Boolean) {
        finishManualReading()
        localError = null; category = "الكل"
        if (clearSearch) query = ""
        graph.publicCatalog.leaveMenu()
        graph.publicMealSelection.value = null
        graph.catalog.value = graph.catalog.value.copy(menu = null, product = null, error = null)
    }
    fun browse() {
        if (publicState.area == null) areaDialog = true else graph.refreshPublicRestaurants()
    }
    Column(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        (localError ?: state.error)?.let { error -> item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("توقفت المقارنة", fontWeight = FontWeight.Bold)
                    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        } }
        if (!comparisonOnly && state.menu == null) item {
            MosabHomeHeader(query,
                onQuery = { query = it.take(100) }, enabled = !state.busy,
                onSearch = { showRestaurants(false) }, onBrowse = { browse() }, onProviders = onProviders,
                onCart = onOpenComparison, onSettings = onSettings, allowed = settings.allowed.size,
                browseEnabled = !state.busy && !state.manualCaptureActive && !publicState.refreshing,
                browseLabel = "تحديث قائمة المطاعم")
        }
        if (!comparisonOnly) item {
            Card(colors = CardDefaults.cardColors(containerColor = OctopusPalette.Soft), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(if (state.menu == null) "جهّز في هنقرستيشن، وقارن هنا" else "اختر هنا، وقارن عندما يكتمل طلبك", fontWeight = FontWeight.Bold)
                    Text("اختر عنوانك والمطعم ووجباتك في هنقرستيشن. افتح السلة واضغط «قارن» لنقل الطلب إلى كيتا والعودة بالنتيجة. يدعم صنفًا أو صنفين دون إضافات، أو وجبة واحدة بخيارات يُختار واحد منها في كل مجموعة. راجع تطابق عنوان التطبيقين.",
                        fontSize = 12.sp, color = OctopusPalette.Muted)
                    Button(onClick = { requestManualReading(nativeCart = true) },
                        enabled = !state.busy && !state.manualCaptureActive && ready,
                        modifier = Modifier.fillMaxWidth()) { Text("ابدأ من هنقرستيشن") }
                    if (publicState.refreshing) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("جارٍ تحديث قائمة المطاعم…", fontSize = 12.sp)
                    }
                    if (publicState.loadingMenu) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("جارٍ قراءة قائمة ${state.selectedRestaurant.orEmpty()} داخل الأخطبوط…", fontSize = 12.sp)
                    }
                    publicState.updatedAt?.let { time -> Text("آخر تحديث: " +
                        java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale("ar")).format(java.util.Date(time)), fontSize = 12.sp) }
                    publicState.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    if (state.manualCaptureActive) {
                        Text("القراءة اليدوية نشطة؛ يمكنك إنهاؤها والاختيار من البيانات المحفوظة.", fontSize = 12.sp)
                        TextButton(onClick = { finishManualReading() }) { Text("إنهاء القراءة اليدوية") }
                    } else if (state.restaurants.isNotEmpty() || state.menu != null) {
                        TextButton(onClick = { requestManualReading() }, enabled = !state.busy) { Text("قراءة يدوية اختيارية من هنقرستيشن") }
                    }
                }
            }
        }
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
            MosabJourneyHero(state.menu!!.restaurant.name, "اختر وجباتك وخصص طلبك على مزاجك.", 2)
        }
        item {
            Button(onClick = { browse() }, enabled = !state.busy && !publicState.refreshing, shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Restaurant, null, Modifier.size(21.dp)); Spacer(Modifier.width(9.dp))
                Text("تحديث قائمة المطاعم", fontWeight = FontWeight.Bold)
            }
        }
        item {
            OutlinedTextField(value = query, onValueChange = { if (it.length <= 100) query = it }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(18.dp),
                placeholder = { Text("ابحث في المطاعم المحفوظة…", fontSize = 14.sp) }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { IconButton(onClick = { showRestaurants(false) }, enabled = !state.busy && query.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, "ابحث عن المطاعم")
                } }, colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White,
                    focusedContainerColor = Color.White, unfocusedBorderColor = OctopusPalette.Line))
        }
        }
        storageError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (comparisonOnly) item {
            OutlinedButton(onClick = { localError = null; manageCarts = true }, enabled = !state.busy && !changing,
                modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.DeleteOutline, null)
                Spacer(Modifier.width(8.dp)); Text("إدارة السلال وحذفها")
            }
        }
        if (comparisonOnly) draft?.let { cart ->
            val nativeConfigured = cart.items.singleOrNull()?.let {
                it.quantity == 1 && it.size == null && it.note.isNullOrBlank() && it.options.isNotEmpty() &&
                    it.options.all { option -> option.quantity == 1 }
            } == true
            val preflightFailure = app.octopus.local.automation.ComparisonPreflight.failure(
                cart, settings.catalogConsent, settings.allowed, installations)
            if (!ComparisonRequestSelection.matches(state.selectedRestaurant, cart)) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = OctopusPalette.Soft)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(state.selectedRestaurant?.let { "المطعم المختار: $it" } ?: "لم تختر طلبًا جديدًا بعد", fontWeight = FontWeight.Bold)
                            Text("اختر مطعمك ووجباتك لبدء المقارنة.")
                            Text("يوجد طلب سابق محفوظ من ${cart.restaurant}؛ لا يدخل المقارنة تلقائيًا.", fontSize = 12.sp)
                            if (state.selectedRestaurant == null) TextButton(onClick = {
                                finishManualReading()
                                graph.catalog.value = graph.catalog.value.copy(selectedRestaurant = cart.restaurant)
                            }, enabled = !state.busy) { Text("استئناف الطلب المحفوظ") }
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
                        Text("راجع الأصناف والخيارات والكميات، ثم ابدأ المقارنة لتجهيز الطلب نفسه في التطبيقين وقراءة إجماليه.", color = OctopusPalette.Muted, fontSize = 12.sp)
                        cart.items.forEach { line ->
                            Text(listOfNotNull(line.name, line.size).joinToString(" • "), fontWeight = FontWeight.Medium)
                            if (line.options.isNotEmpty()) Text(line.options.joinToString("، ") { "${it.group}: ${it.value}" }, fontSize = 12.sp)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                                fun change(amount: Int) {
                                    finishManualReading()
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
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(checked=allowMatchingQuantity,onCheckedChange={allowMatchingQuantity=it},enabled=!state.busy)
                            Text("أسمح بإكمال الأصناف المطابقة وتعديل كمياتها في سلة سابقة من المطعم نفسه إلى طلبي المختار، بعد فحص جميع الصفوف والإضافات.",fontSize=11.sp,modifier=Modifier.weight(1f))
                        }
                        Button(onClick = {
                            finishManualReading()
                            localError = null
                            if (nativeConfigured) requestManualReading(nativeCart = true)
                            else {
                            if(allowMatchingQuantity) graph.approveExistingMatchingQuantity() else graph.existingQuantityConsent.value=null
                            if (!LiveCatalogBridge.compareAndPrepare()) localError = graph.catalog.value.error
                                ?: "تعذر بدء تجهيز السلتين؛ تحقق من اتصال خدمة الأخطبوط."
                            }
                        }, enabled = !state.busy && !changing && connected && (nativeConfigured || preflightFailure == null),
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Outlined.CompareArrows, null); Spacer(Modifier.width(8.dp))
                            Text(if (nativeConfigured) "أعد المقارنة من سلة هنقرستيشن" else "قارن الإجمالي وجهّز السلتين")
                        }
                        if (!state.busy && !connected) TextButton(onClick = onSetup) { Text("ربط خدمة الأخطبوط لتشغيل المقارنة") }
                        if (!state.busy && preflightFailure != null && !nativeConfigured) {
                            Text(preflightFailure, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = onProviders) { Text("مراجعة إعدادات المنصات") }
                        }
                        if (preflightFailure == null && PlainComparisonSupport.existingTwoRow(cart)) Text("المقارنة الحالية تدعم صنفين دون خيارات. يفحص التطبيق السلة أولًا؛ السلة المختلفة لا تُستبدل تلقائيًا.",fontSize=12.sp,color=OctopusPalette.Muted)
                        if (!nativeConfigured && !PreviewEvidence.supports(cart) && !PlainComparisonSupport.existingTwoRow(cart)) Text("حُفظ طلبك كاملًا. راجع سبب عدم الجاهزية أعلاه قبل بدء المقارنة.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        Text("يجهّز السلتين ويقرأ إجمالي المراجعة إذا تعرّف على مسارها، دون تأكيد طلب أو دفع. النتيجة غير المقروءة لا تُقدّر.", fontSize = 12.sp, color = OctopusPalette.Muted)
                        state.comparisonProgress.values.forEach { result ->
                            Text("${result.provider.title}: ${result.detail ?: "جارٍ تجهيز السلة والتحقق منها…"}", fontSize = 12.sp)
                        }
                        ProviderId.entries.forEach { id ->
                            if (!nativeConfigured && state.preparedBaskets.forRequest(cart, id) != null) TextButton(onClick = {
                                finishManualReading()
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
                                // Partial accessibility fields can contain a pre-discount fee. Until the
                                // full breakdown is reconciled, only the independently read total is shown.
                                Text("الإجمالي مقروء؛ لم يكتمل التحقق من تفاصيل الرسوم والخصومات.",fontSize=12.sp,color=OctopusPalette.Muted)
                                Text("تم التحقق من السلة وقت القراءة. تطابق العنوان والفرع يحتاج تحققًا مستقلًا؛ لم يُعتمد ترتيب الأرخص.", fontSize = 11.sp, color = OctopusPalette.Muted)
                            }
                        }
                    } }
                }
            }
            if (!comparisonOnly && state.previews.isNotEmpty()) {
                item { Text("أسعار الوجبات المقروءة", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                item { Text("بيانات القائمة منفصلة عن إجمالي مراجعة الطلب. لا يدخل سعر ناقص في ترتيب النتائج، وتطابق العنوان والفرع يحتاج تحققًا.", fontSize = 12.sp, color = OctopusPalette.Muted) }
                item { ComparisonSummary(state.previews, state.finalTotals, cart, currentQuoteTime) }
                items(state.previews, key = { it.provider }) { preview ->
                    val fresh = preview.fresh(cart, currentQuoteTime)
                    CostPreviewCard(preview, fresh, currentQuoteTime)
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
            val visible = state.restaurants.filter {
                (category == "الكل" || (it.name + " " + it.description.orEmpty()).contains(category)) &&
                    CatalogSearch.matches(it.name, query)
            }
            if (visible.isEmpty()) item { Text("لا توجد مطاعم تطابق البحث في الصفحات المحمّلة. حدّث القائمة أو حمّل مطاعم إضافية.", fontSize = 13.sp) }
            items(visible, key = { it.catalogKey }) { restaurant ->
                Card(onClick = {
                    finishManualReading(); localError = null
                    if (!LiveCatalogBridge.openMenu(restaurant)) localError = graph.catalog.value.error
                        ?: "قائمة هذا المطعم غير محفوظة. افتحها في هنقرستيشن ثم اضغط «احفظ وارجع للأخطبوط»."
                }, enabled = !state.busy,
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(22.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(48.dp).background(OctopusPalette.Soft, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                            Text(restaurant.name.take(1), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = OctopusPalette.Petrol)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(restaurant.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            restaurant.description?.let { Text(it, color = OctopusPalette.Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            Text(if (restaurant.origin == CatalogOrigin.MANUAL) "هنقرستيشن • قراءة يدوية محفوظة" else "موقع هنقرستيشن • قائمة عامة", fontSize = 11.sp, color = OctopusPalette.Petrol)
                        }
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp), tint = OctopusPalette.Petrol)
                    }
                }
            }
            if (publicState.page < publicState.pages && publicState.directory.size < 500) item {
                OutlinedButton(onClick = { graph.publicCatalog.refresh(nextPage = true) },
                    enabled = !state.busy && !publicState.refreshing, modifier = Modifier.fillMaxWidth()) { Text("تحميل مطاعم إضافية") }
            }
        }
        if (!comparisonOnly) state.menu?.let { menu ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            TextButton(onClick = { showRestaurants(true) }, enabled = !state.busy) { Text("العودة للمطاعم") }
                    Text(menu.restaurant.name, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
                    Text("اختر وجباتك وخصصها داخل الأخطبوط", color = OctopusPalette.Muted, fontSize = 13.sp)
                    Text("${menu.items.size} صنف مقروء • ${menu.restaurant.source.title}", color = OctopusPalette.Petrol, fontSize = 12.sp)
                    OutlinedTextField(value = menuQuery, onValueChange = { menuQuery = it.take(100) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(18.dp),
                        placeholder = { Text("ابحث في الوجبات المقروءة") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) })
                }
            }
            val visibleMeals = menu.items.filter { CatalogSearch.matches(it.name, menuQuery) }
            if (visibleMeals.isEmpty()) item { Text("لم تظهر وجبة بهذا الاسم في القائمة المقروءة.", fontSize = 13.sp) }
            items(visibleMeals, key = { it.selector }) { item ->
                Card(onClick = {
                    finishManualReading(); localError = null
                    showProduct = true
                    if (!LiveCatalogBridge.openProduct(menu.restaurant, item)) localError = graph.catalog.value.error
                        ?: "خيارات هذه الوجبة غير محفوظة. افتحها في هنقرستيشن ثم اضغط «احفظ وارجع للأخطبوط»."
                }, enabled = !state.busy && ready, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text(item.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(item.listedPrice?.let { money(it) + " • سعر قائمة محفوظ" } ?: "السعر غير مقروء", color = OctopusPalette.Petrol, fontWeight = FontWeight.Bold)
                            Text(if (item.selector.startsWith("public-hs:")) "اختيار الكمية" else "عرض الخيارات", color = OctopusPalette.Muted, fontSize = 11.sp)
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
        item { Text("اختر المطعم والوجبة. تُحفظ الخيارات عند اكتمال قراءتها؛ تحدد شاشة المقارنة جاهزية تجهيز طلبك حسب إصدارات التطبيقين. الأصناف والتوصيل لا يمثلان الإجمالي النهائي مع الرسوم.", fontSize = 11.sp, color = OctopusPalette.Muted) }
    }
    if (areaDialog) CatalogAreaDialog(graph, onDismiss = { areaDialog = false })
    if (manageCarts) AlertDialog(onDismissRequest = { manageCarts = false },
        title = { Text("إدارة السلال") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("يمكنك حذف طلب الأخطبوط هنا. لتفريغ سلة منصة التوصيل، افتح تطبيقها واحذفها بنفسك؛ ستُلغى نتيجة مقارنتها القديمة.")
            OutlinedButton(onClick = {
                deleteError = null; cartToDelete = draft; manageCarts = false
            }, enabled = draft != null && ready && !state.busy && !changing,
                modifier = Modifier.fillMaxWidth()) { Text("حذف سلة الأخطبوط") }
            ProviderId.entries.forEach { provider ->
                OutlinedButton(onClick = {
                    finishManualReading()
                    if (graph.openProviderCartManagement(provider)) manageCarts = false
                    else localError = "تعذر فتح ${provider.title}؛ تحقق من تثبيته وانتهاء العملية الحالية."
                }, enabled = !state.busy && !changing, modifier = Modifier.fillMaxWidth()) {
                    Text("فتح ${provider.title} لحذف سلته")
                }
                TextButton(onClick = { draft?.let { clearedCartConfirmation = provider to it; manageCarts = false } },
                    enabled = draft != null && ready && !state.busy && !changing) {
                    Text("أفرغت سلة ${provider.title} وأريد البدء من جديد")
                }
            }
            localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(onClick = { manageCarts = false }) { Text("رجوع") } })
    clearedCartConfirmation?.let { (provider, confirmedCart) ->
        AlertDialog(onDismissRequest = { clearedCartConfirmation = null },
            title = { Text("تأكيد إفراغ سلة ${provider.title}") },
            text = { Text("أكد فقط إذا حذفت جميع أصناف ${confirmedCart.restaurant} من ${provider.title}. يُزال سجل السلة السابقة لهذا المطعم فقط، ويُفحص التطبيق مجددًا قبل أي إضافة. إذا بقيت أصناف، ارجع للمنصة أولًا.") },
            confirmButton = { TextButton(onClick = {
                localError = null
                try { graph.confirmProviderCartCleared(provider, confirmedCart.fingerprint); clearedCartConfirmation = null }
                catch (_: Exception) { localError = "تعذر اعتماد الإفراغ؛ تأكد من عدم وجود عملية نشطة أو طلب متغير ثم حاول مجددًا."; clearedCartConfirmation = null }
            }, enabled = !state.busy && !changing) { Text("نعم، حذفت جميع أصناف هذا المطعم") } },
            dismissButton = { TextButton(onClick = { clearedCartConfirmation = null }) { Text("رجوع") } })
    }
    cartToDelete?.let { target ->
        AlertDialog(onDismissRequest = { if (!changing) cartToDelete = null },
            title = { Text("حذف سلة الأخطبوط؟") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("سيُحذف طلب ${target.restaurant} (${target.items.sumOf { it.quantity }} وجبة) ونتائج مقارنته من الأخطبوط. تبقى سلال كيتا وهنقرستيشن داخل تطبيقاتها.")
                deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !changing && !state.busy, onClick = {
                finishManualReading(); changing = true; deleteError = null
                graph.scope.launch {
                    try {
                        when (val result = graph.deleteActiveCart(target.fingerprint)) {
                            is DraftChange.Changed -> { cartToDelete = null; localError = null }
                            is DraftChange.Rejected -> deleteError = result.reason
                            else -> deleteError = "تعذر حذف السلة."
                        }
                    } catch (_: Exception) { deleteError = "تعذر حفظ حذف السلة؛ حاول مجددًا." }
                    finally { changing = false }
                }
            }) { Text("احذف سلة الأخطبوط") } },
            dismissButton = { TextButton(enabled = !changing, onClick = { cartToDelete = null }) { Text("إلغاء") } })
    }
    publicMeal?.let { (menu, meal) -> PublicMealDialog(graph, menu, meal,
        onDismiss = { graph.publicMealSelection.value = null }) }
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
            showProduct = false
        })
    }
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("السماح باستعراض المطاعم") },
        text = { Text(if (nativeCartRequested)
            "يفتح هنقرستيشن لتختار طلبك بنفسك. عند ضغط الزر العائم «قارن» تُقرأ السلة كاملة ويُحفظ الطلب محليًا، ثم تُقرأ مراجعة هنقرستيشن ويُجهّز الطلب المطابق في كيتا وتعود النتيجة للأخطبوط. يتوقف عند سلة مختلفة أو خيار غير مدعوم. راجع تطابق عنوان التوصيل والفرع. لا يُؤكد طلب أو دفع."
            else "يفتح هنقرستيشن لتتصفحه بنفسك. الأخطبوط لا ينقر ولا يمرّر أثناء الاستعراض. يحفظ محليًا بيانات المطاعم والوجبات وخياراتها التي تعرضها أثناء هذه الجلسة. زر «احفظ وارجع للأخطبوط» ينهي القراءة ويعيدك للاختيار هنا. لا يبدأ التنقل وتجهيز السلتين إلا بعد ضغط زر المقارنة. لا يُؤكد طلب أو دفع.") },
        confirmButton = { TextButton(onClick = {
            consent = false
            graph.scope.launch { graph.settingsStore.acceptCatalogConsent(); graph.settings.first { it.catalogConsent }; beginManualReading() }
        }) { Text("ابدأ الاستعراض") } }, dismissButton = { TextButton(onClick = { consent = false }) { Text("إلغاء") } })
}

@Composable
private fun MosabHomeHeader(query: String, onQuery: (String) -> Unit, enabled: Boolean,
    onSearch: () -> Unit, onBrowse: () -> Unit, onProviders: () -> Unit, onCart: () -> Unit, onSettings: () -> Unit, allowed: Int,
    browseEnabled: Boolean, browseLabel: String) {
    Column(Modifier.fillMaxWidth().statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_octopus_selected), "الأخطبوط", Modifier.size(38.dp))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text("الأخطبوط", color = OctopusPalette.Petrol, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("طلب واحد، أسعار أوضح", color = OctopusPalette.Muted, fontSize = 12.sp)
            }
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "الإعدادات", tint = OctopusPalette.Petrol) }
            IconButton(onClick = onCart) { Icon(Icons.Outlined.ShoppingBag, "طلبك", tint = OctopusPalette.Petrol) }
        }
        OutlinedTextField(value = query, onValueChange = onQuery, enabled = enabled,
            modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp),
            placeholder = { Text("ابحث عن مطعم…", fontSize = 14.sp) }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { IconButton(onClick = onSearch, enabled = enabled && query.isNotBlank()) {
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, "ابحث")
            } }, colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White,
                focusedContainerColor = Color.White, unfocusedBorderColor = OctopusPalette.Line))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onProviders) { Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp)); Text("المنصات · $allowed") }
            OutlinedButton(onClick = onBrowse, enabled = browseEnabled, shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp))
                Text(browseLabel, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MosabJourneyHero(title: String, subtitle: String, active: Int) {
    Box(Modifier.fillMaxWidth().background(Brush.linearGradient(
        listOf(OctopusPalette.Ink, OctopusPalette.Petrol, OctopusPalette.SeaMid)), RoundedCornerShape(20.dp)).padding(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.size(7.dp).background(OctopusPalette.Coral, CircleShape))
                Text("الأخطبوط • اختياراتك أوضح", color = Color(0xFFBED4E0), fontSize = 12.sp)
            }
            Text(title, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
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
private fun CostPreviewCard(preview: MenuCostPreview, fresh: Boolean, now: Long) {
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
            Text("هذه أسعار القائمة؛ الإجمالي يؤخذ من مراجعة الطلب", fontWeight = FontWeight.Bold)
            preview.error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        }
    }
}


@Composable
private fun ComparisonSummary(previews: List<MenuCostPreview>, totals: Map<ProviderId, FinalTotalReading>, cart: CanonicalCart, now: Long) {
    val providers = listOf(ProviderId.HUNGERSTATION, ProviderId.KEETA)
    val sources = providers.map { id -> previews.firstOrNull { it.provider == id } }
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
                    sources.forEachIndexed { sourceIndex, source ->
                        val value = if (index == 4) totals[providers[sourceIndex]]?.let { if (it.fresh(cart, now)) money(it.total) else "قراءة سابقة — أعد التحقق" } ?: "لم يُقرأ الإجمالي"
                        else if (source?.fresh(cart, now) != true) "غير مقروء" else when (index) {
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
