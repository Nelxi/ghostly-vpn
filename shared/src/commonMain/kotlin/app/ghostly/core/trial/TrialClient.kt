package app.ghostly.core.trial

import app.ghostly.core.JsonX
import app.ghostly.core.vpn.PlatformInfo
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * Trial over Key Attestation (Android only). Desktop must not call this:
 * Windows/macOS/Linux trial lives in the Telegram bot.
 *
 * Flow: GET challenge -> platform attests (AndroidKeyStore, injected via
 * [attest]) -> POST claim -> import [TrialClaimResponse.subscriptionUrl].
 * Any refusal surfaces as [TrialPolicy.UNAVAILABLE]; the real reason stays
 * in the server log. HWID is sent only as a weak log hint.
 */
class TrialClient(
    private val platform: PlatformInfo,
    private val userAgent: String,
    private val attest: suspend (challenge: TrialChallenge) -> List<String>,
) {
    private val http = HttpClient {
        install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = 7_000 }
        expectSuccess = false
    }

    /** One neutral exception for every refusal path. */
    suspend fun claim(host: String): String {
        if (!TrialPolicy.clientMayTry(platform.os, platform.appVersion)) {
            throw TrialUnavailableException()
        }
        val challenge = fetchChallenge(host) ?: throw TrialUnavailableException()
        val chain = runCatching { attest(challenge) }.getOrNull()
            ?: throw TrialUnavailableException()
        val req = TrialClaimRequest(
            challengeId = challenge.id,
            chainPem = chain,
            appVersion = platform.appVersion,
            hwidHint = platform.hwid,
        )
        return postClaim(host, req) ?: throw TrialUnavailableException()
    }

    private suspend fun fetchChallenge(host: String): TrialChallenge? = runCatching {
        val r = http.get("https://$host/trial/challenge") {
            header("User-Agent", userAgent)
            header("x-device-os", platform.os)
            header("x-ver-os", platform.osVersion)
            header("x-device-model", platform.deviceModel)
            header("x-hwid", platform.hwid)
        }
        if (!r.status.isSuccess()) return null
        JsonX.decodeFromString(TrialChallenge.serializer(), r.bodyAsText())
    }.getOrNull()

    private suspend fun postClaim(host: String, req: TrialClaimRequest): String? = runCatching {
        val r = http.post("https://$host/trial/claim") {
            header("User-Agent", userAgent)
            header("x-device-os", platform.os)
            header("x-ver-os", platform.osVersion)
            header("x-device-model", platform.deviceModel)
            header("x-hwid", platform.hwid)
            contentType(ContentType.Application.Json)
            setBody(JsonX.encodeToString(TrialClaimRequest.serializer(), req))
        }
        if (!r.status.isSuccess()) return null
        val reply = JsonX.decodeFromString(TrialClaimResponse.serializer(), r.bodyAsText())
        if (!reply.ok) return null
        reply.subscriptionUrl?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun close() = http.close()
}
