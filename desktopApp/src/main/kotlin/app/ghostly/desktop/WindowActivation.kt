package app.ghostly.desktop

import java.awt.EventQueue
import java.awt.Frame
import java.awt.Window

/** Shared by tray and second-instance activation, including an already-visible window behind others. */
internal fun activateWindow(window: Window) {
    check(EventQueue.isDispatchThread())
    if (window is Frame) window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
    window.isVisible = true
    window.toFront()
    window.requestFocus()
}
