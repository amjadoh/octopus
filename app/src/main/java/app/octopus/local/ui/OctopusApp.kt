package app.octopus.local.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.octopus.local.AppGraph
import app.octopus.local.BuildConfig
import app.octopus.local.R
import app.octopus.local.automation.ReadOnlyServiceBridge
import app.octopus.local.automation.LiveCatalogBridge
import app.octopus.local.data.*
import app.octopus.local.diagnostics.DiagnosticExporter
import app.octopus.local.domain.*
import app.octopus.local.providers.ProviderPolicies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Green = OctopusPalette.Petrol
private val Ink = OctopusPalette.Ink
private val Muted = OctopusPalette.Muted
private val Background = OctopusPalette.Background
private val Line = OctopusPalette.Line
private val PaleGreen = OctopusPalette.Soft
private val Amber = OctopusPalette.Amber
private val PaleAmber = OctopusPalette.SoftAmber
private val Red = OctopusPalette.Error
private val Scheme = lightColorScheme(primary = OctopusPalette.Coral, onPrimary = Ink, secondary = Green,
    background = Background, surface = Color.White, onSurface = Ink, onBackground = Ink,
    surfaceVariant = PaleGreen, outline = Line, outlineVariant = Line,
    primaryContainer = PaleGreen, onPrimaryContainer = Ink, secondaryContainer = PaleGreen, onSecondaryContainer = Green,
    surfaceContainerLow = Color.White, surfaceContainer = Color.White, surfaceContainerHigh = PaleGreen,
    surfaceContainerHighest = Line)

private enum class Page(val title: String, val icon: ImageVector) {
    HOME("المطاعم", Icons.Outlined.Home), COMPARE("المقارنة", Icons.Outlined.CompareArrows), PROVIDERS("التطبيقات", Icons.Outlined.Apps),
    READ("القراءة", Icons.Outlined.DocumentScanner), HISTORY("السجل", Icons.Outlined.History),
    SETTINGS("الإعدادات", Icons.Outlined.Settings), INSPECT("فحص الشاشة", Icons.Outlined.ManageSearch)
}

@Composable
fun OctopusTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = Scheme, typography = mosabTypography(), content = content)
    }
}

