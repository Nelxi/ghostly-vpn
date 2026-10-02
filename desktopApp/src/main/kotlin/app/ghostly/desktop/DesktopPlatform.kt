package app.ghostly.desktop

import app.ghostly.core.vpn.PlatformInfo
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import java.security.MessageDigest

enum class HostOs { WINDOWS, MACOS, LINUX }

val hostOs: HostOs = System.getProperty("os.name").lowercase().let {
    when {
        "win" in it -> HostOs.WINDOWS
        "mac" in it -> HostOs.MACOS
        else -> HostOs.LINUX
    }
}

class DesktopPlatform : PlatformInfo {
    /** Folder of Ghostly.exe; a file named `portable` next to it switches on portable mode. */
    private val exeDir: File? = ProcessHandle.current().info().command().orElse(null)?.let { File(it).parentFile }

    /** Portable build: data lives next to the exe, nothing is written to the system (registry, autostart, URL scheme). */
    val portable: Boolean = exeDir?.let { File(it, "portable").isFile } == true

    override val os: String = when (hostOs) {
        HostOs.WINDOWS -> "Windows"
        HostOs.MACOS -> "macOS"
        HostOs.LINUX -> "Linux"
    }
    override val osVersion: String = System.getProperty("os.version")
    override val deviceModel: String = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("PC")
    override val appVersion: String = System.getProperty("jpackage.app-version") ?: "0.1.5-dev"
    override val isDesktop = true

    /** A PC sits on a fixed line (or Wi-Fi): treat it as Wi-Fi for white-list decisions. */
    override fun networkType(): app.ghostly.core.vpn.NetType = app.ghostly.core.vpn.NetType.WIFI

    /** Installed Windows build updates itself; dev runs (java.exe) and other OSes don't. */
    override fun utcOffsetMinutes(): Int = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

    override val updateAsset: String? = run {
        val exe = ProcessHandle.current().info().command().orElse("")
        if (hostOs == HostOs.WINDOWS && exe.endsWith("Ghostly.exe", ignoreCase = true) && !portable) "Ghostly-Windows.exe" else null
    }

    /** Music on this PC for stage mode (Windows only for now). */
    override val stage: app.ghostly.core.stage.StageSource? by lazy { if (hostOs == HostOs.WINDOWS) DesktopStage(dataDir) else null }

    /** Wired by main() once the backend exists (needs the core path and elevation check). */
    override var killSwitch: app.ghostly.core.vpn.KillSwitch? = null

    /** Set by main(): disconnect (restores the system proxy) and exit so the installer can replace files. */
    var quitForUpdate: (() -> Unit)? = null

    override suspend fun downloadVerified(urls: List<String>, sha256: String, size: Long, onProgress: (Float) -> Unit): String =
        downloadVerifiedTo(File(System.getProperty("java.io.tmpdir"), "ghostly-update/Ghostly-Windows.exe"), urls, sha256, size, onProgress)

    override fun clearDownloadedUpdate() {
        File(System.getProperty("java.io.tmpdir"), "ghostly-update").deleteRecursively()
    }

    override fun installUpdate(path: String) {
        // Silent Inno Setup over the current install; its [Run] entry starts Ghostly again when done.
        // It must not start while we are still closing: the JVM holds its jars and the core is still
        // running, Inno finds files in use and (message boxes suppressed) cancels — no update, no relaunch.
        // So a hidden helper waits for this process and our cores to be gone, then runs the installer.
        val dir = exeDir ?: return run {
            ProcessBuilder(path, "/SILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/CLOSEAPPLICATIONS").start()
            quitForUpdate?.invoke()
        }
        val log = File(dataDir, "run/update-install.log").apply { parentFile.mkdirs() }
        val script = updateHandoffScript(ProcessHandle.current().pid(), dir.absolutePath, path, log.absolutePath)
        val encoded = java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        quitForUpdate?.invoke()
    }

