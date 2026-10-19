package app.octopus.local

import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test

class KeetaHeaderScrollTest {
    @Test fun swipeStaysInsideMenuAboveCheckoutAndMovesDown() {
        val viewport=HeaderViewport(0,300,1060,2310)
        val window=HeaderViewport(0,0,1060,2310)
        val swipe=KeetaHeaderScroll.plan(viewport,window,2118)!!
        assertEquals(530,swipe.x)
        assertTrue(swipe.fromY>viewport.top)
        assertTrue(swipe.toY>swipe.fromY+100)
        assertTrue(swipe.toY<2118-32)
    }
    @Test fun clippedSmallOrInvalidViewportCannotProduceGesture() {
        val window=HeaderViewport(0,0,1060,2310)
        assertNull(KeetaHeaderScroll.plan(HeaderViewport(0,1900,1060,2310),window,2118))
        assertNull(KeetaHeaderScroll.plan(HeaderViewport(0,0,100,2000),window,2118))
        val swipe=KeetaHeaderScroll.plan(HeaderViewport(-100,-100,2000,3000),window,null)!!
        assertTrue(swipe.x in 0..1060 && swipe.fromY>=0 && swipe.toY<2079)
    }
}
