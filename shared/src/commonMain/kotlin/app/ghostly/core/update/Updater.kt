package app.ghostly.core.update

import app.ghostly.core.JsonX
import app.ghostly.core.vpn.PlatformInfo
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
data class ReleaseFile(
    val size: Long = 0,
    val sha256: String,
    /**
     * Version of this very file. A release that changes one platform only reuses the other one's
     * previous build, which still reports its own version — comparing against the release number
     * would offer that same file forever. Absent in manifests before 0.2.7 → the release version.
     */
    val version: String? = null,
)

/** `/dl/latest.json`, published next to every release. */
@Serializable
data class ReleaseManifest(val version: String, val files: Map<String, ReleaseFile> = emptyMap(), val github: String? = null)

data class UpdateOffer(val version: String, val file: String, val sha256: String, val size: Long)

sealed interface UpdateStep {
    data object Idle : UpdateStep
    data class Downloading(val progress: Float) : UpdateStep
    data object Verifying : UpdateStep
    data object Installing : UpdateStep
    data class Failed(val message: String) : UpdateStep
}

/**
 * Checks our server (both domains, then GitHub) for a newer build of this platform's file,
 * downloads it and only hands it to the installer after the SHA-256 matches the manifest.
 */
class Updater(private val platform: PlatformInfo, private val isDismissed: (String) -> Boolean) {

    private val http = HttpClient {
        install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = 8_000 }
        expectSuccess = false
    }

    private val _offer = MutableStateFlow<UpdateOffer?>(null)
    val offer: StateFlow<UpdateOffer?> = _offer.asStateFlow()

    private val _step = MutableStateFlow<UpdateStep>(UpdateStep.Idle)
    val step: StateFlow<UpdateStep> = _step.asStateFlow()

    private val _lastCheck = MutableStateFlow<String?>(null)
    /** Human-readable result of the latest check, shown in "О приложении" (so it's visible whether updates work). */
    val lastCheck: StateFlow<String?> = _lastCheck.asStateFlow()

    /** @return the offer if a newer version exists (ignores the user's "✕" unless [force]). */
    suspend fun check(force: Boolean = false): UpdateOffer? {
        val asset = platform.updateAsset ?: run {
            _lastCheck.value = "Эта сборка не обновляется сама (портативная или из исходников)"
            return null
        }
        // Ask every mirror at once and trust the newest answer: a CDN edge can hold a stale manifest.
        val manifest = kotlinx.coroutines.coroutineScope {
            MANIFESTS.map { url ->
                async {
                    runCatching {
                        val r = http.get(url) { header("User-Agent", "GhostlyVPN/${platform.appVersion} (${platform.os})") }
                        if (r.status.isSuccess()) JsonX.decodeFromString(ReleaseManifest.serializer(), r.bodyAsText()) else null
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull().maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
        } ?: run {
            _lastCheck.value = "${stamp()}: сервер обновлений недоступен"
            return null
        }
        val file = manifest.files[asset] ?: run {
            _lastCheck.value = "${stamp()}: в версии ${manifest.version} нет файла $asset"
            return null
        }
        val latest = file.version ?: manifest.version
        _lastCheck.value = "${stamp()}: на сервере $latest, у тебя ${platform.appVersion}" +
            if (compareVersions(latest, platform.appVersion) > 0) " — есть обновление" else " — всё свежее"
        val newer = compareVersions(latest, platform.appVersion) > 0
        // offer.version also picks the download folder (/dl/<version>/, GitHub v<version>): a reused file lives in its own.
        val offer = if (newer && (force || !isDismissed(latest))) UpdateOffer(latest, asset, file.sha256, file.size) else null
        _offer.value = offer
        return offer
    }

    private fun stamp(): String {
        val m = (kotlin.time.Clock.System.now().toEpochMilliseconds() / 60_000 + platform.utcOffsetMinutes()) % (24 * 60)
        return "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
    }

    fun hide() {
        _offer.value = null
    }

    /** Failed downloads/verifications per version, this run: auto-updates give up after two. */
    private val failures = HashMap<String, Int>()

    /** True when [version] already failed twice: the background updater stops retrying it (no 98% -> 1% loop). */
    fun gaveUp(version: String): Boolean = (failures[version] ?: 0) >= 2

    suspend fun install(offer: UpdateOffer) {
        if (_step.value is UpdateStep.Downloading || _step.value is UpdateStep.Verifying) return
        _step.value = UpdateStep.Downloading(0f)
        // Versioned folders first: a URL that never changes can't be served stale by a cache.
        val urls = SERVERS.map { it + offer.version + "/" + offer.file } + MIRRORS.map { it + offer.file } +
            "https://github.com/Nelxi/ghostly-vpn/releases/download/v${offer.version}/${offer.file}"
        val path = try {
            platform.downloadVerified(urls, offer.sha256, offer.size) { p ->
                _step.value = if (p >= 1f) UpdateStep.Verifying else UpdateStep.Downloading(p)
            }
        } catch (e: Exception) {
            failures[offer.version] = (failures[offer.version] ?: 0) + 1
            _step.value = UpdateStep.Failed(e.message ?: "Не удалось скачать обновление")
            return
        }
        _step.value = UpdateStep.Installing
        runCatching { platform.installUpdate(path) }
            .onFailure { _step.value = UpdateStep.Failed(it.message ?: "Не удалось запустить установку") }
            .onSuccess {
                // Android hands the APK to the system installer and stays alive: if the user cancels it,
                // the banner must come back to "Обновить" instead of hanging on "Запускаю установку".
                // (Desktop quits for the installer before this fires.)
                kotlinx.coroutines.delay(6_000)
                if (_step.value == UpdateStep.Installing) _step.value = UpdateStep.Idle
            }
    }

    fun reset() {
        _step.value = UpdateStep.Idle
    }

    companion object {
        private val MANIFESTS = listOf(
            "https://ghostlinknex.online/dl/latest.json",
            "https://srv.ghostlinknex.online/dl/latest.json",
            "https://ghostlynex.fun/dl/latest.json",
        )
        private val SERVERS = listOf(
            "https://srv.ghostlinknex.online/dl/",
            "https://ghostlinknex.online/dl/",
            "https://ghostlynex.fun/dl/",
        )
        private val MIRRORS = listOf(
            "https://ghostlinknex.online/dl/",
            "https://srv.ghostlinknex.online/dl/",
            "https://ghostlynex.fun/dl/",
            "https://github.com/Nelxi/ghostly-vpn/releases/latest/download/",
        )

        /** "0.1.10" > "0.1.9"; suffixes like "-dev" are ignored. */
        fun compareVersions(a: String, b: String): Int {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val x = parts(a)
            val y = parts(b)
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
                if (d != 0) return d
            }
            return 0
        }
    }
}