    override val dataDir: String = if (portable) File(exeDir, "data").apply { mkdirs() }.absolutePath else when (hostOs) {
        HostOs.WINDOWS -> File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "Ghostly")
        HostOs.MACOS -> File(System.getProperty("user.home"), "Library/Application Support/Ghostly")
        HostOs.LINUX -> File(System.getenv("XDG_DATA_HOME") ?: (System.getProperty("user.home") + "/.local/share"), "ghostly")
    }.apply { mkdirs() }.absolutePath

    /** Stable machine id (MachineGuid / IOPlatformUUID / machine-id), hashed so the raw id never leaves the PC. */
    override val hwid: String by lazy {
        val raw = runCatching {
            when (hostOs) {
                HostOs.WINDOWS -> exec("reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid")
                    .lineSequence().firstOrNull { "MachineGuid" in it }?.trim()?.split(Regex("\\s+"))?.lastOrNull()
                HostOs.MACOS -> exec("ioreg", "-rd1", "-c", "IOPlatformExpertDevice")
                    .lineSequence().firstOrNull { "IOPlatformUUID" in it }?.substringAfterLast("= ")?.trim('"', ' ')
                HostOs.LINUX -> File("/etc/machine-id").takeIf { it.isFile }?.readText()?.trim()
            }
        }.getOrNull() ?: (System.getProperty("user.name") + deviceModel)
        MessageDigest.getInstance("SHA-256").digest(("ghostly:" + raw).toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
    }

    override fun openUrl(url: String) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }.onFailure {
            when (hostOs) {
                HostOs.LINUX -> ProcessBuilder("xdg-open", url).start()
                HostOs.MACOS -> ProcessBuilder("open", url).start()
                HostOs.WINDOWS -> ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start()
            }
        }
    }

    override fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

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

    override fun isPortFree(port: Int, listen: String): Boolean = runCatching {
        java.net.ServerSocket().use { it.reuseAddress = false; it.bind(java.net.InetSocketAddress(listen, port)) }
        // 0.0.0.0 can bind next to another app's 127.0.0.1 listener on Windows — check loopback too.
        if (listen == "0.0.0.0") java.net.ServerSocket().use { it.bind(java.net.InetSocketAddress("127.0.0.1", port)) }
        true
    }.getOrDefault(false)

    // Decoded once; every play opens its own Clip that closes itself at the end, so sounds overlap like
    // on the site instead of cutting each other off. Several takes per action, picked at random.
    private class Pcm(val format: javax.sound.sampled.AudioFormat, val bytes: ByteArray)

    private val pcm: Map<String, List<Pcm>> by lazy {
        mapOf("click" to 3, "press" to 4, "success" to 4, "error" to 2, "hover" to 5).mapValues { (name, n) ->
            (0 until n).mapNotNull { i ->
                runCatching {
                    val res = DesktopPlatform::class.java.getResourceAsStream("/sounds/ui_${name}_$i.wav") ?: return@runCatching null
                    javax.sound.sampled.AudioSystem.getAudioInputStream(java.io.BufferedInputStream(res)).use { Pcm(it.format, it.readAllBytes()) }
                }.getOrNull()
            }
        }
    }
    private val playing = java.util.concurrent.atomic.AtomicInteger()

    private fun play(name: String, volume: Float) {
        val take = pcm[name]?.randomOrNull() ?: return
        if (volume <= 0.001f || playing.get() >= 12) return
        Thread {
            runCatching {
                val clip = javax.sound.sampled.AudioSystem.getClip()
                clip.open(take.format, take.bytes, 0, take.bytes.size)
                (clip.getControl(javax.sound.sampled.FloatControl.Type.MASTER_GAIN) as? javax.sound.sampled.FloatControl)?.let { g ->
                    g.value = (20f * kotlin.math.log10(volume.coerceIn(0.001f, 1f))).coerceIn(g.minimum, g.maximum)
                }
                playing.incrementAndGet()
                clip.addLineListener { e -> if (e.type == javax.sound.sampled.LineEvent.Type.STOP) { clip.close(); playing.decrementAndGet() } }
                clip.start()
            }
        }.apply { isDaemon = true }.start()
    }

    override fun playHover(volume: Float) = play("hover", volume)

    override fun playSound(kind: app.ghostly.core.vpn.Haptic, volume: Float) = play(
        when (kind) {
            app.ghostly.core.vpn.Haptic.HEAVY -> "press"
            app.ghostly.core.vpn.Haptic.SUCCESS -> "success"
            app.ghostly.core.vpn.Haptic.ERROR -> "error"
            else -> "click"
        },
        volume,
    )

    /** Windows accent colour (Settings → Personalisation → Colours), the desktop's "Monet". */
    private val winAccent: Long? by lazy {
        if (hostOs != HostOs.WINDOWS) return@lazy null
        runCatching {
            val out = ProcessBuilder("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\DWM", "/v", "AccentColor")
                .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
            val abgr = Regex("0x([0-9a-fA-F]+)").find(out)!!.groupValues[1].toLong(16)
            val r = abgr and 0xFF; val g = (abgr shr 8) and 0xFF; val b = (abgr shr 16) and 0xFF
            // Lift dark accents toward white so they read on the dark UI, like Monet's light tone.
            fun lift(x: Long) = (x + (255 - x) * 0.35).toLong()
            0xFF000000L or (lift(r) shl 16) or (lift(g) shl 8) or lift(b)
        }.getOrNull()
    }

    override fun systemAccent(): Long? = winAccent

    override val demo: String? = System.getenv("GHOSTLY_DEMO")?.takeIf { it.isNotBlank() }

    /** Direct sockets; the controller skips it while a TUN tunnel would catch them. */
    override suspend fun blockCheck(target: app.ghostly.core.vpn.BlockTarget) = app.ghostly.core.vpn.JvmBlockCheck.run(target)

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

    /** One system ping (ICMP): works without admin rights, unlike Java's isReachable on Windows. */
    override suspend fun icmpPing(host: String, timeoutMs: Int): Long = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val cmd = when (hostOs) {
                HostOs.WINDOWS -> listOf("ping", "-n", "1", "-w", "$timeoutMs", host)
                HostOs.MACOS -> listOf("ping", "-c", "1", "-t", "${(timeoutMs / 1000).coerceAtLeast(1)}", host)
                HostOs.LINUX -> listOf("ping", "-c", "1", "-W", "${(timeoutMs / 1000).coerceAtLeast(1)}", host)
            }
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            // Windows prints in the OEM code page (cp866 for Russian).
            val out = p.inputStream.readAllBytes().toString(if (hostOs == HostOs.WINDOWS) charset("CP866") else Charsets.UTF_8)
            if (!p.waitFor(timeoutMs + 2000L, java.util.concurrent.TimeUnit.MILLISECONDS)) p.destroyForcibly()
            app.ghostly.core.vpn.Probe.parsePingOutput(out)
        }.getOrDefault(-1L)
    }

    /** Autostart entry: HKCU Run key / LaunchAgent / XDG autostart, launching minimized to tray. */
    override fun setStartOnBoot(enabled: Boolean) {
        val exe = ProcessHandle.current().info().command().orElse(null) ?: return
        if (portable && enabled) return
        // Only meaningful for the installed app, not for `gradlew run`.
        if (exe.endsWith("java.exe") || exe.endsWith("/java")) return
        runCatching {
            when (hostOs) {
                HostOs.WINDOWS -> {
                    val key = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
                    if (enabled) com.sun.jna.platform.win32.Advapi32Util.registrySetStringValue(
                        com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER, key, "Ghostly", "\"$exe\" --autostart",
                    )
                    else if (com.sun.jna.platform.win32.Advapi32Util.registryValueExists(com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER, key, "Ghostly"))
                        com.sun.jna.platform.win32.Advapi32Util.registryDeleteValue(com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER, key, "Ghostly")
                }
                HostOs.MACOS -> {
                    val plist = File(System.getProperty("user.home"), "Library/LaunchAgents/app.ghostly.desktop.plist")
                    if (enabled) plist.apply { parentFile.mkdirs() }.writeText(
                        """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key><string>app.ghostly.desktop</string>
<key>ProgramArguments</key><array><string>$exe</string><string>--autostart</string></array>
<key>RunAtLoad</key><true/>
</dict></plist>
""",
                    ) else plist.delete()
                }
                HostOs.LINUX -> {
                    val f = File(System.getProperty("user.home"), ".config/autostart/ghostly.desktop")
                    if (enabled) f.apply { parentFile.mkdirs() }.writeText(
                        "[Desktop Entry]\nType=Application\nName=Ghostly\nExec=\"$exe\" --autostart\nX-GNOME-Autostart-enabled=true\n",
                    )
                    else f.delete()
                }
            }
        }
    }

    override fun readClipboard(): String? = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
    }.getOrNull()
}

