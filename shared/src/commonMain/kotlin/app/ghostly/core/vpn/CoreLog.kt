package app.ghostly.core.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The last [max] lines a core printed, for the "Core log" screen. */
class CoreLog(private val max: Int = 400) {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun add(line: String) {
        val l = line.trimEnd()
        if (l.isEmpty()) return
        _lines.update { (it + l).takeLast(max) }
    }

    fun clear() {
        _lines.value = emptyList()
    }

    companion object {
        /** For cores whose output we can't read (Xray on Android logs to logcat only). */
        val NONE = CoreLog(0)
    }
}