@Composable
fun OctopusApp(graph: AppGraph, openAccessibilitySettings: () -> Unit, openUpdatePage: () -> Unit = {}) {
    val settings by graph.settings.collectAsStateWithLifecycle()
    val installations by graph.installations.collectAsStateWithLifecycle()
    val history by graph.history.collectAsStateWithLifecycle()
    val session by graph.session.collectAsStateWithLifecycle()
    val connected by graph.serviceConnected.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    var selected by rememberSaveable { mutableStateOf(ProviderId.HUNGERSTATION) }
    var disclosure by remember { mutableStateOf(false) }
    var exportConfirmation by remember { mutableStateOf(false) }
    var deleteConfirmation by remember { mutableStateOf(false) }
    var updateDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) graph.refreshInstallations() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) graph.scope.launch {
            val result = runCatching {
                val json = DiagnosticExporter.json(graph.recorder.snapshot(), graph.inspectionDao.all())
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri) ?: error("No output stream")
                    output.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
                }
            }
            errorMessage = if (result.isSuccess) "تم حفظ السجل في المكان الذي اخترته." else "تعذر حفظ السجل. لم يتم تصديره."
        }
    }
    OctopusTheme {
        Scaffold(containerColor = Background, contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                if (page != Page.HOME) {
                Column(Modifier.background(Ink).statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.ic_octopus_selected), "شعار الأخطبوط", Modifier.size(36.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("الأخطبوط", fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                            Text("طلبك واحد. خياراتك أوضح.", fontSize = 11.sp, color = Color(0xFFB8CDD9))
                        }
                        IconButton(onClick = { page = Page.SETTINGS }) { Icon(Icons.Outlined.Settings, "الإعدادات", tint = Color.White) }
                    }
                    HorizontalDivider(color = OctopusPalette.SeaMid)
                }
                }
            },
            bottomBar = {
                NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                    listOf(Page.HOME, Page.COMPARE, Page.PROVIDERS, Page.SETTINGS).forEach { item ->
                        NavigationBarItem(selected = page == item, onClick = { page = item },
                            icon = { Icon(item.icon, if (item == Page.SETTINGS) "الإعدادات" else null, Modifier.size(23.dp)) },
                            label = { Text(item.title, fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = OctopusPalette.CoralSoft,
                                selectedIconColor = Ink, selectedTextColor = Ink, unselectedIconColor = Muted, unselectedTextColor = Muted))
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (page) {
                    Page.HOME, Page.COMPARE -> CatalogScreen(graph, comparisonOnly = page == Page.COMPARE,
                        onRestaurants = { page = Page.HOME }, onOpenComparison = { page = Page.COMPARE },
                        onProviders = { page = Page.PROVIDERS },
                        onSetup = { if (!settings.consent) disclosure = true else openAccessibilitySettings() })
                    Page.PROVIDERS -> ProvidersScreen(installations, settings, onAllowed = { provider, allowed ->
                        ReadOnlyServiceBridge.stop()
                        LiveCatalogBridge.stop()
                        graph.scope.launch { graph.settingsStore.setAllowed(provider, allowed) }
                    })
                    Page.READ -> ReadScreen(selected, { selected = it }, installations, settings, connected, session, history,
                        onInspect = { page = Page.INSPECT },
                        onSetup = { if (!settings.consent) disclosure = true else openAccessibilitySettings() },
                        onStart = {
                            if (!settings.consent) disclosure = true
                            else if (!connected) openAccessibilitySettings()
                            else if (!ReadOnlyServiceBridge.start(selected)) errorMessage = "لم تبدأ القراءة. تحقق من السماح للمنصة وتثبيتها واتصال الخدمة، ثم أعد المحاولة."
                        })
                    Page.INSPECT -> InspectionScreen(graph, onBack = { page = Page.READ },
                        onSetup = { if (!settings.consent) disclosure = true else openAccessibilitySettings() })
                    Page.HISTORY -> HistoryScreen(history, onExport = { exportConfirmation = true })
                    Page.SETTINGS -> SettingsScreen(settings, connected, onSetup = {
                        if (!settings.consent) disclosure = true else openAccessibilitySettings()
                    }, onExport = { exportConfirmation = true }, onDelete = { deleteConfirmation = true }, onUpdate = { updateDialog = true },
                        onDiagnostics = { page = Page.READ }, onHistory = { page = Page.HISTORY })
                }
            }
        }
        if (disclosure) AlertDialog(onDismissRequest = { disclosure = false },
            icon = { Icon(Icons.Outlined.VerifiedUser, null, tint = Green) },
            title = { Text("السماح بقراءة الشاشة") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("تستخدم النسخة التشخيصية خدمة الإتاحة لقراءة مؤشرات السعر الظاهرة في كيتا وهنقرستيشن أثناء اختبار تبدأه أنت فقط. قيد كيتا موثق في صفحة التطبيقات؛ موافقتك لا تعني موافقة المنصة.")
                Text("تتنقل الخدمة لقراءة المطاعم والوجبات والعروض في اختبار تبدأه أنت. يمكن تجهيز الوجبة المختارة ذات البنية المدعومة بعد اختيارك الصريح؛ لا تُنشئ طلبًا نهائيًا ولا تنفذ دفعًا.")
                Text("تُحفظ محليًا مبالغ مرشحة وحالات القراءة وإصدارات التطبيقات، ومعرّفات العناصر وتسميات وظيفية محددة أثناء الجلسة لتفسير فشل القراءة تلقائيًا. لا تُحفظ كلمات المرور أو رموز التحقق أو بيانات البطاقات أو نصوص الشاشة الخام أو صورها، ولا تُرسل البيانات إلى الإنترنت.")
                Text("يمكنك الإيقاف فورًا من الزر الظاهر أثناء الاختبار، أو تعطيل الخدمة من إعدادات Android. تنتهي الجلسة خلال 90 ثانية كحد أقصى.")
            } },
            confirmButton = { TextButton(onClick = {
                graph.scope.launch { graph.settingsStore.acceptConsent(); disclosure = false; openAccessibilitySettings() }
            }) { Text("أوافق، افتح إعدادات الخدمة") } },
            dismissButton = { TextButton(onClick = { disclosure = false }) { Text("ليس الآن") } })
        if (exportConfirmation) AlertDialog(onDismissRequest = { exportConfirmation = false },
            title = { Text("تصدير سجل مختصر") },
            text = { Text("يتضمن ${history.size} اختبارًا، والمبالغ المرشحة إن قُرئت، وإصدارات التطبيقات، وطراز الجهاز وإصدار Android. ويشمل فحوص العناصر التي وافقت على حفظها: معرفات العناصر وأدوارها ومواقعها وتسميات وظيفية من قائمة محددة. لا يتضمن صورًا أو نصوص الشاشة الخام أو العنوان أو بيانات الحساب. ستختار مكان الحفظ بنفسك.") },
            confirmButton = { TextButton(onClick = { exportConfirmation = false; export.launch("octopus-diagnostics.json") }) { Text("اختيار مكان الحفظ") } },
            dismissButton = { TextButton(onClick = { exportConfirmation = false }) { Text("إلغاء") } })
        if (deleteConfirmation) AlertDialog(onDismissRequest = { deleteConfirmation = false },
            title = { Text("حذف جميع بيانات الأخطبوط؟") },
            text = { Text("سيُحذف سجل الاختبارات والإعدادات والموافقة المحلية. لا يمس ذلك سلال تطبيقات التوصيل. يلزم الإعداد مجددًا عند العودة.") },
            confirmButton = { TextButton(onClick = {
                ReadOnlyServiceBridge.stop()
                LiveCatalogBridge.stop()
                graph.scope.launch { graph.recorder.clear(); graph.inspectionRecorder.clear(); graph.clearOrders(); graph.settingsStore.clear(); graph.catalog.value = app.octopus.local.providers.LiveCatalogState(); deleteConfirmation = false }
            }) { Text("حذف البيانات", color = Red) } },
            dismissButton = { TextButton(onClick = { deleteConfirmation = false }) { Text("إلغاء") } })
        if (updateDialog) PrivateUpdateDialog(onDismiss = { updateDialog = false })
        errorMessage?.let { message -> AlertDialog(onDismissRequest = { errorMessage = null },
            title = { Text("الأخطبوط") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("حسنًا") } }) }
    }
}

