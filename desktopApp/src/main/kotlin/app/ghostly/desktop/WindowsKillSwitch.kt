package app.ghostly.desktop

import app.ghostly.core.vpn.KillSwitch
import java.io.File
import app.ghostly.i18n.tr

/**
 * Kill switch on Windows Firewall. Engaged only while the tunnel is down against the user's will:
 * outbound traffic is blocked for everything except Ghostly and its core (so they can reconnect),
 * then the saved policy is restored the moment the tunnel is back or the user disconnects.
 * Needs admin rights, so it works in TUN mode (Ghostly runs elevated there).
 */
class WindowsKillSwitch(
    private val dataDir: String,
    private val xray: () -> File,
    private val elevated: () -> Boolean,
) : KillSwitch {

    private val saved get() = File(dataDir, "run/firewall-policy.txt")
    private val admin by lazy { elevated() }

    override val engaged: Boolean get() = saved.isFile

    override fun unavailableReason(): String? = when {
        hostOs != HostOs.WINDOWS -> tr("Пока только для Windows")
        !admin -> tr("Работает в режиме TUN — ему нужны права администратора")
        !firewallOn() -> tr("Брандмауэр Windows выключен — без него блокировать трафик нечем")
        else -> null
    }

    override fun engage(): Boolean {
        if (engaged) return true
        if (unavailableReason() != null) return false
        val policies = currentPolicies() ?: return false
        saved.parentFile.mkdirs()
        saved.writeText(policies.joinToString("\n"))
        val self = ProcessHandle.current().info().command().orElse(null)
        listOfNotNull(xray().absolutePath, self).forEachIndexed { i, program ->
            netsh("advfirewall", "firewall", "add", "rule", "name=$RULE$i", "dir=out", "action=allow", "program=$program", "enable=yes")
        }
        // Keep each profile's inbound setting, only flip outbound to block.
        PROFILES.zip(policies).forEach { (profile, policy) ->
            val inbound = policy.substringBefore(',')
            netsh("advfirewall", "set", profile, "firewallpolicy", "$inbound,blockoutbound")
        }
        return true
    }

    override fun release() {
        val lines = runCatching { saved.readLines().filter { it.isNotBlank() } }.getOrNull() ?: return
        PROFILES.zip(lines).forEach { (profile, policy) -> netsh("advfirewall", "set", profile, "firewallpolicy", policy.lowercase()) }
        for (i in 0..1) netsh("advfirewall", "firewall", "delete", "rule", "name=$RULE$i")
        saved.delete()
    }

    /** Policy per profile (domain, private, public), e.g. "BlockInbound,AllowOutbound". Values are English in every locale. */
    private fun currentPolicies(): List<String>? {
        val out = netsh("advfirewall", "show", "allprofiles", "firewallpolicy") ?: return null
        val found = Regex("""(Block|Allow)Inbound(Always)?,(Block|Allow)Outbound""", RegexOption.IGNORE_CASE).findAll(out).map { it.value }.toList()
        return found.takeIf { it.size == 3 }
    }

    private fun firewallOn(): Boolean {
        val out = netsh("advfirewall", "show", "allprofiles", "state") ?: return false
        // "State  ON" / "Состояние  ВКЛЮЧИТЬ": the value is localized, the OFF marker is what we look for.
        val states = out.lines().map { it.trim() }.filter { it.isNotEmpty() && (it.startsWith("State") || it.startsWith("Состояние")) }
        return states.isNotEmpty() && states.none { it.endsWith("OFF", true) || it.endsWith("ВЫКЛЮЧИТЬ", true) }
    }

    private fun netsh(vararg args: String): String? = runCatching {
        val p = ProcessBuilder(listOf("netsh") + args).redirectErrorStream(true).start()
        val text = p.inputStream.readAllBytes().toString(charset("CP866"))
        if (p.waitFor() == 0) text else null
    }.getOrNull()

    private companion object {
        const val RULE = "Ghostly kill switch "
        val PROFILES = listOf("domainprofile", "privateprofile", "publicprofile")
    }
}
