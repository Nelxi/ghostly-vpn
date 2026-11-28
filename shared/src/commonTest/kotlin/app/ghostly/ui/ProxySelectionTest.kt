package app.ghostly.ui

import app.ghostly.ui.screens.ProxySelection
import kotlin.test.Test
import kotlin.test.assertEquals

class ProxySelectionTest {
    private val order = listOf("a", "b", "c", "d", "e")

    @Test fun shiftUsesTheAnchorAndCtrlTogglesWithoutLosingOtherRows() {
        var s = ProxySelection().click("b", order, true, false)
        s = s.click("d", order, false, true)
        assertEquals(setOf("b", "c", "d"), s.ids)
        s = s.click("c", order, true, false)
        assertEquals(setOf("b", "d"), s.ids)
        s = s.click("a", order, true, true)
        assertEquals(setOf("a", "b", "c", "d"), s.ids)
    }

    @Test fun deletionAndMissingAnchorsNeverSelectUnrelatedRows() {
        val s = ProxySelection(setOf("b", "gone"), "gone").retain(order)
        assertEquals(ProxySelection(setOf("b"), null), s)
        assertEquals(setOf("e"), s.click("e", order, false, true).ids)
    }
}
