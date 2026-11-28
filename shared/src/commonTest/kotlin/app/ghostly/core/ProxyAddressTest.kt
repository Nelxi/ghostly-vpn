package app.ghostly.core

import app.ghostly.core.model.ExtraProxy
import app.ghostly.core.model.address
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ProxyAddressTest {
    @Test fun specialCredentialsAndIpv6RemainValidProxyAddresses() {
        val proxy = ExtraProxy("x", port = 11080, auth = true, user = "a@b", pass = "x:y/z")
        assertEquals("socks5://a%40b:x%3Ay%2Fz@[::1]:11080", proxy.address("::1"))
        assertFalse(proxy.address("127.0.0.1", maskPassword = true).contains("x%3Ay"))
    }
}
