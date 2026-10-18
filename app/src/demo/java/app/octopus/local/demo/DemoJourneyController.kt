package app.octopus.local.demo

import app.octopus.local.domain.ProviderId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** No Context, Intents, provider adapters, accessibility bridge, or real quote storage. */
class DemoJourneyController {
    private val mutable = MutableStateFlow(DemoJourneyState())
    val state = mutable.asStateFlow()
    private var nextId = 1L
    private fun invalidated(value: DemoJourneyState) = value.copy(quotes = emptyList(), selected = null, epoch = value.epoch + 1)
    fun navigate(page: JourneyPage) {
        mutable.update { current ->
            var target = page
            if (target == JourneyPage.MENU && current.restaurantId == null) target = JourneyPage.RESTAURANTS
            if (target == JourneyPage.ITEM && current.draft == null) target = JourneyPage.MENU
            if (target in setOf(JourneyPage.READY, JourneyPage.HANDOFF) && current.quotes.none { it.provider == current.selected }) target = JourneyPage.RESULTS
            current.copy(page = target, epoch = current.epoch + if (current.page == JourneyPage.COMPARING) 1 else 0, message = null)
        }
    }
    fun search(value: String) { mutable.update { it.copy(search = value) } }
    fun category(value: String) { if (value in listOf("الكل", "برجر", "بيتزا", "مشويات")) mutable.update { it.copy(category = value) } }
    fun chooseRestaurant(id: String) {
        if (DemoCatalog.restaurants.none { it.id == id }) return
        mutable.update { current ->
            if (current.cart.isNotEmpty() && current.restaurantId != id) current.copy(pendingRestaurant = id)
            else current.copy(restaurantId = id, page = JourneyPage.MENU, pendingRestaurant = null, message = null)
        }
    }
    fun confirmRestaurantChange() {
        val id = mutable.value.pendingRestaurant ?: return
        mutable.update { invalidated(it).copy(restaurantId = id, cart = emptyList(), draft = null, pendingRestaurant = null, page = JourneyPage.MENU) }
    }
    fun dismissRestaurantChange() { mutable.update { it.copy(pendingRestaurant = null) } }
    fun openItem(id: String, editingId: Long? = null) {
        val current = mutable.value
        val item = DemoCatalog.items.firstOrNull { it.id == id && it.restaurantId == current.restaurantId } ?: return
        val existing = current.cart.firstOrNull { it.id == editingId }
        if (editingId != null && (existing == null || existing.itemId != id)) return
        val draft = existing?.let { DemoDraft(it.itemId, it.sizeId, it.drinkId, it.extras, it.quantity, it.id) }
            ?: DemoDraft(item.id, item.sizes.firstOrNull()?.id)
        mutable.update { it.copy(page = JourneyPage.ITEM, draft = draft) }
    }
    fun size(id: String) { editDraft { draft -> if (DemoCatalog.item(draft.itemId).sizes.any { it.id == id }) draft.copy(sizeId = id) else draft } }
    fun drink(id: String) { editDraft { draft -> if (DemoCatalog.item(draft.itemId).drinks.any { it.id == id }) draft.copy(drinkId = id) else draft } }
    fun extra(id: String, checked: Boolean) { editDraft { draft ->
        if (DemoCatalog.item(draft.itemId).extras.none { it.id == id }) draft else draft.copy(extras = if (checked) draft.extras + id else draft.extras - id)
    } }
    private fun editDraft(transform: (DemoDraft) -> DemoDraft) { mutable.update { it.copy(draft = it.draft?.let(transform)) } }
    fun draftQuantity(delta: Int) { editDraft { it.copy(quantity = (it.quantity + delta).coerceIn(1, 10)) } }
    fun addDraft(): Boolean {
        val draft = mutable.value.draft ?: return false
        if (!DemoPricing.valid(draft)) return false
        val item = DemoCatalog.item(draft.itemId)
        if (item.restaurantId != mutable.value.restaurantId) return false
        mutable.update { current ->
            val cart = current.cart.filter { it.id != draft.editingId }.toMutableList()
            val index = cart.indexOfFirst { it.itemId == draft.itemId && it.sizeId == draft.sizeId && it.drinkId == draft.drinkId && it.extras == draft.extras && it.quantity + draft.quantity <= 10 }
            if (index >= 0) cart[index] = cart[index].copy(quantity = cart[index].quantity + draft.quantity)
            else cart += DemoLine(draft.editingId ?: nextId++, draft.itemId, draft.sizeId, draft.drinkId, draft.extras, draft.quantity)
            invalidated(current).copy(cart = cart, draft = null, page = JourneyPage.MENU)
        }
        return true
    }
    fun quantity(id: Long, delta: Int) { mutable.update { current -> invalidated(current).copy(cart = current.cart.map { if (it.id == id) it.copy(quantity = (it.quantity + delta).coerceIn(1, 10)) else it }) } }
    fun remove(id: Long) { mutable.update { invalidated(it).copy(cart = it.cart.filterNot { line -> line.id == id }) } }
    fun allow(provider: ProviderId, allowed: Boolean) { mutable.update { invalidated(it).copy(allowed = if (allowed) it.allowed + provider else it.allowed - provider) } }
    fun address(address: DemoAddress) { mutable.update { current -> invalidated(current).copy(address = address, page = if (current.cart.isEmpty()) JourneyPage.RESTAURANTS else JourneyPage.CART) } }
    fun beginComparison(): Long? {
        val current = mutable.value
        if (current.cart.isEmpty() || current.allowed.size != ProviderId.entries.size) return null
        val next = invalidated(current).copy(page = JourneyPage.COMPARING, progress = 0)
        mutable.value = next
        return next.epoch
    }
    fun advanceComparison(epoch: Long) {
        mutable.update { current ->
            if (current.epoch != epoch || current.page != JourneyPage.COMPARING) current
            else if (current.progress < 4) current.copy(progress = current.progress + 1)
            else current.copy(progress = 5, quotes = current.allowed.map { DemoPricing.quote(current.cart, it) }.sortedBy { it.orderCost }, page = JourneyPage.RESULTS)
        }
    }
    fun cancelComparison() { mutable.update { invalidated(it).copy(page = JourneyPage.CART, message = "أوقفت محاكاة المقارنة؛ لم تتغير أي سلة حقيقية.") } }
    fun select(provider: ProviderId) { if (mutable.value.quotes.any { it.provider == provider }) mutable.update { it.copy(selected = provider) } }
}
