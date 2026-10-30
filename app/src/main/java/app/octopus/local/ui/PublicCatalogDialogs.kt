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
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun CatalogAreaDialog(graph: AppGraph, onDismiss: () -> Unit, locationLookup: (suspend () -> CurrentAreaHint)? = null) {
    val publicState by graph.publicCatalog.state.collectAsStateWithLifecycle()
    val current = publicState.area
    var city by remember { mutableStateOf(current?.city.orEmpty()) }
    var district by remember { mutableStateOf(current?.district.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val locator = remember(context) { CurrentAreaLocator(context) }
    var cities by remember { mutableStateOf<List<AreaEntry>>(emptyList()) }
    var districts by remember { mutableStateOf<List<AreaEntry>>(emptyList()) }
    var districtsForCity by remember { mutableStateOf<String?>(null) }
    var cityLoadError by remember { mutableStateOf<String?>(null) }
    var districtLoadError by remember { mutableStateOf<String?>(null) }
    val selectedCity = AreaNameSearch.exact(cities, city)
    val currentDistricts = if (districtsForCity == selectedCity?.slug) districts else emptyList()
    val resolution = AreaPickerResolution.resolve(cities, currentDistricts, city, district)
    val selectedDistrict = resolution.district
    var loadingCities by remember { mutableStateOf(false) }
    var loadingDistricts by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val loading = loadingCities || loadingDistricts
    var locating by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<CurrentAreaHint?>(null) }
    var locateJob by remember { mutableStateOf<Job?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    fun locate() {
        locateJob?.cancel()
        locateJob = scope.launch {
            locating = true; error = null
            try {
                val found = locationLookup?.invoke() ?: locator.locate()
                hint = found
                city = found.city; district = found.district.orEmpty()
                // Retry the directory even when GPS resolves to the same city after a failed load.
                districtsForCity = null
                reload++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "تعذر تحديد الموقع؛ اختر المنطقة من الاقتراحات." }
            finally { locating = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) locate()
        else error = "لم تسمح بالموقع؛ يمكنك اختيار المدينة والحي من الاقتراحات."
    }
    LaunchedEffect(reload) {
        loadingCities = true; cityLoadError = null
        try {
            cities = withContext(Dispatchers.IO) { graph.areaDirectory.cities() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            publicState.area?.takeIf { publicState.directory.isNotEmpty() }?.let { known ->
                cities = listOf(AreaEntry(known.citySlug))
            }
            cityLoadError = "تعذر تحديث أسماء المدن من المصدر؛ يمكنك إعادة التحميل أو إبقاء المنطقة المحفوظة."
        }
        finally { loadingCities = false }
    }
    LaunchedEffect(selectedCity?.slug, reload) {
        val chosen = selectedCity
        districts = emptyList(); districtsForCity = null; districtLoadError = null
        if (chosen != null) {
            loadingDistricts = true
            try {
                districts = withContext(Dispatchers.IO) { graph.areaDirectory.districts(chosen) }
                districtsForCity = chosen.slug
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                publicState.area?.takeIf { it.citySlug == chosen.slug && publicState.directory.isNotEmpty() }?.let { known ->
                    districts = listOf(AreaEntry(known.districtSlug))
                    districtsForCity = chosen.slug
                }
                districtLoadError = "تعذر تحديث أسماء الأحياء من المصدر؛ أعد التحميل أو أبقِ الحي المحفوظ."
            }
            finally { loadingDistricts = false }
        }
    }
    DisposableEffect(Unit) { onDispose { locateJob?.cancel() } }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) locateJob?.cancel() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("منطقة قائمة المطاعم") },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("حدد موقعك أو ابحث واختر من أسماء المدن والأحياء. الاقتراحات تساعدك حتى لو أخطأت في الكتابة.")
            OutlinedButton(enabled = !saving && !loading && !locating, onClick = {
                if (locationLookup != null || locator.permitted()) locate()
                else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }, modifier = Modifier.fillMaxWidth()) { Text(if (locating) "جارٍ تحديد موقعك…" else "استخدم موقعي الحالي") }
            if (loading || locating) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedTextField(city,{
                val next = it.take(100)
                if (AreaNameSearch.exact(cities, next)?.slug != selectedCity?.slug) {
                    district = ""; districtsForCity = null
                }
                city = next; hint = null; error = null
            }, label={Text("ابحث عن المدينة")},singleLine=true,enabled=!saving && !locating)
            if (selectedCity == null) AreaNameSearch.rank(cities, city).forEach { candidate ->
                TextButton(onClick = {
                    city = candidate.title; district = ""; error = null
                }, enabled = !saving && !locating) { Text(candidate.title) }
            }
            if (selectedCity != null) {
                OutlinedTextField(district, { district = it.take(100); hint = null; error = null },
                    label={Text("ابحث عن الحي")},singleLine=true,enabled=!saving && !loading && !locating)
                if (selectedDistrict == null) AreaNameSearch.rank(currentDistricts, district).forEach { candidate ->
                    TextButton(onClick = { district = candidate.title; error = null },
                        enabled = !saving && !locating) { Text(candidate.title) }
                }
            }
            if (!loading && !locating && selectedCity != null && selectedDistrict == null && currentDistricts.isNotEmpty() && district.isNotBlank()) {
                TextButton(onClick = { district = ""; error = null }, enabled = !saving) { Text("عرض الأحياء المتاحة") }
            }
            hint?.let { Text("راجع المدينة والحي المقترحين قبل الحفظ. تحديد الموقع لا يغيّر عنوان التوصيل في التطبيقين.",style=MaterialTheme.typography.bodySmall) }
            Text("تُحفظ المنطقة لتحديث المطاعم؛ لا تغيّر عنوان التوصيل في التطبيقين. راجع تطابق العنوان والفرع قبل اعتماد المقارنة.",style=MaterialTheme.typography.bodySmall)
            val problem = error ?: cityLoadError ?: districtLoadError ?: resolution.message
            if (!loading && !locating) problem?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            if (problem != null) TextButton(enabled = !loading && !locating && !saving,
                onClick = { error = null; reload++ }) { Text("إعادة تحميل أسماء المناطق") }
        } }, confirmButton = { TextButton(enabled=!saving && !loading && !locating && resolution.area != null,onClick={
            val area = resolution.area
            if (area == null) error="أدخل اسم مدينة وحي صحيحين، دون روابط أو علامات مسار."
            else {
                saving=true
                scope.launch {
                    try {
                        graph.changeCatalogArea(area)
                        onDismiss()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error=failure.message ?: "تعذر حفظ المنطقة." }
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
