package app.octopus.local

import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test

class KeetaRidanBasketTest {
    @Test fun configuredDiscountRowKeepsExactChoicesAndCurrentPrice() {
        val reader = KeetaMenuReader("مطعم تجريبي", "وجبة تجريبية", "3.13.301", setOf("جانب أ", "مشروب ب"))
        val original = fixture(1)
        val price = original.nodes.single { it.label == "30.00 ﷼" }
        fun n(i: Int, p: Int, text: String = "", cls: String = "android.view.ViewGroup") =
            CatalogNode(i,p,"",text,cls,false,false,false)
        val nodes = original.nodes.filter { it.index != price.index }.map {
            it.copy(label = when(it.label) { "ريدان" -> reader.restaurant; "نصف دجاج مضغوط" -> reader.title; else -> it.label })
        } + listOf(n(100,price.parent),n(101,100,"20.00 ﷼","android.widget.TextView"),n(102,100),
            n(103,102),n(104,103,"25.00 ﷼","android.widget.TextView"),
            n(105,price.parent,"خصم 20%","android.widget.TextView"),
            n(106,price.parent,"وجبة تجريبية، جانب أ، مشروب ب","android.widget.TextView"))
        val f = CatalogFrame(nodes,true)
        val actual = KeetaBasketReader(reader).evidence(f)!!
        assertEquals(2000L, actual.subtotal)
        assertEquals(setOf("جانب أ","مشروب ب"),actual.lines.single().choices.map { it.value }.toSet())
        assertNull(KeetaBasketReader(reader).evidence(f.copy(nodes=nodes.map { if(it.index==106) it.copy(label="وجبة أخرى، جانب أ، مشروب ب") else it })))
        assertNull(KeetaBasketReader(reader).evidence(f.copy(nodes=nodes.map { if(it.index==104) it.copy(parent=price.parent) else it })))
        val card = original.nodes.single { it.index == price.parent }
        val section = original.nodes.single { it.index == card.parent }
        val totals = listOf(n(200,section.parent),n(201,200),
            n(202,201,"تفاصيل العرض الترويجي","android.widget.TextView"),
            n(203,201,"يتم تأكيد المبلغ النهائي عند صفحة إتمام الدفع","android.widget.TextView"),
            n(204,200),n(205,204,"الإجمالي الفرعي","android.widget.TextView"),n(206,204,"25.00 ﷼","android.widget.TextView"),
            n(207,200),n(208,207,"الخصم","android.widget.TextView"),n(209,207,"-5.00 ﷼","android.widget.TextView"),
            n(210,200),n(216,210).copy(clickable=true),n(211,216,"الأصناف الترويجية","android.widget.TextView"),n(212,210,"-5.00 ﷼","android.widget.TextView"),
            n(213,200),n(214,213,"الإجمالي","android.widget.TextView"),n(215,213,"20.00 ﷼","android.widget.TextView"))
        assertEquals(2000L,KeetaBasketReader(reader).evidence(f.copy(nodes=nodes+totals))!!.subtotal)
        assertNull(KeetaBasketReader(reader).evidence(f.copy(nodes=nodes+totals.map {if(it.index==215)it.copy(label="19.00 ﷼")else it})))
    }
    @Test fun multipleVisiblePlainRowsAreReadWithoutDroppingOrMergingProducts() {
        val one = fixture()
        val first = one.nodes.single { it.label == "نصف دجاج مضغوط" }
        val card = one.nodes.single { it.index == first.parent }
        val body = one.descendants(card.index)
        val shift = 100
        val copied = body.map { node -> node.copy(index = node.index + shift,
            parent = if (node.index == card.index) card.parent else node.parent + shift,
            label = if (node.label == "نصف دجاج مضغوط") "صنف ثان" else node.label) }
        val two = one.copy(nodes = one.nodes + copied)
        val reader = KeetaBasketReader(KeetaMenuPreview, setOf("نصف دجاج مضغوط", "صنف ثان"))
        val result = reader.evidence(two)!!
        assertEquals(2, result.lines.size)
        assertEquals(18000L, result.subtotal)
        assertEquals(setOf("نصف دجاج مضغوط", "صنف ثان"), result.lines.map { it.name }.toSet())
        assertNull(KeetaRidanBasket.evidence(two))
        val unknown = two.copy(nodes = two.nodes.map { if (it.label == "صنف ثان") it.copy(label = "صنف غير مطلوب") else it })
        assertNull(reader.evidence(unknown))
        val duplicate = two.copy(nodes = two.nodes.map { if (it.label == "صنف ثان") it.copy(label = "نصف دجاج مضغوط") else it })
        assertNull(reader.evidence(duplicate))
        assertNotNull(reader.quantityTarget(two,true))
    }
    private fun fixture(quantity: Int = 3, unknown: Boolean = false, scrollable: Boolean = false): CatalogFrame {
        val nodes = mutableListOf<CatalogNode>()
        fun n(parent: Int, label: String = "", cls: String = "android.view.ViewGroup", click: Boolean = false, scroll: Boolean = false, omitted: Boolean = false): Int {
            val index = nodes.size; nodes += CatalogNode(index, parent, "", label, cls, click, false, scroll, omittedText = omitted); return index
        }
        val root = n(-1); n(root, "ريدان", "android.widget.TextView")
        n(root, "إتمام الدفع", "android.widget.TextView")
        val pane = n(root); val header = n(pane)
        n(header, "الأصناف المضافة", "android.widget.TextView"); n(header, "تفريغ السلة", "android.widget.TextView")
        val scroll = n(pane, cls = "android.widget.ScrollView", scroll = scrollable)
        val content = n(scroll); val rows = n(content); val row = n(rows, click = true)
        n(row, "نصف دجاج مضغوط", "android.widget.TextView")
        n(row, "${quantity * 30}.00 ﷼", "android.widget.TextView")
        if (unknown) n(row, omitted = true)
        val controls = n(row)
        val minus = n(controls); n(minus, click = true); n(minus, cls = "android.widget.ImageView")
        n(controls, "$quantity", "android.widget.TextView", click = true)
        val plus = n(controls); n(plus, click = true); n(plus, cls = "android.widget.ImageView")
        return CatalogFrame(nodes, true)
    }
    @Test fun readsCompleteFixedMealAndOnlyItsQuantityControls() {
        val f = fixture()
        val evidence = KeetaRidanBasket.evidence(f)!!
        assertTrue(evidence.complete); assertEquals(3, evidence.lines.single().quantity)
        assertEquals(9000L, evidence.subtotal)
        val plus = KeetaRidanBasket.quantityTarget(f, true)!!
        val minus = KeetaRidanBasket.quantityTarget(f, false)!!
        assertNotEquals(plus.index, minus.index)
        assertTrue(listOf(plus, minus).all { it.clickable && it.label.isEmpty() })
        assertFalse(evidence.recognizedOrderReview)
    }
    @Test fun rejectsUnknownOptionsAndIncompleteRows() {
        assertNull(KeetaRidanBasket.evidence(fixture(unknown = true)))
        assertNull(KeetaRidanBasket.evidence(fixture(scrollable = true)))
        val f = fixture(); assertNull(KeetaRidanBasket.evidence(f.copy(complete = false)))
        val title = f.nodes.single { it.label == "نصف دجاج مضغوط" }
        assertNull(KeetaRidanBasket.evidence(f.copy(nodes = f.nodes + title.copy(index = 100))))
    }
    @Test fun neverSelectsRemoveLastItemOrExceedsQuantityLimit() {
        assertNull(KeetaRidanBasket.quantityTarget(fixture(1), false))
        assertNull(KeetaRidanBasket.quantityTarget(fixture(99), true))
    }
    private fun product(quantity: Int = 1, entered: Boolean = false, scrollable: Boolean = false): CatalogFrame {
        val nodes=mutableListOf<CatalogNode>()
        fun n(p:Int,label:String="",cls:String="android.view.ViewGroup",click:Boolean=false,edit:Boolean=false,scroll:Boolean=false):Int {
            val i=nodes.size;nodes+=CatalogNode(i,p,"",label,cls,click,edit,scroll,enteredText=edit&&entered);return i
        }
        val root=n(-1);n(root,"ريدان","android.widget.TextView")
        val body=n(root,cls="android.widget.ScrollView",scroll=scrollable)
        n(body,"نصف دجاج مضغوط","android.widget.TextView");n(body,"طلب خاص","android.widget.TextView")
        n(body,cls="android.widget.EditText",edit=true)
        val footer=n(root);n(footer,"$quantity","android.widget.TextView")
        val button=n(footer);n(button,click=true);val labelBody=n(button)
        n(labelBody,"إضافة إلى السلة","android.widget.TextView");n(labelBody,"${quantity*30}.00 ﷼","android.widget.TextView")
        return CatalogFrame(nodes,true)
    }
    @Test fun freshAddRequiresOneMealExactPriceEmptyNoteAndCompleteProduct() {
        assertNotNull(KeetaRidanBasket.addTarget(product(),3000))
        assertNull(KeetaRidanBasket.addTarget(product(),3100))
        assertNull(KeetaRidanBasket.addTarget(product(2),3000))
        assertNull(KeetaRidanBasket.addTarget(product(entered=true),3000))
        assertNull(KeetaRidanBasket.addTarget(product(scrollable=true),3000))
        assertNull(KeetaRidanBasket.addTarget(product().copy(complete=false),3000))
    }
    @Test fun selectedRestaurantAndMealAreBoundToTheirOwnEvidence() {
        val selected = KeetaMenuReader("مدور", "فطيرة شكشوكة بالجبن")
        val reader = KeetaBasketReader(selected)
        val changed = fixture().copy(nodes = fixture().nodes.map {
            when (it.label) {
                "ريدان" -> it.copy(label = selected.restaurant)
                "نصف دجاج مضغوط" -> it.copy(label = selected.title)
                else -> it
            }
        })
        assertEquals(selected.restaurant, reader.evidence(changed)!!.restaurant)
        assertEquals(selected.title, reader.evidence(changed)!!.lines.single().name)
        assertNotNull(reader.quantityTarget(changed, true))
        assertNull(KeetaRidanBasket.evidence(changed))
        assertNull(reader.evidence(fixture()))
        assertNull(KeetaBasketReader(KeetaMenuReader("مطعم آخر", selected.title)).evidence(changed))
    }
    @Test fun selectedMealAddStillChecksPriceNotesOptionsAndQuantity() {
        val selected = KeetaMenuReader("مطعم آخر", "فطيرة")
        val reader = KeetaBasketReader(selected)
        fun renamed(frame: CatalogFrame) = frame.copy(nodes=frame.nodes.map {
            when(it.label) { "ريدان" -> it.copy(label=selected.restaurant); "نصف دجاج مضغوط" -> it.copy(label=selected.title); else -> it }
        })
        assertNotNull(reader.addTarget(renamed(product()),3000))
        assertNull(reader.addTarget(renamed(product()),3100))
        assertNull(reader.addTarget(renamed(product(entered=true)),3000))
        assertNull(reader.addTarget(renamed(product(2)),3000))
        assertNull(reader.addTarget(renamed(product(scrollable=true)),3000))
    }
}
