package app.ghostly.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ghostly.core.GhostlyController
import app.ghostly.core.update.UpdateStep
import app.ghostly.ui.theme.Ghost
import app.ghostly.i18n.tr

/** "Вышло обновление — Обновить" with ✕; shows download / verify progress in place. */
@Composable
fun UpdateBanner(controller: GhostlyController, modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = Ghost.colors
    val offer by controller.updater.offer.collectAsState()
    val step by controller.updater.step.collectAsState()
    AnimatedVisibility(offer != null, modifier, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        val o = offer ?: return@AnimatedVisibility
        val busy = step is UpdateStep.Downloading || step is UpdateStep.Verifying || step is UpdateStep.Installing
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(if (compact) app.ghostly.ui.components.Radius.tile else app.ghostly.ui.components.Radius.card))
                .background(c.surface2)
                .background(Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.10f), Color.Transparent)))
                .border(1.dp, c.accent.copy(alpha = 0.35f), RoundedCornerShape(if (compact) app.ghostly.ui.components.Radius.tile else app.ghostly.ui.components.Radius.card))
                .padding(if (compact) 12.dp else 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!compact) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.SystemUpdate, null, tint = c.accent, modifier = Modifier.size(19.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(tr("Вышло обновление"), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Text(
                        when (val s = step) {
                            is UpdateStep.Downloading -> tr("Загружаю {0}%", (s.progress * 100).toInt())
                            UpdateStep.Verifying -> tr("Проверяю контрольную сумму…")
                            UpdateStep.Installing -> tr("Запускаю установку…")
                            is UpdateStep.Failed -> s.message
                            UpdateStep.Idle -> tr("Версия {0}", o.version) + if (o.size > 0) " · ${app.ghostly.ui.Format.bytes(o.size)}" else ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (step is UpdateStep.Failed) c.bad else c.ink3,
                    )
                }
                if (!busy) {
                    Icon(
                        Icons.Rounded.Close, tr("Скрыть"), tint = c.ink3,
                        modifier = Modifier.size(30.dp).clip(CircleShape).hoverSound().clickable { controller.dismissUpdate() }.padding(6.dp),
                    )
                }
            }
            val s = step
            if (s is UpdateStep.Downloading) {
                Spacer(Modifier.height(10.dp))
                GlowBar(s.progress, c.accent)
            } else if (!busy) {
                Spacer(Modifier.height(10.dp))
                AccentButton(
                    if (step is UpdateStep.Failed) tr("Попробовать ещё раз") else tr("Обновить"),
                    { controller.haptic(); controller.updater.reset(); controller.installUpdate() },
                    Modifier.fillMaxWidth(), icon = Icons.Rounded.SystemUpdate,
                )
            }
        }
    }
}

/**
 * Windows with TCP auto-tuning turned off caps every connection at ~8 Mbit/s (64 KB per round trip), VPN or not.
 * Checked once per launch; one tap fixes it (a UAC prompt). Hidden for good once dismissed.
 */
@Composable
fun SpeedFixBanner(controller: GhostlyController, modifier: Modifier = Modifier) {
    val c = Ghost.colors
    val tuning = controller.platform.tcpTuning ?: return
    val settings by controller.settings.collectAsState()
    var capped by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var state by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) } // 0 idle, 1 busy, 2 done, 3 failed
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(Unit) { capped = runCatching { tuning.capped() }.getOrDefault(false) }
    AnimatedVisibility(capped && !settings.tcpTipDismissed, modifier, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(app.ghostly.ui.components.Radius.card))
                .background(c.surface2)
                .background(Brush.verticalGradient(listOf(c.warn.copy(alpha = 0.10f), Color.Transparent)))
                .border(1.dp, c.warn.copy(alpha = 0.35f), RoundedCornerShape(app.ghostly.ui.components.Radius.card))
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(c.warn.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Speed, null, tint = c.warn, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when (state) {
                            2 -> tr("Готово — скорость больше не ограничена")
                            else -> tr("Windows режет скорость")
                        },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        when (state) {
                            2 -> tr("Переподключитесь, чтобы новые соединения шли на полной скорости")
                            3 -> tr("Не получилось — нужно разрешить изменение в окне Windows")
                            else -> tr("Отключена автонастройка TCP: каждое соединение ограничено примерно 8 Мбит/с, с VPN и без него")
                        },
                        style = MaterialTheme.typography.bodySmall, color = if (state == 3) c.bad else c.ink3,
                    )
                }
                Icon(
                    Icons.Rounded.Close, tr("Скрыть"), tint = c.ink3,
                    modifier = Modifier.size(30.dp).clip(CircleShape).hoverSound().clickable {
                        controller.updateSettings { it.copy(tcpTipDismissed = true) }
                    }.padding(6.dp),
                )
            }
            if (state != 2) {
                Spacer(Modifier.height(10.dp))
                AccentButton(
                    if (state == 1) tr("Исправляю…") else tr("Ускорить"),
                    {
                        if (state != 1) {
                            controller.haptic()
                            state = 1
                            scope.launch {
                                state = if (runCatching { tuning.fix() }.getOrDefault(false)) 2 else 3
                            }
                        }
                    },
                    Modifier.fillMaxWidth(), icon = Icons.Rounded.Speed,
                )
            }
        }
    }
}