@Composable private fun PageIntro(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text(subtitle, fontSize = 14.sp, color = Muted, lineHeight = 22.sp)
    }
}
@Composable private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(13.dp), color = Color.White, border = BorderStroke(1.dp, Line)) {
        Column(Modifier.fillMaxWidth().padding(19.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}
@Composable private fun Pill(text: String, amber: Boolean = false) {
    Surface(color = if (amber) PaleAmber else PaleGreen, shape = RoundedCornerShape(50)) {
        Text(text, Modifier.padding(horizontal = 11.dp, vertical = 6.dp), color = if (amber) Amber else Green, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}
@Composable private fun Note(text: String, icon: ImageVector = Icons.Outlined.Info) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PaleGreen).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = Green, modifier = Modifier.size(20.dp))
        Text(text, color = Green, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
    }
}
@Composable private fun SectionTitle(text: String) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink) }
@Composable private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(15.dp)) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
@Composable private fun Step(number: String, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(29.dp).background(PaleGreen, CircleShape), contentAlignment = Alignment.Center) { Text(number, color = Green, fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(body, fontSize = 12.sp, color = Muted, lineHeight = 20.sp)
        }
    }
}
@Composable private fun ProviderMark(provider: ProviderId) {
    Box(Modifier.size(46.dp).background(PaleGreen, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        Text(if (provider == ProviderId.KEETA) "ك" else "هـ", fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Ink)
    }
}

@Composable private fun HomeScreen(settings: UserSettings, connected: Boolean, installations: List<ProviderInstallation>,
    history: List<DiagnosticReport>, session: SessionState, onRead: () -> Unit, onProviders: () -> Unit, onSetup: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(21.dp), verticalArrangement = Arrangement.spacedBy(21.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Pill("نسخة تشخيصية • قراءة فقط")
            Text("محلي على جهازك", fontSize = 11.sp, color = Muted)
        } }
        item { Surface(shape = RoundedCornerShape(13.dp), color = PaleGreen) {
            Column(Modifier.fillMaxWidth().background(PaleGreen).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Restaurant, null, tint = Green, modifier = Modifier.size(29.dp))
                Text("أول قراءة،\nمن تطبيقك الفعلي.", fontSize = 27.sp, fontWeight = FontWeight.Bold, lineHeight = 39.sp, color = Ink)
                Text("نجهّز أساس المقارنة بين كيتا وهنقرستيشن، بدءًا من قراءة السعر الحقيقي.", fontSize = 14.sp, color = Muted, lineHeight = 23.sp)
                PrimaryButton("جرّب قراءة السلة", onClick = onRead)
            }
        } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("تطبيقات التوصيل")
            TextButton(onClick = onProviders) { Text("إدارة التطبيقات", fontSize = 12.sp) }
        } }
        item { Panel {
            ProviderId.entries.forEachIndexed { index, provider ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProviderMark(provider)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(provider.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (!ProviderPolicies.forProvider(provider).permitsDiagnosticRead) "متوقفة بسبب قيد موثق" else installations.firstOrNull { it.provider == provider }?.let { if (it.installed) "مثبت • القراءة لم تُختبر بعد" else "غير مثبت على هذا الجهاز" } ?: "جارٍ فحص التثبيت", fontSize = 11.sp, color = Muted)
                    }
                    Icon(Icons.Outlined.Circle, null, tint = if (provider in settings.allowed && ProviderPolicies.forProvider(provider).permitsDiagnosticRead) Green else Muted, modifier = Modifier.size(13.dp))
                }
                if (index == 0) HorizontalDivider(color = Line)
            }
        } }
        item { Panel {
            SectionTitle("خطوتك التالية")
            Step("١", "فعّل القراءة بإذنك", "راجع الإفصاح وفعّل خدمة الأخطبوط من إعدادات Android.")
            Step("٢", "افتح مراجعة طلب حقيقي", "جهّز سلة بنفسك في هذا الاختبار التشخيصي فقط.")
            Step("٣", "ابدأ اختبارًا قصيرًا", "نحاول قراءة الإجمالي دون أي نقرة أو تعديل على طلبك.")
            OutlinedButton(onClick = onSetup, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Text(if (connected) "مراجعة إعدادات الخدمة" else "إعداد خدمة القراءة")
            }
        } }
        item { Note("طلب المقارنة الفعلية: المضياف العربي — دجاج مضبي. نحتاج أولًا فحص شاشات الجهاز؛ لا توجد أسعار أو نتائج وهمية في هذه النسخة.", Icons.Outlined.VerifiedUser) }
        if (history.isNotEmpty()) item { Panel { SectionTitle("آخر اختبار"); ReportContent(history.first()) } }
        if (session.active) item { ActiveSession(session) }
    }
}