internal fun exec(vararg cmd: String): String {
    val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor()
    return out
}

/**
 * PowerShell for the update hand-off: wait until Ghostly ([pid]) has exited and nothing runs from
 * [appDir] any more (cores, helpers; stragglers are stopped after 20 s), then run the installer. A
 * failed attempt is retried once; if both fail, the old Ghostly is started again so the user is never
 * left without the app.
 */
internal fun updateHandoffScript(pid: Long, appDir: String, installer: String, log: String): String {
    fun q(s: String) = "'" + s.replace("'", "''") + "'"
    val app = q(appDir.trimEnd('\\', '/'))
    return """
        ${'$'}ErrorActionPreference = 'SilentlyContinue'
        ${'$'}app = $app
        ${'$'}setup = ${q(installer)}
        ${'$'}log = ${q(log)}
        function Ours { Get-Process | Where-Object { ${'$'}_.Path -and ${'$'}_.Path.StartsWith(${'$'}app + '\', [StringComparison]::OrdinalIgnoreCase) } }
        Wait-Process -Id $pid -Timeout 60
        ${'$'}deadline = (Get-Date).AddSeconds(20)
        while ((Ours) -and (Get-Date) -lt ${'$'}deadline) { Start-Sleep -Milliseconds 300 }
        Ours | Stop-Process -Force
        Start-Sleep -Milliseconds 700
        for (${'$'}i = 0; ${'$'}i -lt 2; ${'$'}i++) {
            ${'$'}p = Start-Process -FilePath ${'$'}setup -ArgumentList @('/SILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/CLOSEAPPLICATIONS', ('/LOG="' + ${'$'}log + '"')) -PassThru -Wait
            if (${'$'}p -and ${'$'}p.ExitCode -eq 0) { exit 0 }
            Start-Sleep -Seconds 3
            Ours | Stop-Process -Force
        }
        Start-Process -FilePath (Join-Path ${'$'}app 'Ghostly.exe')
    """.trimIndent()
}
