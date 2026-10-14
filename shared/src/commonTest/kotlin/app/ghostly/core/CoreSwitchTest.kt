package app.ghostly.core

import app.ghostly.core.mihomo.forCore
import app.ghostly.core.mihomo.visibleFor
import app.ghostly.core.mihomo.withFetched
import app.ghostly.core.model.CoreType
import app.ghostly.core.model.Profile
import app.ghostly.core.model.Server
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** A subscription fetched on both cores keeps both formats, so a core switch never hides it. */
class CoreSwitchTest {
    private val xrayServers = listOf(Server("p:0", "🔀 Авто", "balancer", config = JsonObject(mapOf("x" to JsonPrimitive(1)))))
    private val clash = JsonObject(mapOf("proxies" to JsonPrimitive("…")))
    private val clashServers = listOf(Server("p:0", "Ghostly", "mihomo", mihomo = JsonObject(emptyMap())))
    private val onXray = Profile("p", "Ghostly", url = "https://x/sub/1", servers = xrayServers)

    @Test
    fun aFetchInTheOtherFormatStashesTheOldOne() {
        val onMihomo = onXray.withFetched(clashServers, clash)
        assertEquals(clash, onMihomo.mihomo)
        assertEquals(xrayServers, onMihomo.stash?.servers)
        assertNull(onMihomo.stash?.mihomo)
        // Same format again: the stash (the Xray copy) stays.
        assertEquals(xrayServers, onMihomo.withFetched(clashServers, clash).stash?.servers)
    }

    @Test
    fun switchingBackToXrayShowsTheSubscriptionAtOnce() {
        val onMihomo = onXray.withFetched(clashServers, clash)
        // The bug: stored as Clash only, the subscription vanished on Xray until a refetch succeeded.
        assertNull(onMihomo.visibleFor(CoreType.XRAY))
        val back = onMihomo.forCore(CoreType.XRAY)
        assertNotNull(back.visibleFor(CoreType.XRAY))
        assertEquals(xrayServers, back.servers)
        assertNull(back.mihomo)
        // ...and the Clash copy is kept for the next switch.
        assertEquals(clash, back.stash?.mihomo)
        assertEquals(clash, back.forCore(CoreType.MIHOMO).mihomo)
    }

    @Test
    fun nothingToSwapLeavesTheProfileAlone() {
        assertSame(onXray, onXray.forCore(CoreType.XRAY))
        val clashOnly = Profile("c", "Clash", servers = clashServers, mihomo = clash)
        assertSame(clashOnly, clashOnly.forCore(CoreType.XRAY))
    }
}
