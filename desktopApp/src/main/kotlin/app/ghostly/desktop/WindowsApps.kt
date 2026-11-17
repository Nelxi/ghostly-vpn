package app.ghostly.desktop

import app.ghostly.core.vpn.AppEntry
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File

object WindowsApps {
    fun list(): List<AppEntry> {
        if (hostOs != HostOs.WINDOWS) return emptyList()
        val apps = mutableMapOf<String, AppEntry>() // key = lowercase exe name or path

        // 1. Currently running processes (with window or running non-system programs)
        runCatching {
            ProcessHandle.allProcesses().forEach { ph ->
                val path = ph.info().command().orElse(null) ?: return@forEach
                val f = File(path)
                if (!f.isFile) return@forEach
                val exeName = f.name
                val low = exeName.lowercase()
                if (low in SYSTEM_EXES || path.contains("\\Windows\\System32\\", ignoreCase = true)) return@forEach
                val label = f.nameWithoutExtension.replaceFirstChar { it.uppercase() }
                apps[low] = AppEntry(packageName = exeName, label = label, isSystem = false)
            }
        }

        // 2. Start Menu shortcuts
        val startMenuDirs = listOfNotNull(
            System.getenv("ProgramData")?.let { File(it, "Microsoft\\Windows\\Start Menu\\Programs") },
            System.getenv("APPDATA")?.let { File(it, "Microsoft\\Windows\\Start Menu\\Programs") }
        )
        for (dir in startMenuDirs) {
            if (!dir.isDirectory) continue
            dir.walkTopDown().maxDepth(4).filter { it.extension.equals("lnk", ignoreCase = true) }.forEach { lnk ->
                val name = lnk.nameWithoutExtension
                if (name.lowercase() in SYSTEM_SHORTCUTS) return@forEach
                // Heuristic: check if there's an exe of the same name or add based on lnk
                val exeGuess = "$name.exe"
                val low = exeGuess.lowercase()
                if (low !in apps && low !in SYSTEM_EXES) {
                    apps[low] = AppEntry(packageName = exeGuess, label = name, isSystem = false)
                }
            }
        }

        // 3. Registry App Paths
        val appPathsKeys = listOf(
            WinReg.HKEY_LOCAL_MACHINE to "Software\\Microsoft\\Windows\\CurrentVersion\\App Paths",
            WinReg.HKEY_CURRENT_USER to "Software\\Microsoft\\Windows\\CurrentVersion\\App Paths"
        )
        for ((root, key) in appPathsKeys) {
            runCatching {
                if (Advapi32Util.registryKeyExists(root, key)) {
                    Advapi32Util.registryGetKeys(root, key).forEach { subKey ->
                        if (subKey.endsWith(".exe", ignoreCase = true)) {
                            val low = subKey.lowercase()
                            if (low !in SYSTEM_EXES && low !in apps) {
                                val label = File(subKey).nameWithoutExtension.replaceFirstChar { it.uppercase() }
                                apps[low] = AppEntry(packageName = subKey, label = label, isSystem = false)
                            }
                        }
                    }
                }
            }
        }

        // 4. Registry Uninstall (Common applications)
        val uninstallKeys = listOf(
            WinReg.HKEY_LOCAL_MACHINE to "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            WinReg.HKEY_LOCAL_MACHINE to "Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            WinReg.HKEY_CURRENT_USER to "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall"
        )
        for ((root, key) in uninstallKeys) {
            runCatching {
                if (Advapi32Util.registryKeyExists(root, key)) {
                    Advapi32Util.registryGetKeys(root, key).forEach { subKey ->
                        val subPath = "$key\\$subKey"
                        val displayName = runCatching { Advapi32Util.registryGetStringValue(root, subPath, "DisplayName") }.getOrNull()
                        val displayIcon = runCatching { Advapi32Util.registryGetStringValue(root, subPath, "DisplayIcon") }.getOrNull()
                        val installLoc = runCatching { Advapi32Util.registryGetStringValue(root, subPath, "InstallLocation") }.getOrNull()
                        if (!displayName.isNullOrBlank()) {
                            var exe: String? = null
                            if (!displayIcon.isNullOrBlank()) {
                                val clean = displayIcon.split(',')[0].trim('"', ' ')
                                if (clean.endsWith(".exe", ignoreCase = true) && File(clean).isFile) {
                                    exe = File(clean).name
                                }
                            }
                            if (exe == null && !installLoc.isNullOrBlank()) {
                                val locDir = File(installLoc.trim('"', ' '))
                                if (locDir.isDirectory) {
                                    val candidate = locDir.listFiles { f -> f.isFile && f.extension.equals("exe", ignoreCase = true) }
                                        ?.maxByOrNull { it.length() }
                                    if (candidate != null) exe = candidate.name
                                }
                            }
                            if (exe != null) {
                                val low = exe.lowercase()
                                if (low !in SYSTEM_EXES && low !in apps) {
                                    apps[low] = AppEntry(packageName = exe, label = displayName, isSystem = false)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Add standard well-known Windows applications if not yet in list
        val common = listOf(
            "chrome.exe" to "Google Chrome",
            "firefox.exe" to "Mozilla Firefox",
            "opera.exe" to "Opera",
            "browser.exe" to "Yandex Browser",
            "Telegram.exe" to "Telegram Desktop",
            "Discord.exe" to "Discord",
            "Steam.exe" to "Steam",
            "msedge.exe" to "Microsoft Edge",
            "spotify.exe" to "Spotify"
        )
        for ((exe, label) in common) {
            val low = exe.lowercase()
            if (low !in apps) {
                apps[low] = AppEntry(packageName = exe, label = label, isSystem = false)
            }
        }

        return apps.values.sortedBy { it.label.lowercase() }
    }

    private val SYSTEM_EXES = setOf(
        "explorer.exe", "svchost.exe", "lsass.exe", "services.exe", "csrss.exe", "smss.exe",
        "wininit.exe", "winlogon.exe", "taskhostw.exe", "runtimebroker.exe", "searchhost.exe",
        "shellexperiencehost.exe", "startmenuexperiencehost.exe", "textinputhost.exe",
        "ctfmon.exe", "conhost.exe", "dllhost.exe", "dwm.exe", "sihost.exe", "xray.exe",
        "prizrak-core.exe", "ghostly.exe", "audiodg.exe"
    )

    private val SYSTEM_SHORTCUTS = setOf(
        "command prompt", "windows powershell", "registry editor", "task manager",
        "control panel", "run", "administrative tools"
    )
}