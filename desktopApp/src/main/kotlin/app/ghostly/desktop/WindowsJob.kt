package app.ghostly.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary

/**
 * Windows: the processes Ghostly starts (the Xray / Mihomo cores, the now-playing helper) are put in a
 * job object that closes together with Ghostly. When the app exits in any way — including a crash or
 * being killed from the task manager — Windows ends them too, so a core never keeps running on its own.
 * Elsewhere this does nothing.
 */
object WindowsJob {

    /** The few kernel32 calls needed (jna-platform has no job-object bindings). */
    @Suppress("FunctionName")
    private interface Api : StdCallLibrary {
        fun CreateJobObjectW(attributes: Pointer?, name: WString?): Pointer?
        fun SetInformationJobObject(job: Pointer, infoClass: Int, info: Pointer, size: Int): Boolean
        fun AssignProcessToJobObject(job: Pointer, process: Pointer): Boolean
        fun OpenProcess(access: Int, inherit: Boolean, pid: Int): Pointer?
        fun CloseHandle(handle: Pointer): Boolean
    }

    private const val KILL_ON_JOB_CLOSE = 0x2000
    private const val EXTENDED_LIMIT_INFORMATION = 9
    private const val PROCESS_SET_QUOTA = 0x0100
    private const val PROCESS_TERMINATE = 0x0001

    /** JOBOBJECT_EXTENDED_LIMIT_INFORMATION on x64: 144 bytes, BasicLimitInformation.LimitFlags at offset 16. */
    private const val LIMIT_INFO_SIZE = 144L
    private const val LIMIT_FLAGS_OFFSET = 16L

    private val api: Api? by lazy {
        if (hostOs != HostOs.WINDOWS || Native.POINTER_SIZE != 8) null
        else runCatching { Native.load("kernel32", Api::class.java) }.getOrNull()
    }

    private val job: Pointer? by lazy {
        val k = api ?: return@lazy null
        runCatching {
            val handle = k.CreateJobObjectW(null, null) ?: return@runCatching null
            val info = Memory(LIMIT_INFO_SIZE).apply { clear(); setInt(LIMIT_FLAGS_OFFSET, KILL_ON_JOB_CLOSE) }
            // The handle stays open for the life of the process on purpose: closing it is what ends the job.
            if (k.SetInformationJobObject(handle, EXTENDED_LIMIT_INFORMATION, info, LIMIT_INFO_SIZE.toInt())) handle else null
        }.getOrNull()
    }

    /** Ties [process] to Ghostly's lifetime (best effort: a failure only means the old behaviour). */
    fun adopt(process: Process) {
        val k = api ?: return
        val j = job ?: return
        runCatching {
            val ph = k.OpenProcess(PROCESS_SET_QUOTA or PROCESS_TERMINATE, false, process.pid().toInt()) ?: return@runCatching
            try {
                k.AssignProcessToJobObject(j, ph)
            } finally {
                k.CloseHandle(ph)
            }
        }
    }
}

/**
 * Ends copies of [exe] still running without us: a core left behind by an earlier Ghostly that crashed
 * (before [WindowsJob] existed) keeps the ports and the TUN adapter, and the new connection can't come up.
 * Called right after the backend stopped its own process, so whatever is left is an orphan.
 */
fun killOrphanCores(exe: java.io.File) {
    val path = exe.absolutePath
    runCatching {
        ProcessHandle.allProcesses()
            .filter { h -> h.pid() != ProcessHandle.current().pid() && h.info().command().map { it.equals(path, ignoreCase = true) }.orElse(false) }
            .forEach { it.destroyForcibly() }
    }
}
