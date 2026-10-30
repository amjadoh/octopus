package app.octopus.local

import app.octopus.local.automation.CatalogCommands
import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test

class NativeCartMenuTargetTest {
    private val frame = CatalogFrame(listOf(
        CatalogNode(0,-1,"FlutterCart_title","السلة\nمطعم تجريبي","android.view.View",false,false,false),
        CatalogNode(1,-1,"FlutterCart_addMoreItemButton","enabled","android.view.View",false,false,false),
        CatalogNode(2,1,"","إضافة أصناف","android.widget.Button",true,false,false)
    ),true)
    @Test fun exactAddItemsControlReturnsToRestaurantWithoutUsingCartBack() {
        assertEquals(2,CatalogCommands.cartMenuTarget(frame)!!.index)
        assertNull(CatalogCommands.backTarget(frame))
    }
    @Test fun ambiguousIncompleteOrCheckoutControlsNeverAuthorizeReturn() {
        assertNull(CatalogCommands.cartMenuTarget(frame.copy(complete=false)))
        assertNull(CatalogCommands.cartMenuTarget(frame.copy(nodes=frame.nodes+frame.nodes.last().copy(index=3))))
        assertNull(CatalogCommands.cartMenuTarget(frame.copy(nodes=frame.nodes.map {
            if(it.index==2) it.copy(label="اذهب للدفع") else it })))
        assertNull(CatalogCommands.cartMenuTarget(frame.copy(nodes=frame.nodes.map {
            if(it.index==1) it.copy(id="FlutterCart_checkoutButton") else it })))
    }
}
