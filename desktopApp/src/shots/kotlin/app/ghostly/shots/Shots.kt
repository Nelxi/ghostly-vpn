package app.ghostly.shots

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.ghostly.core.GhostlyController
import app.ghostly.core.account.CabinetState
import app.ghostly.core.account.CabinetUi
import app.ghostly.core.model.AppSettings
import app.ghostly.core.model.POOL_REG
import app.ghostly.core.model.POOL_WL
import app.ghostly.core.model.Profile
import app.ghostly.core.model.Server
import app.ghostly.core.model.SubscriptionInfo
import app.ghostly.core.model.TrafficPool
import app.ghostly.core.store.FileStore
import app.ghostly.core.vpn.PlatformInfo
import app.ghostly.core.vpn.Traffic
import app.ghostly.core.vpn.VpnBackend
import app.ghostly.core.vpn.VpnState
import app.ghostly.ui.GhostlyApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess

/**
 * Renders the app's screens to PNG files for design review: a fake platform and VPN backend, a seeded
 * Ghostly subscription, the cabinet in preview mode (no network). Not part of the app.
 *
 * `./gradlew :desktopApp:shots [-Ponly=home,cabinet]` → desktopApp/build/shots/<name>.png
 */
private data class Shot(
    val name: String,
    val tab: String,
    val widthDp: Int = 412,
    val heightDp: Int = 915,
    val scale: Float = 2f,
    /** idle / connected / connecting / failed */
    val vpn: String = "idle",
    val cabinet: CabinetUi? = null,
    val desktop: Boolean = false,
    val onboarded: Boolean = true,
    /** Demo spec instead of `tab:<tab>` (e.g. `tabs:1500` steps through the tabs). */
    val demo: String? = null,
    /** Milliseconds to settle before the picture. */
    val settleMs: Long = 2600,
    /** Extra pictures at these milliseconds (a filmstrip of a transition): <name>-<ms>.png. */
    val film: List<Long> = emptyList(),
)

private val SHOTS = listOf(
    Shot("home", "HOME"),
    Shot("home-on", "HOME", vpn = "connected"),
    Shot("home-connecting", "HOME", vpn = "connecting"),
    Shot("home-error", "HOME", vpn = "failed"),
    Shot("servers", "SERVERS"),
    Shot("servers-on", "SERVERS", vpn = "connected"),
    Shot("settings", "SETTINGS"),
    Shot("settings-on", "SETTINGS", vpn = "connected"),
    Shot("cabinet", "ACCOUNT", cabinet = CabinetUi.Ready(Samples.state)),
    Shot("cabinet-on", "ACCOUNT", vpn = "connected", cabinet = CabinetUi.Ready(Samples.state)),
    Shot("cabinet-long", "ACCOUNT", cabinet = CabinetUi.Ready(Samples.state), heightDp = 1500),
    Shot("cabinet-signin", "ACCOUNT", cabinet = CabinetUi.SignedOut(Samples.anon, why = "not_first_device")),
    Shot("cabinet-loading", "ACCOUNT", cabinet = CabinetUi.Loading),
    Shot("onboarding", "HOME", onboarded = false),
    Shot("small", "HOME", widthDp = 360, heightDp = 640, scale = 2f),
    Shot("small-on", "HOME", widthDp = 360, heightDp = 640, scale = 2f, vpn = "connected"),
    // Home → Settings (tabs:1500 switches at 1.5 s): the camera on its way.
    Shot("move", "HOME", demo = "tabs:1500", settleMs = 2900, film = listOf(1500, 1600, 1700, 1800, 1950, 2150, 2400), scale = 1f),
    Shot("move-on", "HOME", vpn = "connected", demo = "tabs:1500", settleMs = 2900, film = listOf(1600, 1800, 2000, 2300), scale = 1f),
    Shot("desktop-home", "HOME", widthDp = 1180, heightDp = 780, scale = 1.25f, desktop = true, vpn = "connected"),
    Shot("desktop-home-off", "HOME", widthDp = 1180, heightDp = 780, scale = 1.25f, desktop = true),
    Shot("desktop-servers", "SERVERS", widthDp = 1180, heightDp = 780, scale = 1.25f, desktop = true, vpn = "connected"),
    Shot("desktop-settings", "SETTINGS", widthDp = 1180, heightDp = 780, scale = 1.25f, desktop = true),
    Shot("desktop-cabinet", "ACCOUNT", widthDp = 1180, heightDp = 900, scale = 1.25f, desktop = true, cabinet = CabinetUi.Ready(Samples.state)),
)

fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/shots").apply { mkdirs() }
    val only = args.drop(1).flatMap { it.split(',') }.filter { it.isNotBlank() }.toSet()
    runBlocking { app.ghostly.i18n.L10n.load("ru") }
    for (shot in SHOTS) {
        if (only.isNotEmpty() && shot.name !in only) continue
        runCatching { render(shot, out) }.onFailure { System.err.println("${shot.name}: ${it.stackTraceToString()}") }
    }
    exitProcess(0)
}

