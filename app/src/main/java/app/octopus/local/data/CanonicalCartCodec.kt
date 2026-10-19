package app.octopus.local.data

import app.octopus.local.domain.*
import java.io.*

/** Versioned local-only payload. No Java serialization, account identifiers or screen dumps. */
object CanonicalCartCodec {
    private const val VERSION = 1
    private const val MAX_BYTES = 512 * 1024
    private fun DataOutputStream.optional(value: String?) { writeBoolean(value != null); if (value != null) writeUTF(value) }
    private fun DataInputStream.optional(): String? = if (readBoolean()) readUTF() else null
    private fun DataInputStream.count(maximum: Int): Int = readInt().also { require(it in 0..maximum) }

    fun encode(cart: CanonicalCart): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(VERSION)
            out.writeUTF(cart.restaurant); out.optional(cart.branch); out.optional(cart.addressReference)
            out.writeInt(cart.items.size)
            cart.items.forEach { item ->
                out.writeUTF(item.name); out.optional(item.size); out.writeInt(item.quantity); out.optional(item.note)
                out.writeInt(item.options.size)
                item.options.forEach { option -> out.writeUTF(option.group); out.writeUTF(option.value); out.writeInt(option.quantity) }
            }
        }
        return buffer.toByteArray().also { require(it.size <= MAX_BYTES) }
    }

    fun decode(payload: ByteArray): CanonicalCart? {
        if (payload.size > MAX_BYTES) return null
        return try {
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                require(input.readInt() == VERSION)
                val restaurant = input.readUTF(); val branch = input.optional(); val address = input.optional()
                val items = List(input.count(100)) {
                    val name = input.readUTF(); val size = input.optional(); val quantity = input.readInt(); val note = input.optional()
                    val options = List(input.count(100)) { CartOption(input.readUTF(), input.readUTF(), input.readInt()) }
                    CanonicalItem(name, size, options, quantity, note)
                }
                require(input.available() == 0)
                CanonicalCart(restaurant, branch, items, address)
            }
        } catch (_: IOException) { null } catch (_: IllegalArgumentException) { null }
    }
}