@Composable private fun ProvidersScreen(installations: List<ProviderInstallation>, settings: UserSettings, onAllowed: (ProviderId, Boolean) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(21.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { PageIntro("تطبيقات التوصيل", "اختر التطبيقات التي تسمح للأخطبوط بقراءتها خلال الاختبار.") }
        items(ProviderId.entries) { provider ->
            val installed = installations.firstOrNull { it.provider == provider }
            val policy = ProviderPolicies.forProvider(provider)
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProviderMark(provider)
                    Column(Modifier.weight(1f)) { Text(provider.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Text(if (installed?.installed == true) "الإصدار ${installed.version ?: "غير متاح"}" else "غير مثبت على هذا الجهاز", fontSize = 12.sp, color = Muted) }
                    Switch(checked = provider in settings.allowed && policy.permitsDiagnosticRead, enabled = policy.permitsDiagnosticRead,
                        onCheckedChange = { onAllowed(provider, it) })
                }
                HorizontalDivider(color = Line)
                InfoRow("الحالة", if (!policy.permitsDiagnosticRead) "متوقفة بسبب قيد موثق" else if (installed?.installed == true) "متاح للاختبار" else "غير مثبت")
                InfoRow("القدرة الحالية", if (policy.permitsDiagnosticRead) "قراءة تشخيصية فقط" else "القراءة والأتمتة متوقفتان")
                Text(policy.reason, fontSize = 12.sp, color = Muted)
                Text("مصدر المراجعة: ${policy.source} • ${policy.reviewedOn}", fontSize = 11.sp, color = Muted)
                InfoRow("التحقق على جهازك", "لم يتم بعد")
                InfoRow("بناء السلة تلقائيًا", "غير منفذ في هذه النسخة")
            }
        }
        item { Note("يُستخدم الحساب النشط في كل تطبيق. لا حاجة لتوحيد الحسابات أو إدخال كلمة مرور هنا.", Icons.Outlined.Lock) }
    }
}

