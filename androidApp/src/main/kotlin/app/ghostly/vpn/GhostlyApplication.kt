package app.ghostly.vpn

import android.annotation.SuppressLint
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import app.ghostly.core.vpn.Haptic
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import app.ghostly.core.GhostlyController
import app.ghostly.core.vpn.AppEntry
import app.ghostly.core.vpn.PlatformInfo
import app.ghostly.vpn.service.AndroidVpn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GhostlyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // The `:mihomo` process runs only the mihomo core: no Xray (a second Go runtime), no controller.
        if (isMihomoProcess()) {
            com.github.kr328.clash.common.Global.init(this)
            return
        }
        AndroidVpn.init(this)
        app.ghostly.vpn.service.AndroidMihomo.init(this)
    }

    private fun isMihomoProcess(): Boolean {
        val name = if (android.os.Build.VERSION.SDK_INT >= 28) Application.getProcessName()
        else runCatching { java.io.File("/proc/self/cmdline").readText().trim(Char(0)) }.getOrDefault("")
        return name.endsWith(":mihomo")
    }

    val platform by lazy { AndroidPlatform(this) }

    val controller by lazy {
        GhostlyController(platform, app.ghostly.core.mihomo.DualCoreBackend(AndroidVpn, app.ghostly.vpn.service.AndroidMihomo))
    }

    companion object {
        lateinit var instance: GhostlyApplication
            private set
    }
}

/** Up to here the slider uses the system primitives; above it, plain pulses (they can go much harder). */
private const val PRIMITIVE_MAX = 0.7f

