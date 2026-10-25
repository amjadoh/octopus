package app.octopus.local.catalog

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class PublicCatalogPage(val url: String, val html: String, val observedAt: Long)
interface PublicCatalogCache {
    suspend fun load(url: String): PublicCatalogPage?
    suspend fun save(page: PublicCatalogPage)
    suspend fun clear()
}

/** Has no dependency on accessibility, activities, provider adapters, carts or payments. */
class PublicCatalogController(
    private val scope: CoroutineScope,
    private val client: PublicCatalogClient,
    private val cache: PublicCatalogCache,
    private val directoryChanged: (List<PublicRestaurant>) -> Unit,
    private val menuChanged: (PublicMenu) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val parser = HungerStationPublicParser()
    private val mutable = MutableStateFlow(PublicCatalogState())
    val state: StateFlow<PublicCatalogState> = mutable.asStateFlow()
    var currentMenu: PublicMenu? = null; private set
    private var epoch = 0L
    private var selection = 0L
    private var directoryJob: Job? = null
    private var menuJob: Job? = null

    fun configure(area: CatalogArea?): Job {
        val token = ++epoch; selection++; directoryJob?.cancel(); menuJob?.cancel(); currentMenu = null
        mutable.value = PublicCatalogState(area)
        return scope.launch {
            if (area == null) return@launch
            val cached = runCatching { withContext(io) { cache.load(area.url)?.let { parser.directory(it.html,area,1,it.observedAt) } } }.getOrNull()
            if (token != epoch) return@launch
            cached?.let(::publish)
            refresh()
        }
    }

    fun refresh(force: Boolean = true, nextPage: Boolean = false): Job? {
        val before = mutable.value; val area = before.area ?: return null
        if (directoryJob?.isActive == true) return directoryJob
        if (!force && before.updatedAt?.let { clock() - it in 0..900_000 } == true) return null
        val page = if (nextPage) before.page + 1 else 1
        if (nextPage && (page > before.pages || before.directory.size >= 500)) return null
        val token = epoch
        mutable.value = before.copy(refreshing = true, error = null)
        return scope.launch {
            try {
                val url = area.url + if (page > 1) "?page=$page" else ""
                val result = withContext(io) {
                    val html = client.get(url); ensureActive()
                    val parsed = parser.directory(html, area, page, clock())
                    cache.save(PublicCatalogPage(url,html,parsed.observedAt)); parsed
                }
                if (token == epoch) publish(result, nextPage)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (token == epoch) mutable.value = mutable.value.copy(error =
                    "تعذر تحديث قائمة المطاعم من المصدر العام. بقيت آخر قائمة محفوظة؛ تحقق من الاتصال والمدينة والحي.")
            } finally {
                if (token == epoch) mutable.value = mutable.value.copy(refreshing = false)
            }
        }.also { directoryJob = it }
    }

    private fun publish(page: PublicDirectory, append: Boolean = false) {
        val rows = (if (append) mutable.value.directory + page.restaurants else page.restaurants)
            .distinctBy { it.id }.take(500)
        mutable.value = mutable.value.copy(directory = rows, updatedAt = page.observedAt,
            page = page.page, pages = page.pages)
        directoryChanged(rows)
    }

    fun openMenu(restaurant: PublicRestaurant): Boolean {
        val area = mutable.value.area ?: return false
        if (mutable.value.directory.none { it == restaurant }) return false
        val token = epoch; val picked = ++selection
        menuJob?.cancel(); currentMenu = null
        mutable.value = mutable.value.copy(loadingMenu = true, error = null)
        menuJob = scope.launch {
            var hadCache = false
            try {
                val cached = withContext(io) { runCatching { cache.load(restaurant.url)?.let {
                    parser.menu(it.html,area,restaurant,it.observedAt) } }.getOrNull() }
                if (token != epoch || picked != selection) return@launch
                cached?.let { hadCache = true; currentMenu = it; menuChanged(it) }
                val result = withContext(io) {
                    val html = client.get(restaurant.url); ensureActive()
                    val parsed = parser.menu(html,area,restaurant,clock())
                    cache.save(PublicCatalogPage(restaurant.url,html,parsed.observedAt)); parsed
                }
                if (token == epoch && picked == selection) { currentMenu = result; menuChanged(result) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (token == epoch && picked == selection) mutable.value = mutable.value.copy(error = if (hadCache)
                    "تعذر تحديث القائمة؛ المعروض آخر قراءة محفوظة، والسعر والتوفر يتحققان عند المقارنة."
                    else "تعذرت قراءة قائمة المطعم من الموقع. لم يُفتح أي تطبيق توصيل.")
            } finally {
                if (token == epoch && picked == selection) mutable.value = mutable.value.copy(loadingMenu = false)
            }
        }
        return true
    }

    fun leaveMenu() { selection++; menuJob?.cancel(); currentMenu = null; mutable.value = mutable.value.copy(loadingMenu = false) }
    fun cancel() { epoch++; selection++; directoryJob?.cancel(); menuJob?.cancel(); currentMenu = null }

    suspend fun stopAndJoin() {
        val jobs = listOfNotNull(directoryJob,menuJob)
        cancel()
        jobs.forEach { it.join() }
        mutable.value = PublicCatalogState()
    }
}