private fun render(shot: Shot, out: File) {
    val dir = Files.createTempDirectory("ghostly-shot-").toFile()
    seed(dir, shot)
    val platform = ShotPlatform(dir.absolutePath, shot.demo ?: ("tab:" + shot.tab), shot.desktop)
    val controller = GhostlyController(platform, ShotBackend(shot.vpn))
    shot.cabinet?.let { controller.cabinet.preview(it) }
    val w = (shot.widthDp * shot.scale).toInt()
    val h = (shot.heightDp * shot.scale).toInt()
    val scene = ImageComposeScene(w, h, Density(shot.scale)) { GhostlyApp(controller) }
    try {
        // Let entrance animations and delayed effects settle: frames on a real clock.
        val t0 = System.nanoTime()
        // A filmstrip keeps every frame rendered inside its window (software rendering of the 3D pages is slow,
        // so fixed instants would mostly be missed).
        val window = if (shot.film.isEmpty()) LongRange.EMPTY else shot.film.first()..shot.film.last()
        while (true) {
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (ms >= shot.settleMs) break
            val frame = scene.render(System.nanoTime() - t0)
            if (ms in window) frame.encodeToData(EncodedImageFormat.PNG)?.let { File(out, "${shot.name}-$ms.png").writeBytes(it.bytes) }
            Thread.sleep(8)
        }
        val image = scene.render(System.nanoTime() - t0)
        val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("no png")
        File(out, "${shot.name}.png").writeBytes(png.bytes)
        println("${shot.name}.png ${w}x$h")
    } finally {
        scene.close()
        dir.deleteRecursively()
    }
}

private fun seed(dir: File, shot: Shot) {
    val store = FileStore(dir.absolutePath)
    store.save(
        "settings.json", AppSettings.serializer(),
        AppSettings(autoUpdateSubs = false, autoCheckUpdates = false, autoInstallUpdates = false, autoConnect = false, stageMode = false, language = "ru"),
    )
    if (!shot.onboarded) return
    val now = System.currentTimeMillis()
    // Each needs an outbound (or a config) to count as runnable on Xray.
    fun ob(protocol: String) = kotlinx.serialization.json.buildJsonObject {
        put("protocol", kotlinx.serialization.json.JsonPrimitive(protocol))
        put("tag", kotlinx.serialization.json.JsonPrimitive("proxy"))
    }
    val servers = listOf(
        Server("srv0", "🔀 Авто (выбор лучшего)", "balancer", transport = "8", config = ob("balancer")),
        Server("srv1", "🇳🇱 ⚡ Быстрый", "vless", "tcp", "reality", "nl.example", 443, outbound = ob("vless")),
        Server("srv2", "🇩🇪 Hysteria2", "hysteria", "hysteria", "tls", "de.example", 443, outbound = ob("hysteria")),
        Server("srv3", "🇫🇮 Обычный", "vless", "grpc", "reality", "fi.example", 443, outbound = ob("vless")),
        Server("srv4", "🇳🇱 LTE · ⚡ Быстрый", "vless", "xhttp", "tls", "lte.example", 443, outbound = ob("vless"), pool = POOL_WL),
        Server("srv5", "🇩🇪 LTE · Hysteria2", "hysteria", "hysteria", "tls", "lte2.example", 443, outbound = ob("hysteria"), pool = POOL_WL),
    )
    val gb = 1_073_741_824L
    val profile = Profile(
        id = "p1", name = "Ghostly VPN👻", url = "https://ghostlynex.fun/sub/sub_demo0000000000a1",
        info = SubscriptionInfo(
            upload = 2 * gb, download = 9 * gb, total = 50 * gb, expire = now / 1000 + 12 * 86_400,
            pools = listOf(TrafficPool(POOL_REG, 9 * gb, 40 * gb, 10), TrafficPool(POOL_WL, 2 * gb, 10 * gb, 20)),
            cycleEnd = now / 1000 + 12 * 86_400,
        ),
        supportUrl = "https://t.me/GhostlySupportBot?start=support",
        renewUrl = "https://t.me/GhostlyVPNruBot?start=renew",
        updatedAt = now - 7 * 60_000, servers = servers,
    )
    store.save("profiles.json", ListSerializer(Profile.serializer()), listOf(profile))
}

private class ShotPlatform(override val dataDir: String, override val demo: String?, private val desktop: Boolean) : PlatformInfo {
    override val os = if (desktop) "Windows" else "Android"
    override val osVersion = if (desktop) "11" else "16"
    override val deviceModel = if (desktop) "Desktop" else "Nothing A059"
    override val hwid = "shot-device-0001"
    override val appVersion = "0.3.36"
    override val isDesktop: Boolean get() = desktop
    override fun openUrl(url: String) {}
    override fun copyToClipboard(text: String) {}
    override fun readClipboard(): String? = null
}

