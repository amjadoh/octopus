package app.octopus.local

import app.octopus.local.data.*
import app.octopus.local.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OrderPersistenceTest {
    private fun cart() = CanonicalCart("مطعم الاختبار", "فرع ١", listOf(
        CanonicalItem("وجبة", "كبير", listOf(CartOption("إضافة", "جبن", 2)), 3, "بدون فلفل"),
        CanonicalItem("ماء", null, emptyList(), 1),
    ), "local-address-reference")
    @Test fun roundTripPreservesAllSelectionsAndFingerprint() {
        val decoded = CanonicalCartCodec.decode(CanonicalCartCodec.encode(cart()))!!
        assertEquals(cart(), decoded)
        assertEquals(cart().fingerprint, decoded.fingerprint)
    }
    @Test fun corruptTruncatedUnknownVersionAndTrailingBytesAreRejected() {
        val bytes = CanonicalCartCodec.encode(cart())
        assertNull(CanonicalCartCodec.decode(bytes.copyOf(bytes.size - 1)))
        assertNull(CanonicalCartCodec.decode(bytes + byteArrayOf(1)))
        assertNull(CanonicalCartCodec.decode(bytes.copyOf().apply { this[3] = 2 }))
        assertNull(CanonicalCartCodec.decode(ByteArray(600_000)))
    }
    @Test fun repositoryUpdateUsesSameIdAndClearRemovesStoredOrders() = runBlocking {
        val rows = mutableMapOf<String, SavedOrder>()
        val dao = object : SavedOrderDao {
            override suspend fun put(order: SavedOrder) { rows[order.id] = order }
            override suspend fun get(id: String) = rows[id]
            override suspend fun clear() { rows.clear() }
            override suspend fun delete(id: String) { rows.remove(id) }
        }
        val repo = OrderRepository(dao)
        val id = repo.save(cart())
        repo.save(cart().copy(branch = "فرع آخر"), id)
        assertEquals(1, rows.size)
        assertEquals("فرع آخر", repo.load(id)!!.branch)
        val other = repo.save(cart())
        repo.delete(id)
        assertNull(repo.load(id))
        assertNotNull(repo.load(other))
        repo.clear()
        assertNull(repo.load(id))
    }
}
