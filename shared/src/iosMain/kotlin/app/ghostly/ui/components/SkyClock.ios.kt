package app.ghostly.ui.components

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970

internal actual fun localWallClockMs(): Long {
    val now = NSDate()
    val offsetS = NSTimeZone.localTimeZone.secondsFromGMTForDate(now)
    return (now.timeIntervalSince1970 * 1000.0).toLong() + offsetS * 1000L
}
