package app.octopus.local

import app.octopus.local.data.*
import app.octopus.local.domain.*
import app.octopus.local.providers.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CatalogLibraryTest {
    private val hs=ProviderId.HUNGERSTATION
    private val keeta=ProviderId.KEETA
    private fun restaurant(provider:ProviderId=hs,name:String="مطعم")=LiveRestaurant(name,"فطائر",name,provider)
    private fun item(id:String="feta",name:String="فطيرة فيتا",price:Long?=700)=LiveMenuItem(name,price,id)
    private fun menu(time:Long,items:List<LiveMenuItem> = listOf(item()),provider:ProviderId=hs) =
        LiveMenu(restaurant(provider),items,time,false)
    private fun product(time:Long=1000,provider:ProviderId=hs,version:String="8.0.303") = ConfiguredProduct("مطعم",
        MenuProduct("feta","فطيرة فيتا",null,true,700,listOf(
            MenuChoiceGroup("extras","إضافات",0,1,listOf(MenuChoice("chips","شيبس",true)))
        )),CatalogProvenance(provider,version,time,"observed-product-page"),true)

    @Test fun emptyLibraryContainsNoSampleRestaurantsOrPrices() {
        val library=CatalogLibrary()
        assertTrue(library.restaurants.isEmpty()); assertTrue(library.menus.isEmpty()); assertTrue(library.products.isEmpty())
        assertNull(library.menu(hs,"مدور"))
    }

    @Test fun partialMenuMergePreservesEachSourceTimeAndNeverClaimsFullMenu() {
        val original=CatalogLibrary().mergeMenu(menu(1000),"8.0.303")
        val merged=original.mergeMenu(menu(2000,listOf(item("tea","شاي",300))).copy(reachedEnd=true),"8.0.303")
        val cached=merged.menus.single()
        assertEquals(2,cached.menu.items.size)
        assertEquals(mapOf("feta" to 1000L,"tea" to 2000L),cached.itemCapturedAtMillis)
        assertEquals(1000L,cached.menu.capturedAtMillis)
        assertEquals(2000L,cached.lastObservedAtMillis)
        assertFalse(cached.menu.reachedEnd)
        assertEquals(1,original.menu(hs,"مطعم")!!.items.size)
    }

    @Test fun sameVersionRoundTripKeepsOptionsFingerprintsAndPricesStale() {
        val library=CatalogLibrary().mergeMenu(menu(1000),"8.0.303").mergeProduct(product())
            .mergeMenu(menu(2000,listOf(item("tea","شاي",null))),"8.0.303")
        val decoded=checkNotNull(CatalogLibraryCodec.decode(CatalogLibraryCodec.encode(library)))
        assertEquals(library,decoded)
        val details=checkNotNull(decoded.configuredProduct(hs,"مطعم","feta"))
        assertEquals(1000L,details.source.capturedAtMillis)
        assertEquals("observed-product-page",details.source.screenFingerprint)
        assertFalse(details.source.fresh(400_000,300_000))
        assertEquals("شيبس",details.product.groups.single().choices.single().title)
        assertNull(decoded.menu(hs,"مطعم")!!.items.single { it.selector=="tea" }.listedPrice)
        // Staleness is retained. Selecting an intended order from cache must use a separate API.
        assertEquals(SelectionResult.Rejected(SelectionError.STALE_MENU),
            MenuSelectionValidator().selectConfigured(details,ProductSelection("feta",emptyMap(),3),400_000))
    }

    @Test fun providerIdentitiesAreIndependentEvenWithSameRestaurantAndItemNames() {
        val library=CatalogLibrary().mergeMenu(menu(1000),"8.0.303").mergeProduct(product())
            .mergeMenu(menu(2000,listOf(item(price=800)),keeta),"3.13.203")
            .mergeProduct(product(2000,keeta,"3.13.203").copy(product=product().product.copy(listedPriceHalala=800)))
        assertEquals(2,library.restaurants.size)
        assertEquals(700L,library.menu(hs,"مطعم")!!.items.single().listedPrice)
        assertEquals(800L,library.menu(keeta,"مطعم")!!.items.single().listedPrice)
        assertEquals("8.0.303",library.configuredProduct(hs,"مطعم","feta")!!.source.providerVersion)
        assertEquals("3.13.203",library.configuredProduct(keeta,"مطعم","feta")!!.source.providerVersion)
        assertEquals(library,CatalogLibraryCodec.decode(CatalogLibraryCodec.encode(library)))
    }

    @Test fun aNewVersionDoesNotInheritOldRowsOrProductConfigurations() {
        val library=CatalogLibrary().mergeMenu(menu(1000),"8.0.303").mergeProduct(product())
            .mergeMenu(menu(2000,listOf(item("tea","شاي",300))),"8.1.0")
        assertEquals(listOf("tea"),library.menu(hs,"مطعم")!!.items.map { it.selector })
        assertNull(library.configuredProduct(hs,"مطعم","feta"))
        assertEquals("8.1.0",library.menus.single().providerVersion)
        assertEquals(library,library.mergeMenu(menu(1000),"8.0.303"))
    }

    @Test fun ambiguousSameNameRestaurantsRemoveOldSelectableEntries() {
        val original=CatalogLibrary().mergeMenu(menu(1000),"8.0.303").mergeProduct(product())
        val ambiguous=original.mergeRestaurants(listOf(restaurant(),restaurant().copy(description="فرع آخر")),"8.0.303",2000)
        assertTrue(ambiguous.restaurants.isEmpty())
        assertNull(ambiguous.menu(hs,"مطعم"))
        assertNull(ambiguous.configuredProduct(hs,"مطعم","feta"))
        val twoProviders=CatalogLibrary().mergeRestaurants(listOf(restaurant(),restaurant(keeta)),"observed-version",2000)
        assertEquals(2,twoProviders.restaurants.size)
    }

    @Test fun duplicateItemNamesAndIdsAreExcludedInsteadOfPickingFirst() {
        val original=CatalogLibrary().mergeMenu(menu(1000),"8.0.303").mergeProduct(product())
        val ambiguous=original.mergeMenu(menu(2000,listOf(item(),item("feta2"))),"8.0.303")
        assertTrue(ambiguous.menu(hs,"مطعم")!!.items.isEmpty())
        assertNull(ambiguous.configuredProduct(hs,"مطعم","feta"))
        val duplicateId=original.mergeMenu(menu(2000,listOf(item(),item(name="فطيرة أخرى"))),"8.0.303")
        assertTrue(duplicateId.menu(hs,"مطعم")!!.items.isEmpty())
    }

    @Test fun delayedObservationDoesNotOverwriteNewerPriceButCanAddAnotherObservedItem() {
        val original=CatalogLibrary().mergeMenu(menu(2000,listOf(item(price=800))),"8.0.303")
        val merged=original.mergeMenu(menu(1000,listOf(item(price=700),item("tea","شاي",300))),"8.0.303")
        val items=merged.menu(hs,"مطعم")!!.items
        assertEquals(800L,items.single { it.selector=="feta" }.listedPrice)
        assertEquals(300L,items.single { it.selector=="tea" }.listedPrice)
        assertEquals(mapOf("tea" to 1000L,"feta" to 2000L),merged.menus.single().itemCapturedAtMillis)
        assertNull(merged.mergeProduct(product(1000)).configuredProduct(hs,"مطعم","feta"))
    }

    @Test fun changedItemPriceInvalidatesTheOldConfiguredProduct() {
        val library=CatalogLibrary().mergeMenu(menu(1000),"8.0.303").mergeProduct(product())
            .mergeMenu(menu(2000,listOf(item(price=800))),"8.0.303")
        assertNull(library.configuredProduct(hs,"مطعم","feta"))
    }

    @Test fun newerProductOptionsDoNotRenewTheInheritedMenuPriceObservation() {
        val original=CatalogLibrary().mergeMenu(menu(1000),"8.0.303")
        val merged=original.mergeProduct(product(400_000))
        assertEquals(original.menus,merged.menus)
        assertEquals(1000L,merged.menus.single().itemCapturedAtMillis.getValue("feta"))
        assertEquals(1000L,merged.menu(hs,"مطعم")!!.capturedAtMillis)
        assertEquals(400_000L,merged.configuredProduct(hs,"مطعم","feta")!!.source.capturedAtMillis)
        assertEquals(merged,CatalogLibraryCodec.decode(CatalogLibraryCodec.encode(merged)))
    }

    @Test fun productOptionsWithoutAMenuDoNotInventAMenuPriceOrRow() {
        val library=CatalogLibrary().mergeProduct(product())
        assertNull(library.menu(hs,"مطعم"))
        assertEquals(product(),library.configuredProduct(hs,"مطعم","feta"))
        assertEquals(1,library.restaurants.size)
    }

    @Test fun productOptionsCannotChangeAnExistingMenuVersionOrReplaceItsPrice() {
        val original=CatalogLibrary().mergeMenu(menu(1000),"8.0.303")
        assertEquals(original,original.mergeProduct(product(2000,version="8.1.0")))
        assertEquals(original,original.mergeProduct(product(2000).copy(product=product().product.copy(listedPriceHalala=800))))
    }

    @Test fun newlyObservedRestaurantVersionInvalidatesOldMenuAndOptionsImmediately() {
        val original=CatalogLibrary().mergeMenu(menu(1000),"8.0.303").mergeProduct(product())
        val newer=original.mergeRestaurants(listOf(restaurant()),"8.1.0",2000)
        assertNull(newer.menu(hs,"مطعم"))
        assertNull(newer.configuredProduct(hs,"مطعم","feta"))
        assertEquals("8.1.0",newer.restaurants.single().providerVersion)
        assertEquals(newer,newer.mergeMenu(menu(1000),"8.0.303"))
        assertEquals(newer,newer.mergeProduct(product(1000)))
        assertEquals(newer,newer.mergeRestaurants(listOf(restaurant()),"8.0.303",1000))
    }

    @Test fun malformedOversizedUnknownVersionOrTrailingPayloadsAreRejected() {
        val bytes=CatalogLibraryCodec.encode(CatalogLibrary().mergeProduct(product()))
        assertNull(CatalogLibraryCodec.decode(bytes.copyOf(bytes.size-1)))
        assertNull(CatalogLibraryCodec.decode(bytes+byteArrayOf(1)))
        assertNull(CatalogLibraryCodec.decode(bytes.copyOf().apply { this[3]=99 }))
        assertNull(CatalogLibraryCodec.decode(ByteArray(CatalogLibraryCodec.MAX_BYTES+1)))
        assertNull(CatalogLibraryCodec.decode(byteArrayOf(0,0,0,1,127,127,127,127)))
        assertThrows(IllegalArgumentException::class.java) {
            CatalogLibraryCodec.encode(CatalogLibrary(products=listOf(product().copy(
                source=product().source.copy(screenFingerprint="x".repeat(513))))))
        }
    }

    @Test fun repositoryPersistsAndClearsWithoutRenewingObservationTimes()=runBlocking {
        class FakeDao:CatalogLibraryDao {
            var record:CatalogLibraryRecord?=null
            override suspend fun get()=record
            override suspend fun put(record:CatalogLibraryRecord) { this.record=record.copy(payload=record.payload.copyOf()) }
            override suspend fun clear() { record=null }
        }
        val dao=FakeDao(); val repo=CatalogLibraryRepository(dao)
        val initial=CatalogLibrary().mergeProduct(product())
        repo.save(initial)
        assertEquals(initial,CatalogLibraryRepository(dao).load())
        val result=repo.update { it.mergeMenu(menu(2000,listOf(item("tea","شاي",300))),"8.0.303") }
        assertEquals(1000L,result.configuredProduct(hs,"مطعم","feta")!!.source.capturedAtMillis)
        assertEquals(result,repo.load())
        repo.clear(); assertEquals(CatalogLibrary(),repo.load())
        dao.record=CatalogLibraryRecord(payload=byteArrayOf(1,2,3))
        assertEquals(CatalogLibrary(),repo.load())
    }
}
