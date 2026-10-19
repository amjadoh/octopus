package app.octopus.local

import app.octopus.local.automation.RidanQuantityCommand
import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test

class RidanQuantityCommandTest {
    private fun frame(quantity: Int): CatalogFrame {
        fun n(i: Int, p: Int, id: String, label: String = "", cls: String = "android.view.View", click: Boolean = false) =
            CatalogNode(i, p, id, label, cls, click, false, false)
        return CatalogFrame(listOf(n(0,-1,"FlutterCart_title","السلة\nريدان"),
            n(1,-1,"FlutterCart_cartItem","نصف دجاج مضغوط\n${quantity * 30}\n§"),
            n(2,1,"menuItemActionDualButtonValue","$quantity"),
            n(3,2,"menuItemActionDualButtonSubtract",cls="android.widget.Button"),
            n(4,3,"",cls="android.widget.ImageView",click=true),
            n(5,2,"menuItemActionDualButtonAdd",cls="android.widget.Button"),
            n(6,5,"",cls="android.widget.ImageView",click=true)),true)
    }
    @Test fun quantityDispatchIsSingleUseAndBoundToBeforeState() {
        val frame=frame(2);val target=RidanQuantityCommand.target(frame,2,true)!!
        assertEquals(6,target.index)
        val command=RidanQuantityCommand(2,true,1000)
        assertTrue(command.consume(frame,target,1001));assertFalse(command.consume(frame,target,1002))
        assertFalse(RidanQuantityCommand(1,true,1000).consume(frame,target,1001))
        assertFalse(RidanQuantityCommand(2,true,1000).consume(frame,target,17000))
    }
    @Test fun cannotDeleteLastMealOrModifyUnknownBasket() {
        assertNull(RidanQuantityCommand.target(frame(1),1,false))
        assertNull(RidanQuantityCommand.target(frame(99),99,true))
        val f=frame(2)
        assertNull(RidanQuantityCommand.target(f.copy(complete=false),2,true))
        assertNull(RidanQuantityCommand.target(f.copy(nodes=f.nodes+f.nodes[1].copy(index=9)),2,true))
        assertNull(RidanQuantityCommand.target(f.copy(nodes=f.nodes.map { if(it.id=="FlutterCart_title") it.copy(label="السلة\nمطعم آخر") else it }),2,true))
    }
}
