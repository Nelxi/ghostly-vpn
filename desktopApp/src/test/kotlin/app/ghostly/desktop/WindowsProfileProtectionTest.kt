package app.ghostly.desktop

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse

class WindowsProfileProtectionTest {
    @Test fun windowsDpapiRoundTrip() {
        if (hostOs != HostOs.WINDOWS) return
        val plaintext = "https://example.com/sub/private-credential".encodeToByteArray()
        val sealed = WindowsProfileProtection.seal(plaintext)
        assertFalse(sealed.decodeToString().contains("private-credential"))
        assertContentEquals(plaintext, WindowsProfileProtection.open(sealed))
    }
}
