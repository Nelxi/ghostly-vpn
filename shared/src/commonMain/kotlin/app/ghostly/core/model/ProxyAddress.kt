package app.ghostly.core.model

import io.ktor.http.encodeURLParameter

/** Credentials must survive URI punctuation; display masking never changes the copied address. */
fun ExtraProxy.address(host: String, maskPassword: Boolean = false): String {
    val endpoint = if (':' in host && !host.startsWith("[")) "[$host]" else host
    val credentials = if (auth) user.encodeURLParameter() + ":" +
        (if (maskPassword) "••••••" else pass.encodeURLParameter()) + "@" else ""
    return "${type.scheme}://$credentials$endpoint:$port"
}
