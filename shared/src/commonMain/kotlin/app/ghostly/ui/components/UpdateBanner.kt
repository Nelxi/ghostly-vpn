package app.ghostly.ui.components

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
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
            Modifier.fillMaxWidth().clip(RoundedCornerShape(if (compact) 16.dp else 22.dp))
                .background(Brush.linearGradient(listOf(c.accent.copy(alpha = 0.22f), c.ok.copy(alpha = 0.10f))))
                .border(1.dp, c.accent.copy(alpha = 0.4f), RoundedCornerShape(if (compact) 16.dp else 22.dp))
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
                    Text("Вышло обновление", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Text(
                        when (val s = step) {
                            is UpdateStep.Downloading -> "Загружаю ${(s.progress * 100).toInt()}%"
                            UpdateStep.Verifying -> "Проверяю контрольную сумму…"
                            UpdateStep.Installing -> "Запускаю установку…"
                            is UpdateStep.Failed -> s.message
                            UpdateStep.Idle -> "Версия ${o.version}" + if (o.size > 0) " · ${app.ghostly.ui.Format.bytes(o.size)}" else ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (step is UpdateStep.Failed) c.bad else c.ink3,
                    )
                }
                if (!busy) {
                    Icon(
                        Icons.Rounded.Close, "Скрыть", tint = c.ink3,
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
                    if (step is UpdateStep.Failed) "Попробовать ещё раз" else "Обновить",
                    { controller.haptic(); controller.updater.reset(); controller.installUpdate() },
                    Modifier.fillMaxWidth(), icon = Icons.Rounded.SystemUpdate,
                )
            }
        }
    }
}
