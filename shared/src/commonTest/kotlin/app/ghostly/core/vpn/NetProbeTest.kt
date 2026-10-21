package app.ghostly.core.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class NetProbeTest {
    @Test
    fun foreignSiteAnsweringMeansNoWhiteLists() {
        assertEquals(NetMode.OPEN, NetProbe.classify(russianOk = 2, foreignOk = 1))
        // Yandex down for a moment while Google answers: still open.
        assertEquals(NetMode.OPEN, NetProbe.classify(russianOk = 0, foreignOk = 2))
    }

    @Test
    fun onlyRussianSitesMeansWhiteLists() {
        assertEquals(NetMode.WHITELIST, NetProbe.classify(russianOk = 1, foreignOk = 0))
        assertEquals(NetMode.WHITELIST, NetProbe.classify(russianOk = 2, foreignOk = 0))
    }

    @Test
    fun nothingAnsweringMeansNoInternet() {
        assertEquals(NetMode.OFFLINE, NetProbe.classify(russianOk = 0, foreignOk = 0))
    }

    @Test
    fun runProbesEveryHost() = runTest {
        val asked = mutableListOf<String>()
        val whiteListed = NetProbe.run { host, _ -> asked += host; if (host in NetProbe.RUSSIAN) 40 else -1 }
        assertEquals(NetMode.WHITELIST, whiteListed)
        assertEquals((NetProbe.RUSSIAN + NetProbe.FOREIGN).toSet(), asked.toSet())
        assertEquals(NetMode.OPEN, NetProbe.run { host, _ -> if (host == "www.google.com") 80 else -1 })
        assertEquals(NetMode.OFFLINE, NetProbe.run { _, _ -> -1 })
    }
}
