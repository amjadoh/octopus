package app.octopus.local.data

import app.octopus.local.domain.*
import app.octopus.local.providers.*
import java.io.*

/** Bounded, versioned local payload; no Java object deserialization or device/account data. */
object CatalogLibraryCodec {
    private const val VERSION = 1
    const val MAX_BYTES = 8 * 1024 * 1024
    private class LimitedBuffer : ByteArrayOutputStream() {
        override fun write(value: Int) { require(count < MAX_BYTES); super.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(length >= 0 && count.toLong() + length <= MAX_BYTES); super.write(bytes, offset, length)
        }
    }
    private fun DataOutputStream.text(value: String, max: Int = 1024) { require(value.length <= max); writeUTF(value) }
    private fun DataInputStream.text(max: Int = 1024): String = readUTF().also { require(it.length <= max) }
    private fun DataOutputStream.optional(value: String?, max: Int = 1024) {
        writeBoolean(value != null); if (value != null) text(value, max)
    }
    private fun DataInputStream.optional(max: Int = 1024): String? = if (readBoolean()) text(max) else null
    private fun DataOutputStream.money(value: Long?) { writeBoolean(value != null); if (value != null) { require(value >= 0); writeLong(value) } }
    private fun DataInputStream.money(): Long? = if (readBoolean()) readLong().also { require(it >= 0) } else null
    private fun DataInputStream.count(max: Int): Int = readInt().also { require(it in 0..max) }
    private fun DataOutputStream.restaurant(value: LiveRestaurant) {
        text(value.source.name, 100); text(value.name,512); optional(value.description,2000); text(value.searchQuery,512)
    }
    private fun DataInputStream.restaurant(): LiveRestaurant {
        val provider=ProviderId.valueOf(text(100)); return LiveRestaurant(text(512),optional(2000),text(512),provider)
    }

    fun encode(value: CatalogLibrary): ByteArray {
        val buffer=LimitedBuffer()
        DataOutputStream(buffer).use { out ->
            out.writeInt(VERSION)
            out.writeInt(value.restaurants.size)
            value.restaurants.forEach { row ->
                out.restaurant(row.restaurant); out.text(row.providerVersion,100); out.writeLong(row.capturedAtMillis)
            }
            out.writeInt(value.menus.size)
            value.menus.forEach { row ->
                out.restaurant(row.menu.restaurant); out.text(row.providerVersion,100); out.writeLong(row.lastObservedAtMillis)
                out.writeInt(row.menu.items.size)
                row.menu.items.forEach { item ->
                    out.text(item.selector); out.text(item.name,512); out.money(item.listedPrice)
                    out.writeLong(row.itemCapturedAtMillis.getValue(item.selector))
                }
            }
            out.writeInt(value.products.size)
            value.products.forEach { row ->
                out.text(row.restaurant,512); out.text(row.source.provider.name,100); out.text(row.source.providerVersion,100)
                out.writeLong(row.source.capturedAtMillis); out.text(row.source.screenFingerprint,512)
                out.writeBoolean(row.optionsComplete); out.optional(row.sizeGroupId)
                val item=row.product
                out.text(item.id); out.text(item.title,512); out.optional(item.size,300); out.writeBoolean(item.available)
                out.money(item.listedPriceHalala); require(item.groups.size <= 30); out.writeInt(item.groups.size)
                item.groups.forEach { group ->
                    out.text(group.id); out.text(group.title,300); out.writeInt(group.minimum); out.writeInt(group.maximum)
                    require(group.choices.size <= 100); out.writeInt(group.choices.size)
                    group.choices.forEach { choice -> out.text(choice.id); out.text(choice.title,300); out.writeBoolean(choice.available) }
                }
            }
        }
        return buffer.toByteArray()
    }

    fun decode(payload: ByteArray): CatalogLibrary? {
        if (payload.size > MAX_BYTES) return null
        return try {
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                require(input.readInt() == VERSION)
                val restaurants=List(input.count(CatalogLibrary.MAX_RESTAURANTS)) {
                    CachedRestaurant(input.restaurant(),input.text(100),input.readLong())
                }
                val menus=List(input.count(CatalogLibrary.MAX_MENUS)) {
                    val restaurant=input.restaurant(); val version=input.text(100); val observedAt=input.readLong()
                    val times=linkedMapOf<String,Long>()
                    val items=List(input.count(CatalogLibrary.MAX_ITEMS_PER_MENU)) {
                        val id=input.text(); val name=input.text(512); val amount=input.money(); val capturedAt=input.readLong()
                        require(id.isNotBlank() && name.isNotBlank() && id !in times)
                        times[id]=capturedAt; LiveMenuItem(name,amount,id)
                    }
                    CachedMenu(LiveMenu(restaurant,items,times.values.minOrNull() ?: observedAt,false),version,times,observedAt)
                }
                val products=List(input.count(CatalogLibrary.MAX_PRODUCTS)) {
                    val restaurant=input.text(512); val provider=ProviderId.valueOf(input.text(100)); val version=input.text(100)
                    val capturedAt=input.readLong(); val fingerprint=input.text(512)
                    val complete=input.readBoolean(); val sizeGroup=input.optional()
                    val id=input.text(); val title=input.text(512); val size=input.optional(300); val available=input.readBoolean()
                    val amount=input.money()
                    val groups=List(input.count(30)) {
                        val groupId=input.text(); val groupTitle=input.text(300); val minimum=input.readInt(); val maximum=input.readInt()
                        val choices=List(input.count(100)) { MenuChoice(input.text(),input.text(300),input.readBoolean()) }
                        MenuChoiceGroup(groupId,groupTitle,minimum,maximum,choices)
                    }
                    ConfiguredProduct(restaurant,MenuProduct(id,title,size,available,amount,groups),
                        CatalogProvenance(provider,version,capturedAt,fingerprint),complete,sizeGroup)
                }
                require(input.available()==0)
                CatalogLibrary(restaurants,menus,products)
            }
        } catch (_: IOException) { null } catch (_: IllegalArgumentException) { null }
    }
}