@Composable private fun ReadScreen(selected: ProviderId, onSelected: (ProviderId) -> Unit,
    installations: List<ProviderInstallation>, settings: UserSettings, connected: Boolean, session: SessionState,
    history: List<DiagnosticReport>, onInspect: () -> Unit, onSetup: () -> Unit, onStart: () -> Unit) {
    val installation = installations.firstOrNull { it.provider == selected }
    val policy = ProviderPolicies.forProvider(selected)
    LazyColumn(contentPadding = PaddingValues(21.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { PageIntro("اختبار القراءة", "نتحقق مما يظهر للتطبيق فعليًا. سلتك تبقى كما هي.") }
        item { Panel {
            SectionTitle("لم يقرأ الإجمالي؟")
            Text("استخدم فحص العناصر لنحدد ما يتيحه التطبيق فعليًا. لا نكرر محاولة القراءة نفسها دون دليل جديد.", fontSize = 13.sp)
            OutlinedButton(onClick = onInspect, modifier = Modifier.fillMaxWidth()) { Text("فحص شاشة مراجعة الطلب") }
        } }
        if (!policy.permitsDiagnosticRead) item { Note(policy.reason) }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProviderId.entries.forEach { provider ->
                FilterChip(selected = provider == selected, onClick = { onSelected(provider) }, enabled = !session.active,
                    label = { Text(provider.title) }, leadingIcon = { Icon(if (selected == provider) Icons.Outlined.CheckCircle else Icons.Outlined.Circle, null, Modifier.size(17.dp)) },
                    modifier = Modifier.weight(1f))
            }
        } }
        item { Panel {
            SectionTitle("قبل أن تبدأ")
            CheckLine("${selected.title} مثبت على الجهاز", installation?.installed == true)
            CheckLine("السماح بقراءة ${selected.title}", selected in settings.allowed)
            CheckLine("اختبار القراءة المحلي متاح", policy.permitsDiagnosticRead)
            CheckLine("الموافقة على الإفصاح", settings.consent)
            CheckLine("خدمة القراءة متصلة", connected)
            if (!connected || !settings.consent) OutlinedButton(onClick = onSetup, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("إكمال الإعداد") }
        } }
        if (policy.permitsDiagnosticRead) item { Panel {
            SectionTitle("كيف تختبر؟")
            Step("١", "جهّز سلة حقيقية", "اختر وجباتك وعنوانك داخل ${selected.title} يدويًا، ثم افتح مراجعة الطلب دون تنفيذه.")
            Step("٢", "عد إلى الأخطبوط وابدأ", "نفتح التطبيق مجددًا وننتظر ظهور الإجمالي. انتقل يدويًا للمراجعة إذا لزم.")
            Step("٣", "راجع نتيجة القراءة", "تُحفظ قراءة مرشحة بعد استقرارها؛ ليست مقارنة مؤكدة بعد.")
            if (session.active) ActiveSession(session)
            else PrimaryButton("ابدأ القراءة وافتح ${selected.title}", enabled = installation?.installed == true && selected in settings.allowed, onClick = onStart)
            Text("جلسة واحدة • حتى ٩٠ ثانية • إيقاف فوري", fontSize = 11.sp, color = Muted)
        } }
        item { Note("هذه النسخة لا تتحقق بعد من تطابق الأصناف والعنوان والفرع؛ أي إجمالي مقروء يظل مرشحًا غير مؤكد.") }
        history.firstOrNull { it.provider == selected.name }?.let { report -> item { Panel { SectionTitle("آخر نتيجة لهذه المنصة"); ReportContent(report) } } }
    }
}

