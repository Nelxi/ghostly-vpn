import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

/**
 * Xray core (AndroidLibXrayLite by 2dust, LGPL-3.0). The aar is ~60 MB, so it is not committed:
 * it is downloaded once into androidApp/libs and verified against the pinned sha256.
 */
abstract class FetchXrayCore : DefaultTask() {
    @get:Input abstract val version: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputFile abstract val target: RegularFileProperty

    @TaskAction
    fun fetch() {
        val file = target.get().asFile
        if (file.isFile && file.sha256() == sha256.get()) return
        file.parentFile.mkdirs()
        val url = "https://github.com/2dust/AndroidLibXrayLite/releases/download/v${version.get()}/libv2ray.aar"
        logger.lifecycle("Downloading Xray core ${version.get()}…")
        val tmp = File(file.parentFile, file.name + ".part")
        URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        val actual = tmp.sha256()
        if (actual != sha256.get()) {
            tmp.delete()
            throw GradleException("libv2ray.aar sha256 mismatch: expected ${sha256.get()}, got $actual")
        }
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }

    private fun File.sha256(): String {
        val md = MessageDigest.getInstance("SHA-256")
        inputStream().use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

val xrayAar = layout.projectDirectory.file("libs/libv2ray.aar")
val fetchXrayCore = tasks.register<FetchXrayCore>("fetchXrayCore") {
    version = libs.versions.xrayCore
    sha256 = libs.versions.xrayCoreSha256
    target = xrayAar
}
tasks.named("preBuild") { dependsOn(fetchXrayCore) }

/**
 * mihomo core (Prizrak-Core through legiz-ru's ClashMetaForAndroid bridge): built from source by
 * tools/android/build-mihomo-core.sh into libs/ (needs Go + NDK; CI does it and caches the result).
 */
val mihomoAars = listOf("libs/mihomo-core.aar", "libs/mihomo-common.aar")

/**
 * Prebuilt core from our own prerelease `mihomo-android-<ref>` (made by .github/workflows/mihomo-core.yml),
 * checked against the SHA-256 pinned in libs.versions.toml — so Windows builds need no Go/NDK and nobody
 * can slip in a different binary. Without pins (a new ref) it falls back to building from source.
 */
abstract class FetchMihomoCore : DefaultTask() {
    @get:Input abstract val ref: Property<String>
    @get:Input abstract val coreSha256: Property<String>
    @get:Input abstract val commonSha256: Property<String>
    @get:Internal abstract val libsDir: DirectoryProperty
    @get:Internal abstract val buildScript: RegularFileProperty

    @TaskAction
    fun fetch() {
        val dir = libsDir.get().asFile.apply { mkdirs() }
        val marker = File(dir, ".mihomo-ref")
        // "none" = no prebuild pinned yet for this ref (a version catalog value can't be empty).
        val pins = mapOf("mihomo-core.aar" to coreSha256.get(), "mihomo-common.aar" to commonSha256.get())
            .mapValues { (_, v) -> if (v == "none") "" else v }
        val upToDate = marker.isFile && marker.readText().trim() == ref.get() &&
            pins.all { (name, sha) -> File(dir, name).let { it.isFile && (sha.isBlank() || it.sha256() == sha) } }
        if (upToDate) return
        if (pins.values.any { it.isBlank() }) {
            logger.lifecycle("mihomo core ${ref.get()} has no pinned prebuild yet — building from source (needs Go + NDK)")
            val p = ProcessBuilder("bash", buildScript.get().asFile.absolutePath).inheritIO().start()
            if (p.waitFor() != 0) throw GradleException(
                "mihomo core build failed. Run the 'mihomo core for Android' workflow on GitHub and pin the SHA-256 " +
                    "of its AARs in gradle/libs.versions.toml (prizrakAndroidCoreSha256 / prizrakAndroidCommonSha256).",
            )
            return
        }
        val tag = "mihomo-android-" + ref.get().take(12)
        for ((name, sha) in pins) {
            val file = File(dir, name)
            if (file.isFile && file.sha256() == sha) continue
            logger.lifecycle("Downloading $name ($tag)…")
            val tmp = File(dir, "$name.part")
            URI("https://github.com/Nelxi/ghostly-vpn/releases/download/$tag/$name").toURL().openStream()
                .use { input -> tmp.outputStream().use { input.copyTo(it) } }
            val actual = tmp.sha256()
            if (actual != sha) {
                tmp.delete()
                throw GradleException("$name sha256 mismatch: expected $sha, got $actual")
            }
            file.delete()
            tmp.renameTo(file)
        }
        marker.writeText(ref.get())
    }

    private fun File.sha256(): String {
        val md = MessageDigest.getInstance("SHA-256")
        inputStream().use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

val buildMihomoCore = tasks.register<FetchMihomoCore>("buildMihomoCore") {
    ref = libs.versions.prizrakAndroid
    coreSha256 = libs.versions.prizrakAndroidCoreSha256
    commonSha256 = libs.versions.prizrakAndroidCommonSha256
    libsDir = layout.projectDirectory.dir("libs")
    buildScript = rootProject.layout.projectDirectory.file("tools/android/build-mihomo-core.sh")
}
tasks.named("preBuild") { dependsOn(buildMihomoCore) }

// Release signing: keystore.properties (never committed) or env vars in CI.
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
fun signing(key: String, env: String): String? = signingProps.getProperty(key) ?: System.getenv(env)

dependencies {
    implementation(projects.shared)
    implementation(files(xrayAar))
    implementation(files(mihomoAars))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.barcode)
}

android {
    namespace = "app.ghostly.vpn"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.ghostly.vpn"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 56
        versionName = "0.3.21"
    }

    signingConfigs {
        create("release") {
            val store = signing("storeFile", "GHOSTLY_KEYSTORE")
            if (store != null) {
                storeFile = rootProject.file(store)
                storePassword = signing("storePassword", "GHOSTLY_KEYSTORE_PASSWORD")
                keyAlias = signing("keyAlias", "GHOSTLY_KEY_ALIAS")
                keyPassword = signing("keyPassword", "GHOSTLY_KEY_PASSWORD")
            }
        }
    }

    // One APK per ABI (the core is ~35 MB of native code per ABI) + a universal one.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Compressed native core: the APK downloads ~2x smaller (it's sideloaded from the site);
            // Android extracts it once on install.
            useLegacyPackaging = true
        }
    }
    buildTypes {
        getByName("release") {
            // No R8 for now: gomobile/Ktor/Compose reflection is easy to break and we can't test every
            // device; the native core dominates the size anyway.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val release = signingConfigs.getByName("release")
            signingConfig = if (release.storeFile != null) release else signingConfigs.getByName("debug")
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
        // Google Play: same app, but no self-updater and no QUERY_ALL_PACKAGES (src/play/AndroidManifest.xml).
        // ./gradlew :androidApp:bundlePlay -> androidApp/build/outputs/bundle/play/androidApp-play.aab
        create("play") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}
