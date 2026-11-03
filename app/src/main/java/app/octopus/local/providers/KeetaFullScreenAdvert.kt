package app.octopus.local.providers

/** Keeta 3.13.x opens a full-screen picture advert over its home on launch (observed 2026-11-03).
 * Its window has no text at all: one large clickable picture and one small clickable close image beside it.
 * Only that close image is ever returned; any text, edit field or list means it is not this advert. */
object KeetaFullScreenAdvert {
    fun closeTarget(frame: CatalogFrame): CatalogNode? {
        if (!frame.complete || frame.nodes.size !in 6..30 ||
            frame.nodes.any { it.label.isNotBlank() || it.omittedText || it.editable || it.enteredText || it.scrollable }) return null
        val close = frame.nodes.filter { it.clickable && it.className == "android.widget.ImageView" }.singleOrNull() ?: return null
        if (!close.enabled || frame.descendants(close.index).size != 1) return null
        val siblings = frame.nodes.filter { it.parent == close.parent && it.index != close.index }
        val picture = siblings.singleOrNull() ?: return null
        if (!picture.clickable || picture.className != "android.view.ViewGroup") return null
        // The picture holds only image layers; nothing in it can be a cart, payment or address control.
        val inside = frame.descendants(picture.index).drop(1)
        if (inside.size > 8 || inside.any { it.clickable }) return null
        return close
    }
}