@Composable private fun ActiveSession(session: SessionState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(session.message, fontSize = 13.sp, color = Green, lineHeight = 21.sp)
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Green, trackColor = PaleGreen)
        Text("متبقي ${session.remainingSeconds} ثانية", fontSize = 12.sp, color = Muted)
        OutlinedButton(onClick = { ReadOnlyServiceBridge.stop() }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("إيقاف القراءة", color = Red) }
    }
}

@Composable private fun HistoryScreen(history: List<DiagnosticReport>, onExport: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(21.dp), verticalArrangement = Arrangement.spacedBy(19.dp)) {
        item { PageIntro("سجل الاختبارات", "نتائج محلية واضحة، بما فيها الاختبارات المتوقفة والفاشلة.") }
        if (history.isEmpty()) item { Panel {
            Icon(Icons.Outlined.ReceiptLong, null, tint = Green, modifier = Modifier.size(36.dp))
            SectionTitle("لا توجد قراءات بعد")
            Text("بعد أول اختبار على جوالك، ستظهر المنصة ووقت القراءة وحالتها هنا. لن نملأ السجل بنتائج تجريبية.", fontSize = 14.sp, lineHeight = 23.sp, color = Muted)
        } }
        items(history, key = { it.id }) { report -> Panel { ReportContent(report) } }
        item { OutlinedButton(onClick = onExport, enabled = history.isNotEmpty(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Outlined.FileDownload, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("تصدير سجل مختصر")
        } }
    }
}

