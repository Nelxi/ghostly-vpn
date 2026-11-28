package app.ghostly.desktop

import java.awt.EventQueue
import java.awt.Frame
import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowActivationTest {
    @Test
    fun restoresHiddenAndMinimizedWindowRepeatedly() {
        if (GraphicsEnvironment.isHeadless()) return
        EventQueue.invokeAndWait {
            val window = Frame("Ghostly activation test")
            try {
                window.setSize(200, 100)
                repeat(3) {
                    window.extendedState = Frame.ICONIFIED
                    window.isVisible = false
                    activateWindow(window)
                    assertTrue(window.isVisible)
                    assertEquals(0, window.extendedState and Frame.ICONIFIED)
                    activateWindow(window)
                    assertTrue(window.isVisible)
                }
            } finally {
                window.dispose()
            }
        }
    }
}
