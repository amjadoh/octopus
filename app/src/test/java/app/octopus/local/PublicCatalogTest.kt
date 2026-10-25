package app.octopus.local

import app.octopus.local.catalog.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Real unauthenticated public HTML captured on 2026-10-25; not current quotes. */
class PublicCatalogTest {
    private val parser = HungerStationPublicParser()
    private val area = CatalogArea("الرياض","النرجس")
    private fun fixture(name:String) = javaClass.getResource("/public-catalog/$name.html")!!.readText(Charsets.UTF_8)
    private fun directory() = parser.directory(fixture("district"),area,1,1000)
    private fun restaurant() = directory().restaurants.single { it.id == 64579L }
    @Test fun realDistrictReadsPublicRestaurantsAndPagination() {
        val d = directory()
        assertEquals(1,d.page); assertTrue(d.pages > 1); assertTrue(d.restaurants.size > 20)
        assertEquals("المشوي والشواية",d.restaurants.single { it.id==64579L }.name)
        assertEquals(area,d.area); assertEquals(1000,d.observedAt)
        assertTrue(d.restaurants.all { PublicCatalogUrls.menuInArea(it.url,area) })
    }
    @Test fun realMenuKeepsExactPricesAndWebIdsSeparateFromNativeSelectors() {
        val menu = parser.menu(fixture("menu"),area,restaurant(),2000)
        val meal = menu.meals.single { it.id==142650964L }
        assertEquals("نص دجاج فحم ارز بشاور",meal.name); assertEquals(3300L,meal.price)
        assertTrue(menu.live().items.all { it.selector.startsWith("public-hs:64579:") })
        assertFalse(menu.live().reachedEnd)
        assertEquals("CLOSE",menu.restaurant.status)
    }
    @Test fun redirectTargetsAreRestrictedToPublicCatalogPages() {
        assertTrue(PublicCatalogUrls.allowed(area.url)); assertTrue(PublicCatalogUrls.allowed(area.url+"?page=2"))
        listOf("https://blocked.teams.cloudflare.com/", "https://hungerstation.com.evil.test/sa-ar/restaurants/regions/a/b",
            "http://hungerstation.com/sa-ar/restaurants/regions/a/b", "https://hungerstation.com/checkout",
            area.url+"?token=secret", area.url+"#login",area.url+"/%252e%252e",area.url+"/%2Fcheckout",
            area.url.replace("https://","https://user@"),area.url.replace(".com/", ".com:443/"))
            .forEach { assertFalse(it,PublicCatalogUrls.allowed(it)) }
    }
    @Test fun wrongAreaAndWrongBranchFailInsteadOfReplacingCache() {
        assertThrows(IllegalArgumentException::class.java) { parser.directory(fixture("district"),CatalogArea("جدة","النرجس"),1,1000) }
        assertThrows(IllegalArgumentException::class.java) { parser.menu(fixture("menu"),area,restaurant().copy(id=1),2000) }
        assertThrows(IllegalArgumentException::class.java) { parser.directory(fixture("district"),area,2,1000) }
    }
    @Test fun blockedOrUnknownHtmlCannotBecomeAnEmptySuccessfulCatalog() {
        assertThrows(IllegalArgumentException::class.java) { parser.directory("<html>This website is blocked</html>",area,1,1000) }
        assertThrows(IllegalArgumentException::class.java) { parser.menu("<html>Login required</html>",area,restaurant(),1000) }
    }
    @Test fun areaHasNoImplicitDefaultOrPreciseGpsRequirement() {
        assertNull(PublicCatalogState().area)
        listOf("", "a/b", "../a", "a?lat=1").forEach { city ->
            assertThrows(IllegalArgumentException::class.java) { CatalogArea(city,"حي") }
        }
    }
    private class Cache : PublicCatalogCache {
        val rows=mutableMapOf<String,PublicCatalogPage>()
        override suspend fun load(url:String)=rows[url]
        override suspend fun save(page:PublicCatalogPage) {rows[page.url]=page}
        override suspend fun clear() {rows.clear()}
    }
    @Test fun offlineRefreshPreservesLastGoodDirectoryAndItsOriginalReadTime() = runBlocking {
        val cache=Cache();cache.save(PublicCatalogPage(area.url,fixture("district"),1000))
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        var emitted=0
        val c=PublicCatalogController(scope,PublicCatalogClient {error("offline")},cache,{emitted++},{}, {9000},Dispatchers.Unconfined)
        try {
            c.configure(area).join()
            assertEquals(directory().restaurants,c.state.value.directory)
            assertEquals(1000L,c.state.value.updatedAt);assertNotNull(c.state.value.error);assertEquals(1,emitted)
            c.refresh()!!.join();assertEquals(1000L,c.state.value.updatedAt);assertFalse(c.state.value.refreshing)
        } finally {scope.cancel()}
    }
    @Test fun refreshingDirectoryDoesNotReloadMenuOrChangeSelectedMeals() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);var menuEvents=0;var requests=0
        val c=PublicCatalogController(scope,PublicCatalogClient {url ->requests++;if(url==area.url)fixture("district") else fixture("menu")},Cache(),{},{menuEvents++},{2000},Dispatchers.Unconfined)
        try {
            c.configure(area).join();assertTrue(c.openMenu(restaurant()))
            assertEquals(1,menuEvents);val selected=c.currentMenu
            c.refresh()!!.join();assertEquals(selected,c.currentMenu);assertEquals(1,menuEvents);assertEquals(3,requests)
            assertNull(c.refresh(force=false))
        } finally {scope.cancel()}
    }
    @Test fun noAreaMeansNoNetworkOrProviderActivity() = runBlocking {
        var requests=0; val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val c=PublicCatalogController(scope,PublicCatalogClient {requests++;error("unexpected")},Cache(),{},{},io=Dispatchers.Unconfined)
        try {c.configure(null).join();assertNull(c.refresh());assertEquals(0,requests)} finally {scope.cancel()}
    }
    @Test fun missingPriceStaysUnknownAndIsNeverZero() {
        val html=fixture("menu").replace("\"price\":\"33.0\"","\"price\":null")
        assertNull(parser.menu(html,area,restaurant(),2000).meals.single {it.id==142650964L}.price)
    }
    @Test fun delayedMenuCannotReopenAfterUserReturnsToRestaurants() = runBlocking {
        val started=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        var emitted=0
        val c=PublicCatalogController(scope,PublicCatalogClient {url ->
            if(url==area.url)fixture("district") else {
                started.countDown();check(release.await(3,java.util.concurrent.TimeUnit.SECONDS));fixture("menu")
            }
        },Cache(),{},{emitted++},{2000})
        try {
            c.configure(area).join()
            withTimeout(5000) {c.state.first {it.directory.isNotEmpty()}}
            assertTrue(c.openMenu(restaurant()))
            assertTrue(withContext(Dispatchers.IO) {started.await(2,java.util.concurrent.TimeUnit.SECONDS)})
            c.leaveMenu();release.countDown();c.stopAndJoin()
            assertNull(c.currentMenu);assertEquals(0,emitted)
        } finally {release.countDown();scope.cancel()}
    }
}
