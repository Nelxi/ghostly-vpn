package app.ghostly.core.mihomo

import app.ghostly.core.model.CoreType
import app.ghostly.core.model.Profile
import app.ghostly.core.model.ProfileContent
import app.ghostly.core.model.Server
import kotlinx.serialization.json.JsonObject

/**
 * One core at a time: with Xray only what Xray runs is shown and used, with mihomo only what mihomo
 * runs. Clash/mihomo profiles are mihomo-only, full Xray-JSON configs are Xray-only, plain links
 * go with the chosen core. Returns null when nothing of the profile fits.
 */
fun Profile.visibleFor(core: CoreType): Profile? = when (core) {
    CoreType.XRAY -> if (mihomo != null) null else {
        // tuic / anytls / mieru links have no Xray outbound
        val runnable = servers.filter { it.config != null || it.outbound != null }
        if (servers.isNotEmpty() && runnable.isEmpty()) null else copy(servers = runnable)
    }
    CoreType.MIHOMO -> {
        // mihomo's link parser has no WireGuard links
        val runnable = servers.filter { it.config == null && !(it.link != null && it.protocol in app.ghostly.core.link.LinkParser.XRAY_ONLY) }
        if (servers.isNotEmpty() && runnable.isEmpty()) null else copy(servers = runnable)
    }
}

/** After a core switch: when [core] can't use the profile as stored but can use its stash, swap the two. */
fun Profile.forCore(core: CoreType): Profile {
    val other = stash ?: return this
    if (visibleFor(core) != null) return this
    val swapped = copy(servers = other.servers, mihomo = other.mihomo, stash = ProfileContent(servers, mihomo))
    return if (swapped.visibleFor(core) != null) swapped else this
}

/** A fresh fetch replaces the content; the old content becomes the stash when it was the other format. */
fun Profile.withFetched(servers: List<Server>, mihomo: JsonObject?): Profile {
    val formatChanged = (this.mihomo != null) != (mihomo != null)
    return copy(servers = servers, mihomo = mihomo, stash = if (formatChanged) ProfileContent(this.servers, this.mihomo) else stash)
}