private class ShotBackend(vpn: String) : VpnBackend {
    override val state = MutableStateFlow<VpnState>(
        when (vpn) {
            "connected" -> VpnState.Connected(System.currentTimeMillis() - 754_000, "srv1")
            "connecting" -> VpnState.Connecting
            "failed" -> VpnState.Failed("Сервер не ответил за 10 секунд: проверь интернет или выбери другой сервер")
            else -> VpnState.Idle
        },
    )
    override val traffic = MutableStateFlow(if (vpn == "connected") Traffic(180_000, 2_400_000, 34_000_000, 812_000_000) else Traffic())
    override suspend fun connect(server: Server, settings: AppSettings) {}
    override suspend fun disconnect() {}
    override suspend fun ping(server: Server, url: String): Long = 84
    override fun coreVersion(): String = "26.9.9"
}

private object Samples {
    private val now = System.currentTimeMillis()
    private fun json(s: String) = app.ghostly.core.JsonX.decodeFromString(CabinetState.serializer(), s)

    val anon: CabinetState = json(
        """{"ok":true,"auth":false,"trial":true,"plans":[{"id":"week_1","title":"1 Неделя","days":7,"amount":49},{"id":"month_1","title":"1 Месяц","days":30,"amount":129},
        {"id":"month_3","title":"3 Месяца","days":90,"amount":329},{"id":"month_6","title":"6 Месяцев","days":180,"amount":590},{"id":"month_12","title":"12 Месяцев","days":365,"amount":990}],
        "popular":"month_3","prices":{"day":20,"gb":3,"gb_month_max":10,"device":30,"devices_max":10},"topup":{"min":10,"max":100000}}""",
    )

    val state: CabinetState = json(
        """{"ok":true,"auth":true,"trial":false,
        "prices":{"day":20,"gb":3,"gb_month_max":10,"device":30,"devices_max":10},
        "topup":{"min":10,"max":100000},
        "account":{"id":"w_demo","login":"nelxi","guest":false,"totp":false,"created":1759000000,"tg":{"username":"listopaddollar","since":1759100000}},
        "bot":"GhostlyVPNruBot","balance":240,
        "history":[{"delta":-140,"type":"extend_days","note":"extend +7d","ts":${now / 1000 - 3600}},{"delta":500,"type":"web_topup","note":"site topup","ts":${now / 1000 - 86400 * 2}},{"delta":39,"type":"referral_bonus","note":"","ts":${now / 1000 - 86400 * 5}},{"delta":-129,"type":"order_balance","note":"","ts":${now / 1000 - 86400 * 9}},{"delta":100,"type":"web_topup","note":"","ts":${now / 1000 - 86400 * 20}}],
        "subs":[{"id":"sub_demo0000000000a1","plan":"3 Месяца","plan_id":"month_3","expiry":${now + 12L * 86_400_000},"active":true,"traffic_gb":50,"used":11811160064,
          "devices":3,"url":"https://ghostlynex.fun/sub/sub_demo0000000000a1","page":"https://ghostlynex.fun/sub/sub_demo0000000000a1",
          "pools":{"cycle_end":${now + 12L * 86_400_000},"unlimited":false,
            "reg":{"title":"Обычные серверы","used":9663676416,"limit_gb":40,"topup_left":10,"price":3,"blocked":false},
            "wl":{"title":"Белые списки","used":2147483648,"limit_gb":10,"topup_left":20,"price":5,"blocked":false}},
          "hw":[{"id":"6c2f0f1d6d33","model":"Nothing A059","os":"Android 16","ago":"5 минут назад","active":true},{"id":"aa11bb22cc33","model":"Windows PC","os":"Windows 11","ago":"2 часа назад","active":true}],"hwb":[],"bot":true}],
        "orders":[],
        "ref":{"code":"GH0ST7","link":"https://ghostlynex.fun/?ref=GH0ST7","count":3,"earned":117,"percent":30,"max":300},
        "gb_left":10,"sessions":[{"id":"0123456789","ua":"GhostlyVPN/0.3.36 (Android 16; Nothing A059)","ip":"95.24.13.7","last":${now / 1000},"current":true},{"id":"9876543210","ua":"Mozilla/5.0 (Windows NT 10.0) Chrome/141","ip":"95.24.13.7","last":${now / 1000 - 86400}}],
        "plans":[{"id":"week_1","title":"1 Неделя","days":7,"amount":49},{"id":"month_1","title":"1 Месяц","days":30,"amount":129},{"id":"month_3","title":"3 Месяца","days":90,"amount":329},{"id":"month_6","title":"6 Месяцев","days":180,"amount":590},{"id":"month_12","title":"12 Месяцев","days":365,"amount":990}],
        "popular":"month_3","scope":"full","bridge":true,"bot_state":{"prices":{},"discount_left":0,"pending":null}}""",
    )
}