@Composable private fun ReportContent(report: DiagnosticReport) {
    val provider = ProviderId.entries.firstOrNull { it.name == report.provider }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(provider?.title ?: report.provider, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Pill(if (report.outcome == "STABLE_CANDIDATE") "قراءة مرشحة" else "اختبار متوقف", amber = true)
    }
    Text(report.candidateTotalHalala?.let { BigDecimal.valueOf(it, 2).toPlainString() + " ر.س" } ?: "لم يُقرأ إجمالي", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
    Text(outcomeArabic(report.outcome), fontSize = 12.sp, color = Muted, lineHeight = 21.sp)
    Text(SimpleDateFormat("dd/MM/yyyy • HH:mm", Locale("ar")).format(Date(report.capturedAt)), color = Muted, fontSize = 11.sp)
    Text("السلة والعنوان والسعر النهائي: غير متحقق منها", fontSize = 11.sp, color = Amber)
}

@Composable private fun SettingsScreen(settings: UserSettings, connected: Boolean, onSetup: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit, onUpdate: () -> Unit, onDiagnostics: () -> Unit, onHistory: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(21.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { PageIntro("الإعدادات والخصوصية", "التحكم لك. بيانات الاختبار تبقى على الجهاز.") }
        item { Panel {
            SectionTitle("تحديث التطبيق")
            InfoRow("الإصدار الحالي", BuildConfig.VERSION_NAME)
            Text("يتحقق الأخطبوط من التحديث وينزّله ويفحصه تلقائيًا، ثم يعرض تأكيد التثبيت في Android. سجّل دخول GitHub عند الحاجة للوصول إلى الإصدار الخاص.", fontSize = 13.sp, color = Muted)
            PrimaryButton("تحديث التطبيق", onClick = onUpdate)
        } }
        item { Panel {
            SectionTitle("الجهاز والخدمة")
            InfoRow("الجهاز الفعلي", "${Build.MANUFACTURER} ${Build.MODEL}")
            InfoRow("Android", "${Build.VERSION.RELEASE} • API ${Build.VERSION.SDK_INT}")
            InfoRow("الخدمة", if (connected) "متصلة" else "غير مفعّلة")
            InfoRow("الإفصاح", if (settings.consent) "تمت الموافقة" else "بانتظار موافقتك")
            OutlinedButton(onClick = onSetup, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("إعداد خدمة القراءة") }
        } }
        item { Panel {
            SectionTitle("بياناتك المحلية")
            Text("نحفظ حالات القراءة والمبالغ المرشحة وإصدارات التطبيقات وسجلًا منقحًا للعناصر أثناء الجلسة التي تبدأها، لتفسير التعطل دون فحص منفصل. لا نصوص خام أو صور شاشة أو بيانات دخول أو خادم خارجي.", fontSize = 13.sp, lineHeight = 22.sp, color = Muted)
            OutlinedButton(onClick = onExport, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("تصدير السجل بعد مراجعته") }
            TextButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, null, tint = Red); Spacer(Modifier.width(7.dp)); Text("حذف جميع بيانات الأخطبوط", color = Red) }
        } }
        item { Panel {
            SectionTitle("الفحص والسجل")
            TextButton(onClick = onDiagnostics) { Text("أدوات فحص القراءة") }
            TextButton(onClick = onHistory) { Text("سجل الاختبارات") }
        } }
        item { Note("النسخة ${BuildConfig.VERSION_NAME} • المقارنة الكاملة بالرسوم والعروض وتجهيز كيتا قيد الاستكمال. لا تأكيد طلب أو دفع.", Icons.Outlined.Science) }
    }
}
@Composable private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), color = Muted, fontSize = 12.sp)
        Text(value, Modifier.weight(1f), color = Ink, fontSize = 12.sp)
    }
}
@Composable private fun CheckLine(label: String, checked: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (checked) Icons.Outlined.CheckCircle else Icons.Outlined.Circle, null, tint = if (checked) Green else Muted, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 13.sp, color = if (checked) Ink else Muted)
    }
}
private fun outcomeArabic(code: String): String = when (code) {
    "STABLE_CANDIDATE" -> "استقرت قراءة مرشحة للإجمالي. لم يُثبت بعد أنها التكلفة النهائية الكاملة."
    "TIMEOUT" -> "انتهت المهلة دون قراءة مستقرة. قد تكون الشاشة أو عناصرها غير متاحة."
    "SENSITIVE_SCREEN" -> "توقفت القراءة عند شاشة دخول أو بيانات حساسة."
    "DEVICE_LOCKED" -> "توقفت القراءة لأن الجهاز مقفل."
    "LEFT_TARGET_APP" -> "توقفت القراءة بعد مغادرة التطبيق المستهدف."
    "USER_STOPPED" -> "أوقفت الاختبار يدويًا. لم تُعدّل أي سلة."
    "STOP_CONTROL_UNAVAILABLE" -> "تعذر إظهار زر الإيقاف، لذلك لم تبدأ القراءة."
    else -> "توقف الاختبار بأمان دون نتيجة مؤكدة."
}

@Preview(name = "Octopus diagnostic home", locale = "ar", showBackground = true, widthDp = 393, heightDp = 852)
@Composable private fun HomePreview() {
    OctopusTheme { Surface(color = Background) { HomeScreen(UserSettings(), false, ProviderId.entries.map { ProviderInstallation(it, false) }, emptyList(), SessionState(), {}, {}, {}) } }
}
