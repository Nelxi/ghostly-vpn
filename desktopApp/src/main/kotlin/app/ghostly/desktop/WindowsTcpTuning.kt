package app.ghostly.desktop

import app.ghostly.core.vpn.TcpTuning
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Windows' TCP receive-window auto-tuning. Off (`netsh int tcp set global autotuninglevel=disabled`, a popular
 * "optimisation") means SYNs carry no window scaling and every connection tops out at 64 KB per round trip:
 * measured on a 100 Mbit/s line, the VPN went from 8 to 85 Mbit/s once it was back to normal.
 */
object WindowsTcpTuning : TcpTuning {
    private fun ps(command: String, timeoutS: Long = 20): String? = runCatching {
        val p = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command)
            .redirectErrorStream(true).start()
        if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return@runCatching null
        }
        p.inputStream.bufferedReader().readText().trim()
    }.getOrNull()

    // Get-NetTCPSetting prints the enum in English on every Windows language (netsh's text is localised).
    override suspend fun capped(): Boolean = withContext(Dispatchers.IO) {
        val level = ps("(Get-NetTCPSetting -SettingName Internet -ErrorAction SilentlyContinue).AutoTuningLevelLocal") ?: return@withContext false
        level.equals("Disabled", true) || level.equals("HighlyRestricted", true) || level.equals("Restricted", true)
    }

    override suspend fun fix(): Boolean = withContext(Dispatchers.IO) {
        // One UAC prompt; netsh changes the global setting, no reboot needed for new connections.
        ps("Start-Process -FilePath netsh.exe -ArgumentList 'int tcp set global autotuninglevel=normal' -Verb RunAs -WindowStyle Hidden -Wait", 120)
        !capped()
    }
}
