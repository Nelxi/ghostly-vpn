package app.ghostly.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ghostly.core.GhostlyController
import app.ghostly.core.vpn.CoreLog
import app.ghostly.ui.components.SoftButton
import app.ghostly.ui.theme.Ghost

/** The running core's recent output: newest at the bottom, selectable, copy / clear. */
@Composable
internal fun CoreLogContent(controller: GhostlyController) {
    val c = Ghost.colors
    val log = controller.backend.coreLog
    val lines by log.lines.collectAsState()
    val level = controller.settings.collectAsState().value.logLevel
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        SoftButton("Скопировать", { controller.platform.copyToClipboard(lines.joinToString("\n")); controller.toast("Журнал скопирован") },
            Modifier.weight(1f), icon = Icons.Rounded.ContentCopy)
        Spacer(Modifier.width(8.dp))
        SoftButton("Очистить", { log.clear() }, Modifier.weight(1f), icon = Icons.Rounded.DeleteSweep)
    }
    val hint = when {
        log === CoreLog.NONE -> "Это ядро пишет журнал только в системный журнал устройства — здесь его не показать."
        level == "none" -> "Журнал выключен. Выбери уровень в «Для продвинутых» → «Журнал ядра»."
        lines.isEmpty() -> "Пока пусто. Подключись — здесь появятся сообщения ядра."
        else -> null
    }
    if (hint != null) {
        Text(hint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(6.dp))
        return
    }
    SelectionContainer {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.035f)).padding(10.dp),
        ) {
            lines.forEach { line ->
                val color = when {
                    line.startsWith("error", true) || "[ERRO" in line || "level=error" in line -> c.bad
                    line.startsWith("warn", true) || "[WARN" in line || "level=warn" in line -> c.warn
                    else -> c.ink2
                }
                Text(line, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
    }
}
