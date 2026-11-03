package app.octopus.local.providers

/** HungerStation (S22, 8.0.304) asks "هل تقوم بالطلب لموقع مختلف؟" when the phone is away from the saved
 * address. Octopus never taps either of its buttons (change address / confirm order for the address);
 * it only recognizes the prompt so it can be dismissed with the system Back action. */
object HungerStationLocationPrompt {
    const val TITLE = "هل تقوم بالطلب لموقع مختلف؟"
    private const val CHANGE = "تغيير الموقع"
    private const val KEEP = "تأكيد الطلب للموقع الحالي"

    fun shown(frame: CatalogFrame): Boolean {
        val labels = frame.nodes.map { it.label.trim() }
        return labels.any { it == TITLE } && labels.any { it == CHANGE } && labels.any { it == KEEP } &&
            frame.nodes.none { it.editable }
    }
}
