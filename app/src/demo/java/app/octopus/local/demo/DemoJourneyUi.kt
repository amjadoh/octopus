package app.octopus.local.demo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.octopus.local.domain.ProviderId
import app.octopus.local.ui.OctopusPalette as P
import app.octopus.local.ui.OctopusTheme
import kotlinx.coroutines.delay
import java.math.BigDecimal

private fun money(value: Long) = BigDecimal.valueOf(value, 2).toPlainString() + " ر.س"

@Composable
fun DemoJourneyApp(controller: DemoJourneyController) {
    val state by controller.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.page, state.epoch) {
        if (state.page == JourneyPage.COMPARING) {
            val epoch = state.epoch
            repeat(5 - state.progress) { delay(400); controller.advanceComparison(epoch) }
        }
    }
    val back = {
        when (state.page) {
            JourneyPage.RESTAURANTS -> Unit
            JourneyPage.MENU -> controller.navigate(JourneyPage.RESTAURANTS)
            JourneyPage.ITEM -> controller.navigate(JourneyPage.MENU)
            JourneyPage.COMPARING -> controller.cancelComparison()
            JourneyPage.READY, JourneyPage.HANDOFF -> controller.navigate(JourneyPage.RESULTS)
            JourneyPage.PROVIDERS, JourneyPage.ADDRESSES -> controller.navigate(if (state.cart.isEmpty()) JourneyPage.RESTAURANTS else JourneyPage.CART)
            else -> controller.navigate(JourneyPage.RESTAURANTS)
        }
    }
    BackHandler(enabled = state.page != JourneyPage.RESTAURANTS, onBack = back)
    OctopusTheme {
        Scaffold(containerColor = Color.White,
            topBar = {
                Column(Modifier.statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 21.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(color = P.Petrol, shape = RoundedCornerShape(8.dp)) { Text("أ", Modifier.padding(horizontal = 10.dp, vertical = 3.dp), color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold) }
                        Column(Modifier.weight(1f)) { Text("الأخطبوط", fontSize = 20.sp, fontWeight = FontWeight.Bold); Text("اختيار واحد. مقارنة أوضح.", fontSize = 11.sp, color = P.Muted) }
                        IconButton(onClick = { controller.navigate(JourneyPage.MORE) }) { Icon(Icons.Outlined.Settings, "المزيد", tint = P.Ink) }
                    }
                    Surface(color = P.Soft) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 19.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Science, null, Modifier.size(17.dp), tint = P.Petrol)
                            Text("وضع عرض • مطاعم وأسعار توضيحية • لا اتصال بالتطبيقات", fontSize = 11.sp, color = P.Petrol, lineHeight = 18.sp)
                        }
                    }
                }
            },
            bottomBar = {
                Column {
                    HorizontalDivider(color = P.Line)
                    NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                        val active = when (state.page) {
                            JourneyPage.RESTAURANTS, JourneyPage.MENU, JourneyPage.ITEM -> 0
                            JourneyPage.CART, JourneyPage.COMPARING -> 1
                            JourneyPage.RESULTS, JourneyPage.READY, JourneyPage.HANDOFF -> 2
                            else -> 3
                        }
                        listOf(
                            Triple("المطاعم", Icons.Outlined.Restaurant, JourneyPage.RESTAURANTS),
                            Triple("طلبي" + state.cart.sumOf { it.quantity }.takeIf { it > 0 }?.let { " ($it)" }.orEmpty(), Icons.Outlined.ShoppingBag, JourneyPage.CART),
                            Triple("المقارنة", Icons.Outlined.CompareArrows, JourneyPage.RESULTS),
                            Triple("المزيد", Icons.Outlined.Settings, JourneyPage.MORE),
                        ).forEachIndexed { index, (title, icon, page) ->
                            NavigationBarItem(selected = active == index, onClick = { controller.navigate(page) },
                                icon = { Icon(icon, null, Modifier.size(22.dp)) }, label = { Text(title, fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(indicatorColor = P.Soft, selectedIconColor = P.Petrol,
                                    selectedTextColor = P.Petrol, unselectedIconColor = P.Muted, unselectedTextColor = P.Muted))
                        }
                    }
                }
            }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                JourneyBody(state, controller, back, Modifier.widthIn(max = 720.dp))
            }
        }
        state.pendingRestaurant?.let {
            AlertDialog(onDismissRequest = controller::dismissRestaurantChange, containerColor = Color.White,
                title = { Text("تغيير المطعم؟") }, text = { Text("طلبك الحالي من ${DemoCatalog.restaurant(state.restaurantId)?.name}. يلزم استبدال سلة العرض للبدء من مطعم آخر. هذا لا يمس أي سلة حقيقية.") },
                confirmButton = { TextButton(onClick = controller::confirmRestaurantChange) { Text("استبدال سلة العرض") } },
                dismissButton = { TextButton(onClick = controller::dismissRestaurantChange) { Text("الاحتفاظ بطلبي") } })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun JourneyBody(state: DemoJourneyState, c: DemoJourneyController, back: () -> Unit, modifier: Modifier) {
    val restaurant = DemoCatalog.restaurant(state.restaurantId)
    val count = state.cart.sumOf { it.quantity }
    LazyColumn(modifier.fillMaxSize().testTag("journey-list"), contentPadding = PaddingValues(21.dp), verticalArrangement = Arrangement.spacedBy(19.dp)) {
        if (state.page != JourneyPage.RESTAURANTS) item {
            TextButton(onClick = back, contentPadding = PaddingValues(0.dp)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)); Text("رجوع", fontSize = 12.sp) }
        }
        when (state.page) {
            JourneyPage.RESTAURANTS -> {
                item { TextButton(onClick = { c.navigate(JourneyPage.ADDRESSES) }, contentPadding = PaddingValues(0.dp)) { Icon(Icons.Outlined.LocationOn, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(state.address.title, fontSize = 12.sp) } }
                item { Intro("طلب واحد.\nمقارنة واضحة.", "اختر المطعم، أضف وجباتك، ثم قارن إجمالي طلبك.") }
                item { StepTrail(0) }
                item { OutlinedTextField(value = state.search, onValueChange = c::search, modifier = Modifier.fillMaxWidth(), placeholder = { Text("ابحث عن مطعمك", fontSize = 14.sp) }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, shape = RoundedCornerShape(13.dp)) }
                item { FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("الكل", "برجر", "بيتزا", "مشويات").forEach { category -> FilterChip(selected = state.category == category, onClick = { c.category(category) }, label = { Text(category, fontSize = 11.sp) }) }
                } }
                item { Title("المطاعم المتاحة في المثال") }
                val matched = DemoCatalog.restaurants.filter { (state.category == "الكل" || it.category == state.category) && (it.name + " " + it.category + " " + it.description).contains(state.search.trim()) }
                if (matched.isEmpty()) item { Hint("لا توجد نتائج في قائمة المثال؛ غيّر البحث أو التصنيف.") }
                items(matched, key = { it.id }) { r ->
                    Column(Modifier.fillMaxWidth().clickable { c.chooseRestaurant(r.id) }.testTag("restaurant-${r.id}")) {
                        Row(Modifier.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                            FoodThumb(r.symbol, 72)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(r.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text(r.description, fontSize = 12.sp, color = P.Muted, lineHeight = 20.sp)
                                Text("كيتا  •  هنقرستيشن", fontSize = 11.sp, color = P.Petrol)
                            }
                            Icon(Icons.Outlined.ChevronLeft, null, tint = P.Muted)
                        }
                        HorizontalDivider(color = P.Line)
                    }
                }
                item { Panel { Title("قارن التكلفة كاملة"); Body("الأصناف والتوصيل والرسوم والخصومات في نتيجة واحدة. جميعها بيانات توضيحية في نسخة العرض.") } }
                if (count > 0) item { MainButton("متابعة طلبي ($count)") { c.navigate(JourneyPage.CART) } }
            }
            JourneyPage.MENU -> {
                item { Intro(restaurant?.name.orEmpty(), "فرع توضيحي • القائمة للاختبار البصري فقط") }
                item { StepTrail(1) }
                items(DemoCatalog.items.filter { it.restaurantId == state.restaurantId }, key = { it.id }) { item ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FoodThumb(item.symbol, 65)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(item.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(item.description, fontSize = 11.sp, color = P.Muted, lineHeight = 19.sp)
                            Text("من ${money(item.baseHalala)}", color = P.Petrol, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedIconButton(onClick = { c.openItem(item.id) }, modifier = Modifier.testTag("add-${item.id}"), shape = RoundedCornerShape(11.dp)) { Icon(Icons.Outlined.Add, "إضافة ${item.name}", tint = P.Petrol) }
                    }
                    HorizontalDivider(color = P.Line)
                }
                item { MainButton("مراجعة طلبي ($count) • ${money(DemoPricing.subtotal(state.cart))}", count > 0) { c.navigate(JourneyPage.CART) } }
            }
            JourneyPage.ITEM -> state.draft?.let { draft ->
                val selectedItem = DemoCatalog.item(draft.itemId)
                item { Surface(color = P.Soft, shape = RoundedCornerShape(14.dp)) { Box(Modifier.fillMaxWidth().height(118.dp), contentAlignment = Alignment.Center) { Text(selectedItem.symbol, fontSize = 65.sp) } } }
                item { Intro(selectedItem.name, selectedItem.description) }
                if (selectedItem.sizes.isNotEmpty()) item { OptionGroup("الحجم", "إلزامي", selectedItem.sizes, draft.sizeId, "size", c::size) }
                if (selectedItem.drinks.isNotEmpty()) item { OptionGroup("المشروب", "إلزامي", selectedItem.drinks, draft.drinkId, "drink", c::drink) }
                if (selectedItem.extras.isNotEmpty()) item { Panel {
                    Title("الإضافات • اختياري")
                    selectedItem.extras.forEach { option -> Row(Modifier.fillMaxWidth().clickable { c.extra(option.id, option.id !in draft.extras) }.testTag("extra-${option.id}"), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = option.id in draft.extras, onCheckedChange = { c.extra(option.id, it) })
                        Text(option.name, Modifier.weight(1f), fontSize = 13.sp)
                        Text("+${money(option.priceHalala)}", color = P.Muted, fontSize = 12.sp)
                    } }
                } }
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Title("الكمية"); Quantity(draft.quantity, c::draftQuantity) } }
                if (!DemoPricing.valid(draft)) item { Hint("أكمل الخيار الإلزامي، مثل المشروب، لإضافة الوجبة كاملة.") }
                item { MainButton((if (draft.editingId == null) "أضف إلى طلبي" else "حفظ التعديل") + " • ${money(DemoPricing.unit(draft) * draft.quantity)}", DemoPricing.valid(draft), "draft-add") { c.addDraft() } }
            }
            JourneyPage.CART -> {
                item { Intro("طلبك، على ذوقك", restaurant?.name ?: "اختر أول وجبة لبدء المقارنة.") }
                if (state.cart.isEmpty()) item { Panel { Title("سلتك فارغة"); Body("أضف الوجبات التي تريدها، ثم جرّب المقارنة بين المنصتين."); MainButton("اختيار مطعم") { c.navigate(JourneyPage.RESTAURANTS) } } }
                else {
                    items(state.cart, key = { it.id }) { line -> CartLine(line, true, c) }
                    item { SecondaryButton("إضافة وجبات أخرى") { c.navigate(JourneyPage.MENU) } }
                    item { Panel {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Title("عنوان التوصيل"); TextButton(onClick = { c.navigate(JourneyPage.ADDRESSES) }) { Text("تغيير") } }
                        Body(state.address.title); Hint("ربط العنوان بين المنصتين هنا تمثيل توضيحي فقط.")
                    } }
                    item { Panel {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Title("تطبيقات المقارنة"); TextButton(onClick = { c.navigate(JourneyPage.PROVIDERS) }) { Text("تعديل") } }
                        Body(state.allowed.joinToString(" • ") { it.title }.ifEmpty { "لم تختر أي تطبيق" })
                        BillRow("مجموع القائمة التوضيحي", DemoPricing.subtotal(state.cart))
                        Body("تظهر الرسوم والخصومات في المقارنة.")
                    } }
                    if (state.allowed.size < 2) item { Hint("فعّل كيتا وهنقرستيشن لتجربة المقارنة بينهما.") }
                    item { MainButton("قارن نفس الطلب بين التطبيقين", state.allowed.size == 2, "compare-start") { c.beginComparison() } }
                }
                state.message?.let { message -> item { Hint(message) } }
            }
            JourneyPage.PROVIDERS -> {
                item { Intro("تطبيقات التوصيل", "اختر التطبيقات التي ستشملها محاكاة المقارنة.") }
                items(ProviderId.entries) { provider -> Panel {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PlatformMark(provider); Column(Modifier.weight(1f)) { Title(provider.title); Body("الحساب النشط داخل التطبيق") }
                        Switch(checked = provider in state.allowed, onCheckedChange = { c.allow(provider, it) })
                    }
                    Body("متاح ضمن المثال فقط. التثبيت وتسجيل الدخول والقراءة الفعلية تحتاج اختبار الهاتف.")
                } }
                item { Hint("لا نوحد الحسابات ولا نطلب كلمات مرور أو رموز تحقق.") }
                item { MainButton("العودة إلى طلبي") { c.navigate(JourneyPage.CART) } }
            }
            JourneyPage.ADDRESSES -> {
                item { Intro("عنوان التوصيل", "هذه عناوين توضيحية؛ لا نقرأ موقعك أو عنوانك الفعلي.") }
                items(DemoAddress.entries) { address ->
                    Surface(color = Color.White, border = BorderStroke(if (state.address == address) 2.dp else 1.dp, if (state.address == address) P.Petrol else P.Line), shape = RoundedCornerShape(13.dp), onClick = { c.address(address) }) {
                        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(Icons.Outlined.LocationOn, null, tint = P.Petrol); Column(Modifier.weight(1f)) { Title(address.title); Body("الرياض • بيانات مثال") }; if (address == state.address) Icon(Icons.Outlined.CheckCircle, null, tint = P.Petrol) }
                    }
                }
            }
            JourneyPage.COMPARING -> {
                item { Intro("نقارن نفس طلبك", "محاكاة مرئية فقط؛ لا يجري فتح التطبيقات أو قراءة أسعار فعلية.") }
                item { Panel {
                    Title(restaurant?.name.orEmpty()); Body("$count أصناف • ${state.address.title}")
                    LinearProgressIndicator(progress = { state.progress / 5f }, modifier = Modifier.fillMaxWidth(), color = P.Petrol, trackColor = P.Soft)
                    listOf("تثبيت تفاصيل الطلب", "محاكاة تجهيز كيتا", "محاكاة تجهيز هنقرستيشن", "تمثيل قراءة الرسوم والخصومات", "ترتيب نتائج المثال").forEachIndexed { index, label -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) { Icon(if (index < state.progress) Icons.Outlined.CheckCircle else Icons.Outlined.Circle, null, tint = if (index < state.progress) P.Petrol else P.Muted, modifier = Modifier.size(20.dp)); Text(label, fontSize = 13.sp) } }
                } }
                item { SecondaryButton("إيقاف المحاكاة والعودة للطلب", c::cancelComparison) }
            }
            JourneyPage.RESULTS -> {
                item { Intro("نفس الطلب. فرق أوضح.", "اختر الأنسب بعد مراجعة تفاصيل تكلفة المثال.") }
                if (state.quotes.isEmpty()) item { Panel { Title("لا توجد نتائج بعد"); Body("ابدأ المقارنة من طلبك. تعديل السلة أو العنوان يلغي النتائج السابقة."); MainButton("الذهاب إلى طلبي") { c.navigate(JourneyPage.CART) } } }
                else {
                    item { StepTrail(2) }
                    item { Hint("${restaurant?.name} • $count أصناف. حالة تطابق السلة والفرع والعنوان هنا توضيحية فقط.") }
                    items(state.quotes, key = { it.provider.name }) { quote ->
                        QuoteCard(quote, best = quote == state.quotes.first(), selected = quote.provider == state.selected) { c.select(quote.provider) }
                    }
                    if (state.selected != null) item { MainButton("متابعة مع ${state.selected.title}", tag = "selected-continue") { c.navigate(JourneyPage.READY) } }
                    item { Body("الرصيد وسيلة تغطية للمبلغ؛ لا يُحسب خصمًا عند ترتيب تكلفة الطلب.") }
                }
            }
            JourneyPage.READY, JourneyPage.HANDOFF -> {
                val quote = state.quotes.firstOrNull { it.provider == state.selected }
                if (quote != null) {
                    if (state.page == JourneyPage.HANDOFF) item { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = P.Petrol, modifier = Modifier.size(38.dp)) }
                    item { Intro(if (state.page == JourneyPage.READY) "طلبك مع ${quote.provider.title}" else "هنا تستلم طلبك في ${quote.provider.title}", if (state.page == JourneyPage.READY) "راجع السلة قبل معاينة الانتقال." else "نهاية رحلة التصميم؛ لم يُفتح تطبيق فعلي ولم يُرسل طلب.") }
                    item { Panel { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { PlatformMark(quote.provider); Title(quote.provider.title) }; Body(restaurant?.name.orEmpty()); Body(state.address.title) } }
                    items(state.cart, key = { it.id }) { line -> CartLine(line, false, c, quote.provider) }
                    item { Panel { QuoteDetails(quote); if (quote.wallet > 0) BillRow("رصيد محفظة تجريبي مستخدم", quote.wallet); BillRow("المتبقي للدفع في المثال", quote.dueNow, true) } }
                    if (state.page == JourneyPage.READY) item { MainButton("معاينة فتح السلة في ${quote.provider.title}", tag = "handoff-preview") { c.navigate(JourneyPage.HANDOFF) } }
                    else {
                        item { Hint("في التطبيق النهائي يُعاد التحقق من السلة والعنوان والسعر، ثم يتوقف الأخطبوط وتكمل أنت المراجعة والدفع داخل التطبيق الأصلي.") }
                        item { MainButton("الدفع داخل التطبيق الأصلي — غير متاح في العرض", false) {} }
                        item { SecondaryButton("العودة إلى طلبي") { c.navigate(JourneyPage.CART) } }
                    }
                }
            }
            JourneyPage.MORE -> {
                item { Intro("الإعدادات والخصوصية", "الأبيض والبترولي • التصميم المعتمد") }
                item { Panel { Title("تطبيقات المقارنة"); Body("لا حاجة لتوحيد الحسابات."); SecondaryButton("اختيار التطبيقات") { c.navigate(JourneyPage.PROVIDERS) } } }
                item { Panel { Title("العنوان المرجعي"); Body(state.address.title); SecondaryButton("تغيير عنوان المثال") { c.navigate(JourneyPage.ADDRESSES) } } }
                item { Panel { Title("وضع العرض معزول"); Body("لا تصل هذه النسخة إلى شاشات التطبيقات ولا تشغل خدمة القراءة. بيانات المطاعم والأسعار أمثلة محلية فقط."); Body("استخدم APK التشخيصي المنفصل لاختبار قراءة إجمالي حقيقي. لا تدخل نتائج هذا العرض سجل الاختبارات الفعلية.") } }
            }
        }
    }
}

