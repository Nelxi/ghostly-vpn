package app.ghostly.core

import app.ghostly.core.link.HappLinks
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HappLinksTest {
    @Test
    fun encryptedLinksAreRecognised() {
        assertTrue(HappLinks.isEncrypted("happ://crypt/AbCd=="))
        assertTrue(HappLinks.isEncrypted(" HAPP://crypt4/xyz"))
        assertTrue(HappLinks.isEncrypted("happ://crypt5/xyz"))
    }

    @Test
    fun plainHappLinksAreNot() {
        assertFalse(HappLinks.isEncrypted("happ://add/https://example.com/sub/1"))
        assertFalse(HappLinks.isEncrypted("https://example.com/happ://crypt/x"))
        assertFalse(HappLinks.isEncrypted("vless://uuid@host:443"))
    }
}