class AndroidPlatform(private val context: Context) : PlatformInfo {
    override val os = "Android"
    override val osVersion: String = Build.VERSION.RELEASE
    override val deviceModel: String = listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL)
        .distinct().joinToString(" ")

    @SuppressLint("HardwareIds")
    override val hwid: String = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        ?.takeIf { it.length >= 8 } ?: "ghostly-${Build.FINGERPRINT.hashCode().toUInt()}"

    override val appVersion: String = BuildConfig.VERSION_NAME
    override val dataDir: String = context.filesDir.absolutePath
    override val supportsPerAppSplit = true

    /** Set by the visible activity: QR scanner needs an activity to launch from. */
    var activityQrScanner: ((onResult: (String) -> Unit) -> Unit)? = null

    override val qrScanner: ((onResult: (String) -> Unit) -> Unit)?
        get() = activityQrScanner

    /** The ghost sings along with the music playing on the phone. */
    override val stage: app.ghostly.vpn.stage.AndroidStage by lazy { app.ghostly.vpn.stage.AndroidStage(context) }

    override val systemVpnSettings: (() -> Unit) = {
        context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun copyToClipboard(text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Ghostly", text))
    }

    override fun readClipboard(): String? {
        val cm = context.getSystemService(ClipboardManager::class.java)
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    }

    override fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** The visible activity's window, for system haptics (set by MainActivity). */
    @Volatile var hapticView: java.lang.ref.WeakReference<android.view.View>? = null

    override fun haptic(kind: Haptic, strength: Float) {
        if (strength <= 0f) return
        if (vibrate(kind, strength.coerceIn(0f, 1f))) return
        val view = hapticView?.get() ?: return
        val run = Runnable {
            @Suppress("DEPRECATION")
            view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY, android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) run.run() else view.post(run)
    }

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
    }

    /**
     * Linear motors (Nothing, Pixel, Samsung flagships) get composed system primitives — crisp; others a
     * pulse of a set amplitude. Tagged as hardware feedback, which the ROM's "touch feedback" setting
     * doesn't mute. The primitives top out at what the ROM tuned them to (very soft on Nothing), so the
     * upper part of the strength slider switches to plain pulses: longer and at full amplitude.
     */
    private fun vibrate(kind: Haptic, strength: Float): Boolean {
        val v = vibrator ?: return false
        if (!v.hasVibrator()) return false
        return runCatching {
            val e = effect(v, kind, strength)
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(e, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_HARDWARE_FEEDBACK))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(e, android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
            }
        }.isSuccess
    }

    private fun effect(v: Vibrator, kind: Haptic, strength: Float): VibrationEffect {
        // A linear motor (Nothing, Pixel, Samsung…) always gets the system primitives — that crisp
        // "tap" is what these phones are for; the rough pulse below is only for motors without them.
        // The slider scales them, and its upper part stacks a second, heavier primitive right on top
        // (a tap you really feel on soft-tuned ROMs), instead of switching to a buzz.
        if (Build.VERSION.SDK_INT >= 30) {
            fun has(vararg p: Int) = v.areAllPrimitivesSupported(*p)
            val click = VibrationEffect.Composition.PRIMITIVE_CLICK
            val tick = VibrationEffect.Composition.PRIMITIVE_TICK
            if (has(click, tick)) {
                val thud = if (Build.VERSION.SDK_INT >= 31 && has(VibrationEffect.Composition.PRIMITIVE_THUD)) VibrationEffect.Composition.PRIMITIVE_THUD else click
                val k = (0.15f + strength / PRIMITIVE_MAX * 0.85f).coerceIn(0.05f, 1f)
                // 0 below PRIMITIVE_MAX, up to 1 at the right end: weight of the stacked body.
                val boost = ((strength - PRIMITIVE_MAX) / (1f - PRIMITIVE_MAX)).coerceIn(0f, 1f)
                val comp = VibrationEffect.startComposition()
                fun tap(p: Int, scale: Float, delay: Int = 0) {
                    comp.addPrimitive(p, scale, delay)
                    if (boost > 0f) comp.addPrimitive(if (p == tick) click else thud, boost, 0)
                }
                when (kind) {
                    Haptic.TICK -> tap(tick, k)
                    Haptic.CLICK -> tap(click, k)
                    Haptic.HEAVY -> { tap(thud, k); comp.addPrimitive(click, k, 20) }
                    Haptic.SUCCESS -> { tap(click, 0.7f * k); tap(thud, k, 70) }
                    Haptic.ERROR -> { tap(click, k); tap(click, k, 60); tap(click, k, 60) }
                }
                return comp.compose()
            }
        }
        val amp = v.hasAmplitudeControl()
        // Longer and harder pulses as the slider goes up; without amplitude control only the length changes.
        val len = 0.6f + strength * 1.6f
        fun ms(base: Int) = (base * len).toLong().coerceAtLeast(4)
        fun a(base: Int) = if (amp) (base * (0.35f + 0.65f * strength)).toInt().coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
        fun pulse(base: Int, amplitude: Int) = VibrationEffect.createOneShot(ms(base), a(amplitude))
        return when (kind) {
            Haptic.TICK -> pulse(12, 200)
            Haptic.CLICK -> pulse(20, 255)
            Haptic.HEAVY -> pulse(40, 255)
            Haptic.SUCCESS -> VibrationEffect.createWaveform(longArrayOf(0, ms(20), 70, ms(40)), if (amp) intArrayOf(0, a(180), 0, a(255)) else intArrayOf(0, 255, 0, 255), -1)
            Haptic.ERROR -> VibrationEffect.createWaveform(longArrayOf(0, ms(25), 50, ms(25), 50, ms(25)), if (amp) intArrayOf(0, a(255), 0, a(255), 0, a(255)) else intArrayOf(0, 255, 0, 255, 0, 255), -1)
        }
    }

    /** Material You: the wallpaper's primary accent, a light tone that reads on the dark UI. */
    override fun systemAccent(): Long? =
        if (Build.VERSION.SDK_INT >= 31) runCatching { context.getColor(android.R.color.system_accent1_200).toLong() and 0xFFFFFFFFL }.getOrNull()
        else null

    /** The app is excluded from its own tunnel, so these sockets go straight to the network. */
    override suspend fun blockCheck(target: app.ghostly.core.vpn.BlockTarget) = app.ghostly.core.vpn.JvmBlockCheck.run(target)

    override fun lanAddress(): String? = runCatching {
        // Real Wi-Fi/Ethernet address: skip loopback, down and virtual adapters (our own TUN too).
        val virtual = Regex("(?i)tun|tap|xray|ghostly|wintun|utun|vbox|vmware|veth|docker|br-|virtual|hyper-v|loopback|zerotier|tailscale|radmin|hamachi")
        java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback && !virtual.containsMatchIn(it.name + " " + (it.displayName ?: "")) }
            .flatMap { java.util.Collections.list(it.inetAddresses) }
            .filterIsInstance<java.net.Inet4Address>()
            .map { it.hostAddress }
            .filter { it.startsWith("192.168.") || it.startsWith("10.") || Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(it) }
            .filterNot { it.startsWith("172.19.0.") } // our tunnel subnet
            .sortedBy { if (it.startsWith("192.168.")) 0 else 1 }
            .firstOrNull()
    }.getOrNull()

    /** System ping (ICMP): /system/bin/ping works for apps without root. */
    override suspend fun icmpPing(host: String, timeoutMs: Int): Long = withContext(Dispatchers.IO) {
        runCatching {
            val p = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "${(timeoutMs / 1000).coerceAtLeast(1)}", host)
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            app.ghostly.core.vpn.Probe.parsePingOutput(out)
        }.getOrDefault(-1L)
    }

    override fun isPortFree(port: Int, listen: String): Boolean = runCatching {
        java.net.ServerSocket().use { it.reuseAddress = false; it.bind(java.net.InetSocketAddress(listen, port)) }
        // 0.0.0.0 can bind next to another app's 127.0.0.1 listener on Windows — check loopback too.
        if (listen == "0.0.0.0") java.net.ServerSocket().use { it.bind(java.net.InetSocketAddress("127.0.0.1", port)) }
        true
    }.getOrDefault(false)

    override suspend fun tcpPing(host: String, port: Int, timeoutMs: Int): Long = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val addr = java.net.InetSocketAddress(host, port) // DNS outside the timer
            if (addr.isUnresolved) return@runCatching -1L
            java.net.Socket().use { s ->
                val t0 = System.nanoTime()
                s.connect(addr, timeoutMs)
                ((System.nanoTime() - t0) / 1_000_000).coerceAtLeast(1)
            }
        }.getOrDefault(-1L)
    }

    /** Underlying network (not our VPN): Wi-Fi vs mobile decides white-list behaviour. */
    override fun networkType(): app.ghostly.core.vpn.NetType = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val nets = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
            .filter {
                it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
            }
        when {
            nets.any { it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) } -> app.ghostly.core.vpn.NetType.WIFI
            nets.any { it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) } -> app.ghostly.core.vpn.NetType.ETHERNET
            nets.any { it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) } -> app.ghostly.core.vpn.NetType.CELLULAR
            else -> app.ghostly.core.vpn.NetType.UNKNOWN
        }
    }.getOrDefault(app.ghostly.core.vpn.NetType.UNKNOWN)

    override fun utcOffsetMinutes(): Int = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

    // The Play build updates only through Google Play (store policy).
    override val updateAsset: String? = if (BuildConfig.BUILD_TYPE == "play") null else when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "Ghostly-Android.apk"
        "armeabi-v7a" -> "Ghostly-Android-armv7.apk"
        else -> "Ghostly-Android-universal.apk"
    }

    override suspend fun downloadVerified(urls: List<String>, sha256: String, size: Long, onProgress: (Float) -> Unit): String =
        downloadVerifiedTo(java.io.File(context.cacheDir, "updates/$updateAsset"), urls, sha256, size, onProgress)

    override fun canAutoInstall(): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    override fun installUpdate(path: String) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            throw IllegalStateException("Разрешите Ghostly устанавливать приложения и нажмите «Обновить» ещё раз")
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.updates", java.io.File(path))
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override suspend fun installedApps(): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.packageName != context.packageName }
            .map { info ->
                AppEntry(
                    packageName = info.packageName,
                    label = info.loadLabel(pm).toString(),
                    isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0 && info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0,
                )
            }
    }
}