@Composable private fun Intro(title: String, subtitle: String) { Column(verticalArrangement = Arrangement.spacedBy(7.dp)) { Text(title, fontSize = 27.sp, fontWeight = FontWeight.Bold, color = P.Ink, lineHeight = 39.sp); Body(subtitle) } }
@Composable private fun Title(text: String) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = P.Ink) }
@Composable private fun Body(text: String) { Text(text, fontSize = 13.sp, lineHeight = 22.sp, color = P.Muted) }
@Composable private fun Hint(text: String) { Surface(color = P.Soft, shape = RoundedCornerShape(13.dp)) { Row(Modifier.fillMaxWidth().padding(13.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(Icons.Outlined.Info, null, tint = P.Petrol, modifier = Modifier.size(18.dp)); Text(text, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 20.sp, color = P.Petrol) } } }
@Composable private fun Panel(content: @Composable ColumnScope.() -> Unit) { Surface(color = Color.White, shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, P.Line)) { Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(13.dp), content = content) } }
@Composable private fun MainButton(text: String, enabled: Boolean = true, tag: String = "", onClick: () -> Unit) { Button(onClick, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(tag), enabled = enabled, shape = RoundedCornerShape(14.dp)) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) } }
@Composable private fun SecondaryButton(text: String, onClick: () -> Unit) { OutlinedButton(onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) { Text(text, fontSize = 13.sp) } }
@Composable private fun FoodThumb(symbol: String, size: Int) { Surface(color = P.Soft, shape = RoundedCornerShape(13.dp)) { Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) { Text(symbol, fontSize = (size / 2).sp) } } }
@Composable private fun PlatformMark(provider: ProviderId) { Surface(color = P.Soft, shape = RoundedCornerShape(12.dp)) { Box(Modifier.size(43.dp), contentAlignment = Alignment.Center) { Text(if (provider == ProviderId.KEETA) "ك" else "هـ", color = P.Petrol, fontSize = 23.sp, fontWeight = FontWeight.SemiBold) } } }
@Composable private fun StepTrail(active: Int) { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { HorizontalDivider(color = P.Line); Row(Modifier.fillMaxWidth()) { listOf("١ اختيار المطعم", "٢ تجهيز الطلب", "٣ المقارنة").forEachIndexed { index, text -> Text(text, Modifier.weight(1f), fontSize = 11.sp, color = if (active == index) P.Petrol else P.Muted, fontWeight = if (active == index) FontWeight.SemiBold else FontWeight.Normal) } }; HorizontalDivider(color = P.Line) } }
@Composable private fun OptionGroup(title: String, status: String, options: List<DemoOption>, selected: String?, tag: String, onSelect: (String) -> Unit) { Panel { Title("$title • $status"); options.forEach { option -> Row(Modifier.fillMaxWidth().clickable { onSelect(option.id) }.testTag("$tag-${option.id}"), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = option.id == selected, onClick = { onSelect(option.id) }); Text(option.name, Modifier.weight(1f), fontSize = 13.sp); Text(if (option.priceHalala == 0L) "بدون زيادة" else "+${money(option.priceHalala)}", color = P.Muted, fontSize = 12.sp) } } } }
@Composable private fun Quantity(value: Int, onChange: (Int) -> Unit) { Row(verticalAlignment = Alignment.CenterVertically) { OutlinedIconButton(onClick = { onChange(-1) }, enabled = value > 1, shape = RoundedCornerShape(10.dp)) { Icon(Icons.Outlined.Remove, "تقليل الكمية") }; Text("$value", Modifier.padding(horizontal = 12.dp), fontSize = 14.sp); OutlinedIconButton(onClick = { onChange(1) }, enabled = value < 10, shape = RoundedCornerShape(10.dp)) { Icon(Icons.Outlined.Add, "زيادة الكمية") } } }
@Composable private fun BillRow(label: String, amount: Long, total: Boolean = false) { if (total) HorizontalDivider(color = P.Line); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text(label, Modifier.weight(1f), fontSize = if (total) 15.sp else 12.sp, color = if (total) P.Ink else P.Muted, fontWeight = if (total) FontWeight.SemiBold else FontWeight.Normal); Text(money(amount), fontSize = if (total) 16.sp else 13.sp, fontWeight = if (total) FontWeight.SemiBold else FontWeight.Normal, color = P.Ink) } }
@Composable private fun CartLine(line: DemoLine, editable: Boolean, c: DemoJourneyController, provider: ProviderId = ProviderId.KEETA) { Panel { Row(horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.Top) { FoodThumb(DemoCatalog.item(line.itemId).symbol, 55); Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(DemoCatalog.item(line.itemId).name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold); Text(DemoPricing.summary(line), fontSize = 12.sp, lineHeight = 20.sp, color = P.Muted); Text("الكمية ${line.quantity} • ${money(DemoPricing.unit(line, provider) * line.quantity)}", fontSize = 13.sp, color = P.Petrol) } }; if (editable) { Quantity(line.quantity) { c.quantity(line.id, it) }; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { TextButton(onClick = { c.openItem(line.itemId, line.id) }) { Text("تعديل الخيارات") }; TextButton(onClick = { c.remove(line.id) }) { Icon(Icons.Outlined.DeleteOutline, null, tint = P.Error, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("حذف", color = P.Error) } } } } }
@Composable private fun QuoteCard(quote: DemoQuote, best: Boolean, selected: Boolean, onSelect: () -> Unit) {
    var expanded by remember(quote) { mutableStateOf(false) }
    Surface(color = Color.White, shape = RoundedCornerShape(13.dp), border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) P.Petrol else P.Line)) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { PlatformMark(quote.provider); Text(quote.provider.title, Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold); if (best) Surface(color = P.Soft, shape = RoundedCornerShape(30.dp)) { Text("الأقل في المثال", Modifier.padding(8.dp), fontSize = 11.sp, color = P.Petrol) } }
            Text(money(quote.orderCost), fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "إخفاء التفاصيل" else "تفاصيل الرسوم والخصم", fontSize = 12.sp) }
            if (expanded) { QuoteDetails(quote); if (quote.wallet > 0) BillRow("رصيد محفظة تجريبي منفصل", quote.wallet); BillRow("المتبقي للدفع", quote.dueNow) }
            if (selected) MainButton("تم اختيار ${quote.provider.title}", tag = "pick-${quote.provider.name}", onClick = onSelect)
            else OutlinedButton(onClick = onSelect, modifier = Modifier.fillMaxWidth().testTag("pick-${quote.provider.name}"), shape = RoundedCornerShape(13.dp)) { Text("اختيار ${quote.provider.title}") }
        }
    }
}
@Composable private fun QuoteDetails(q: DemoQuote) { BillRow("الأصناف", q.items); BillRow("التوصيل", q.delivery); BillRow("الخدمة", q.service); BillRow("رسوم الطلب الصغير", q.smallOrder); BillRow("الخصم التوضيحي", -q.discount); Text("الضريبة مشمولة في مبالغ المثال، ولا تُضاف ثانية.", fontSize = 11.sp, color = P.Muted, lineHeight = 19.sp); BillRow("تكلفة الطلب", q.orderCost, true) }
