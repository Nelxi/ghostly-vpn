import java.net.URI
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.shared)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.compose.material3)
    implementation(libs.compose.components.resources)
    implementation(libs.jna.platform)
    // Now playing (Windows SMTC): the same small library the Kasane media bar uses.
}

kotlin {
    jvmToolchain(21)
}

/**
 * Xray core for the host OS (XTLS/Xray-core release zip), unpacked into core/<os>-<arch>/ which is
 * Compose's per-platform app-resources layout. CI builds every OS on its own runner.
 */
abstract class FetchXrayDesktop : DefaultTask() {
    @get:Input abstract val version: Property<String>
    @get:Input abstract val asset: Property<String>
    @get:OutputDirectory abstract val target: DirectoryProperty

    @TaskAction
    fun fetch() {
        val dir = target.get().asFile
        val marker = File(dir, ".version")
        if (marker.isFile && marker.readText() == version.get() + "/" + asset.get()) return
        dir.deleteRecursively()
        dir.mkdirs()
        val url = "https://github.com/XTLS/Xray-core/releases/download/v${version.get()}/${asset.get()}"
        logger.lifecycle("Downloading $url")
        val keep = setOf("xray", "xray.exe", "wintun.dll", "geoip.dat", "geosite.dat", "LICENSE", "LICENSE-Wintun")
        ZipInputStream(URI(url).toURL().openStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory || e.name.substringAfterLast('/') !in keep) continue
                val out = File(dir, e.name.substringAfterLast('/'))
                out.outputStream().use { zip.copyTo(it) }
                if (out.name == "xray") out.setExecutable(true, false)
            }
        }
        marker.writeText(version.get() + "/" + asset.get())
    }
}

val hostOs: String = System.getProperty("os.name").lowercase().let {
    when {
        "win" in it -> "windows"
        "mac" in it -> "macos"
        else -> "linux"
    }
}
val hostArch: String = System.getProperty("os.arch").lowercase().let { if (it == "aarch64" || it == "arm64") "arm64" else "x64" }
val xrayAsset = when (hostOs) {
    "windows" -> "Xray-windows-64.zip"
    "macos" -> if (hostArch == "arm64") "Xray-macos-arm64-v8a.zip" else "Xray-macos-64.zip"
    else -> if (hostArch == "arm64") "Xray-linux-arm64-v8a.zip" else "Xray-linux-64.zip"
}

val fetchXrayCore = tasks.register<FetchXrayDesktop>("fetchXrayCore") {
    version = libs.versions.xrayDesktop
    asset = xrayAsset
    target = layout.projectDirectory.dir("core/$hostOs-$hostArch")
}
/**
 * mihomo core (legiz-ru/Prizrak-Core) next to Xray in the same core/<os>-<arch>/ folder, as
 * prizrak-core(.exe). Windows ships a zip with one exe, the others a gzipped binary.
 */
abstract class FetchMihomoDesktop : DefaultTask() {
    @get:Input abstract val version: Property<String>
    @get:Input abstract val asset: Property<String>
    @get:Input abstract val exeName: Property<String>
    /** SHA-256 of the release archive; the download is rejected on mismatch. */
    @get:Input abstract val sha256: Property<String>
    @get:Internal abstract val target: DirectoryProperty

    @TaskAction
    fun fetch() {
        val dir = target.get().asFile.apply { mkdirs() }
        val out = File(dir, exeName.get())
        val marker = File(dir, ".mihomo-version")
        if (out.isFile && marker.isFile && marker.readText() == version.get() + "/" + asset.get()) return
        val url = "https://github.com/legiz-ru/Prizrak-Core/releases/download/v${version.get()}/${asset.get()}"
        logger.lifecycle("Downloading $url")
        val tmp = File(dir, out.name + ".part")
        val archive = File(dir, asset.get() + ".part")
        URI(url).toURL().openStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
        val md = MessageDigest.getInstance("SHA-256")
        archive.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        if (actual != sha256.get()) {
            archive.delete()
            throw GradleException("${asset.get()} sha256 mismatch: expected ${sha256.get()}, got $actual")
        }
        archive.inputStream().use { input ->
            if (asset.get().endsWith(".zip")) {
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: throw GradleException("no executable in ${asset.get()}")
                        if (!e.isDirectory && e.name.endsWith(".exe")) {
                            tmp.outputStream().use { zip.copyTo(it) }
                            break
                        }
                    }
                }
            } else {
                GZIPInputStream(input).use { gz -> tmp.outputStream().use { gz.copyTo(it) } }
            }
            Unit
        }
        archive.delete()
        if (out.exists()) out.delete()
        tmp.renameTo(out)
        out.setExecutable(true, false)
        marker.writeText(version.get() + "/" + asset.get())
    }
}

