package app.ghostly.core.update

import app.ghostly.core.vpn.PlatformInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The background updater used to download the whole build and start the installer again every two
 * minutes for as long as the install did not happen (an Android app that is not on screen can't show the
 * installer at all): tens of gigabytes a day for one phone. These are the rules that bound it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdaterTest {

    private class FakePlatform : PlatformInfo {
        override val os = "Android"
        override val osVersion = "14"
        override val deviceModel = "Test"
        override val hwid = "0123456789abcdef"
        override val appVersion = "1.0.0"
        override val dataDir = ""
        override fun openUrl(url: String) {}
        override fun copyToClipboard(text: String) {}
        override fun readClipboard(): String? = null

        var onScreen = true
        var downloads = 0
        var launches = 0
        var download: suspend () -> Unit = {}
        var launch: () -> Unit = {}

        override suspend fun downloadVerified(urls: List<String>, sha256: String, size: Long, onProgress: (Float) -> Unit): String {
            downloads++
            download()
            onProgress(1f)
            return "update.apk"
        }

        override fun canAutoInstall(): Boolean = onScreen

        override fun installUpdate(path: String) {
            launch()
            launches++
        }
    }

    private class Rig {
        val platform = FakePlatform()
        var stored: InstallTry? = null
        var now = 1_800_000_000_000L
        val updater = Updater(platform, lastTry = { stored }, saveTry = { stored = it }, nowMs = { now }) { false }
        val offer = UpdateOffer("1.1.0", "Ghostly-Android.apk", "00", 10)

        /** One turn of the background loop, as GhostlyController does it. */
        suspend fun tick() {
            if (platform.canAutoInstall() && updater.mayAutoInstall(offer.version)) updater.install(offer, auto = true)
        }
    }

    private val hour = 3600_000L

    @Test
    fun aVersionIsHandedOverOnceAndThenLeftAlone() = runTest {
        val r = Rig()
        r.tick()
        assertEquals(1, r.platform.launches)
        assertEquals(InstallTry("1.1.0", 1, r.now), r.stored)
        // The old loop: every two minutes, for hours.
        repeat(200) {
            r.now += 2 * 60_000L
            r.tick()
        }
        // 400 minutes later: the first one plus one more after the six-hour gap.
        assertEquals(2, r.platform.launches)
        assertEquals(2, r.platform.downloads)
    }

    @Test
    fun threeTriesPerVersionAndNoMore() = runTest {
        val r = Rig()
        repeat(10) {
            r.tick()
            r.now += 7 * hour
        }
        assertEquals(Updater.AUTO_TRIES, r.platform.launches)
        r.now += 365 * 24 * hour
        assertFalse(r.updater.mayAutoInstall("1.1.0"))
        // The next release starts from zero.
        assertTrue(r.updater.mayAutoInstall("1.1.1"))
    }

    @Test
    fun theButtonAlwaysWorksAndIsNotCountedAsATry() = runTest {
        val r = Rig()
        r.stored = InstallTry("1.1.0", Updater.AUTO_TRIES, r.now)
        r.now += hour
        r.updater.install(r.offer)
        assertEquals(1, r.platform.launches)
        assertEquals(InstallTry("1.1.0", Updater.AUTO_TRIES, r.now), r.stored)
    }

    @Test
    fun afterATapTheBackgroundUpdaterWaitsToo() = runTest {
        val r = Rig()
        r.updater.install(r.offer)
        assertEquals(InstallTry("1.1.0", 0, r.now), r.stored)
        r.now += 2 * 60_000L
        r.tick()
        assertEquals(1, r.platform.launches)
        r.now += 6 * hour
        r.tick()
        assertEquals(2, r.platform.launches)
    }

    @Test
    fun anAppThatLeftTheScreenKeepsTheFileAndInstallsWhenItIsBack() = runTest {
        val r = Rig()
        r.platform.download = { r.platform.onScreen = false }
        r.tick()
        assertEquals(1, r.platform.downloads)
        assertEquals(0, r.platform.launches)
        assertNull(r.stored)
        assertEquals(UpdateStep.Idle, r.updater.step.value)
        // Not on screen: the loop does not even start a download.
        r.platform.download = {}
        r.now += 2 * 60_000L
        r.tick()
        assertEquals(1, r.platform.downloads)
        // Opened again.
        r.platform.onScreen = true
        r.tick()
        assertEquals(1, r.platform.launches)
        assertEquals(1, r.stored?.count)
    }

    @Test
    fun anInstallerThatDidNotStartIsNotATry() = runTest {
        val r = Rig()
        r.platform.launch = { throw IllegalStateException("no permission yet") }
        r.tick()
        assertEquals(0, r.platform.launches)
        assertNull(r.stored)
        assertEquals("no permission yet", assertIs<UpdateStep.Failed>(r.updater.step.value).message)
        assertTrue(r.updater.mayAutoInstall("1.1.0"))
    }

    @Test
    fun twoFailedDownloadsStopTheBackgroundUpdaterForThisRun() = runTest {
        val r = Rig()
        r.platform.download = { throw RuntimeException("HTTP 502") }
        repeat(5) { r.tick() }
        assertEquals(2, r.platform.downloads)
        assertTrue(r.updater.gaveUp("1.1.0"))
        assertIs<UpdateStep.Failed>(r.updater.step.value)
        // A tap still tries.
        r.platform.download = {}
        r.updater.install(r.offer)
        assertEquals(1, r.platform.launches)
    }

    @Test
    fun aSecondRequestDoesNotStartASecondDownload() = runTest {
        val r = Rig()
        val gate = CompletableDeferred<Unit>()
        r.platform.download = { gate.await() }
        val first = launch { r.updater.install(r.offer, auto = true) }
        runCurrent()
        assertIs<UpdateStep.Downloading>(r.updater.step.value)
        r.updater.install(r.offer)
        assertEquals(1, r.platform.downloads)
        gate.complete(Unit)
        first.join()
        assertEquals(1, r.platform.launches)
        assertEquals(UpdateStep.Idle, r.updater.step.value)
    }

    @Test
    fun theRuleItself() {
        val t = 1_800_000_000_000L
        assertTrue(Updater.autoAllowed("2.0.0", null, t))
        assertTrue(Updater.autoAllowed("2.0.0", InstallTry("1.9.0", 3, t), t))
        assertFalse(Updater.autoAllowed("2.0.0", InstallTry("2.0.0", 1, t), t))
        assertFalse(Updater.autoAllowed("2.0.0", InstallTry("2.0.0", 1, t), t + Updater.AUTO_GAP_MS - 1))
        assertTrue(Updater.autoAllowed("2.0.0", InstallTry("2.0.0", 1, t), t + Updater.AUTO_GAP_MS))
        assertFalse(Updater.autoAllowed("2.0.0", InstallTry("2.0.0", Updater.AUTO_TRIES, t), t + 100 * Updater.AUTO_GAP_MS))
        // A clock that went back does not lock the update out.
        assertTrue(Updater.autoAllowed("2.0.0", InstallTry("2.0.0", 1, t), t - 1))
    }
}
