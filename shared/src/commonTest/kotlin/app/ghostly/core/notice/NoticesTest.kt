package app.ghostly.core.notice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NoticesTest {
    @Test
    fun subIdFromLink() {
        assertEquals("sub_ec3df87ca5d81fc8", Notices.subIdOf("https://ghostlynex.fun/sub/sub_ec3df87ca5d81fc8#Ghostly%20VPN"))
        assertEquals("sub_abc123", Notices.subIdOf("https://ghostlynex.fun/sub/sub_abc123?fmt=xray"))
        assertNull(Notices.subIdOf("https://example.com/api/v1/client/subscribe?token=x"))
    }
}