val mihomoAsset = "prizrak-core-" + when (hostOs) {
    "windows" -> if (hostArch == "arm64") "windows-arm64" else "windows-amd64-v1"
    "macos" -> if (hostArch == "arm64") "darwin-arm64" else "darwin-amd64-v1"
    else -> if (hostArch == "arm64") "linux-arm64" else "linux-amd64-v1"
} + "-v" + libs.versions.prizrakCore.get() + if (hostOs == "windows") ".zip" else ".gz"

// Prizrak-Core 1.19.31 release archives, hashed by us when the core was pinned.
val mihomoSha256 = mapOf(
    "prizrak-core-windows-amd64-v1-v1.19.31.zip" to "4d13e5e2197cfb03d4c55e244fcac04d6d2922fbb821c571fb374c9c756b5b53",
    "prizrak-core-windows-arm64-v1.19.31.zip" to "f20c95bd9cf9bc70673d4ceb60fd88a1aea278e92b4ddf9d800891296e4a63bc",
    "prizrak-core-darwin-arm64-v1.19.31.gz" to "2484629ac58b741b9cea94bda0a349cf295582115301fb8e333fb0b3805d5fbc",
    "prizrak-core-darwin-amd64-v1-v1.19.31.gz" to "3537cb6463ffb868d0f53e01204d265285bb2409d573d63651f3fde901d15101",
    "prizrak-core-linux-arm64-v1.19.31.gz" to "83511d226008214476f40ac86df5800665d30fc2b8b7815e0255a843f7d6fa98",
    "prizrak-core-linux-amd64-v1-v1.19.31.gz" to "4868b5316480d214aa0b1a255ae1914c2633083dc847edfd9b047ceec2c6fc0d",
)

val fetchMihomoCore = tasks.register<FetchMihomoDesktop>("fetchMihomoCore") {
    version = libs.versions.prizrakCore
    asset = mihomoAsset
    sha256 = mihomoSha256[mihomoAsset] ?: error("No pinned SHA-256 for $mihomoAsset — hash the new Prizrak-Core release and add it")
    exeName = if (hostOs == "windows") "prizrak-core.exe" else "prizrak-core"
    target = layout.projectDirectory.dir("core/$hostOs-$hostArch")
    // Xray's task wipes the folder when its version changes, so it goes first.
    mustRunAfter(fetchXrayCore)
    outputs.upToDateWhen { false }
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(fetchXrayCore, fetchMihomoCore) }

/** The one place to bump the desktop version (Windows/Linux use it as is, macOS as 1.x.y). */
val ghostlyVersion = "0.3.25"

compose.desktop {
    application {
        mainClass = "app.ghostly.desktop.MainKt"
        jvmArgs += listOf("-Dsun.java2d.uiScale.enabled=true", "-Xmx512m")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Ghostly"
            packageVersion = ghostlyVersion
            description = "Ghostly VPN"
            vendor = "Ghostly"
            copyright = "GPL-3.0"
            appResourcesRootDir = layout.projectDirectory.dir("core")
            modules("java.naming", "jdk.crypto.ec", "java.net.http", "jdk.unsupported", "java.instrument", "java.management")

            windows {
                menuGroup = "Ghostly"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6b0d7f3e-2f5c-4f3c-9d44-6a0c5a8f1e21"
                iconFile = project.file("icons/ghostly.ico")
            }
            macOS {
                // jpackage on macOS refuses a version starting with 0 ("0.1.8"): the bundle carries 1.x.y.
                packageVersion = "1." + ghostlyVersion.substringAfter('.')
                dmgPackageVersion = "1." + ghostlyVersion.substringAfter('.')
                bundleID = "app.ghostly.desktop"
                iconFile = project.file("icons/ghostly.icns")
            }
            linux {
                iconFile = project.file("icons/ghostly.png")
                packageName = "ghostly"
            }
        }
    }
}
