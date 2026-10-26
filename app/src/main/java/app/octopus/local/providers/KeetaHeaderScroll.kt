package app.octopus.local.providers

data class HeaderViewport(val left: Int, val top: Int, val right: Int, val bottom: Int)
data class HeaderSwipe(val x: Int, val fromY: Int, val toY: Int)

/** Downward scroll stays inside the menu and above its checkout footer. No tap coordinates. */
object KeetaHeaderScroll {
    fun plan(view: HeaderViewport, window: HeaderViewport, footerTop: Int?): HeaderSwipe? {
        val left=maxOf(view.left,window.left);val right=minOf(view.right,window.right)
        val top=maxOf(view.top,window.top)
        val bottom=minOf(view.bottom,footerTop ?: (window.bottom-(window.bottom-window.top)/10))-32
        val height=bottom-top
        if(right-left<200 || height<400 || top<0) return null
        return HeaderSwipe((left+right)/2,top+height/5,top+height*3/4)
    }
}

/** Same verified menu-only bounds, upward stroke to reveal later food rows. Never a tap. */
object KeetaMenuScroll {
    fun plan(view:HeaderViewport,window:HeaderViewport,footerTop:Int?):HeaderSwipe? =
        KeetaHeaderScroll.plan(view,window,footerTop)?.let {HeaderSwipe(it.x,it.toY,it.fromY)}
}
