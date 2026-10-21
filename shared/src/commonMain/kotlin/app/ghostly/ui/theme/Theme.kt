package app.ghostly.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.ghostly.resources.Res
import app.ghostly.resources.onest_bold
import app.ghostly.resources.onest_extrabold
import app.ghostly.resources.onest_medium
import app.ghostly.resources.onest_regular
import app.ghostly.resources.onest_semibold
import org.jetbrains.compose.resources.Font

/**
 * The interface's colours. They follow the night behind it (see NightScene): moonlit greys while the VPN is
 * off, a violet tint once it is protected ([protectedNight] / [greyNight]); the accent stays the same in both.
 */
@Immutable
data class GhostColors(
    val bg: Color = Color(0xFF05040A),
    val bgRaised: Color = Color(0xFF0E0A16),
    val ink: Color = Color(0xFFF6F3FF),
    val ink2: Color = Color(0xFFB6ABDB),
    val ink3: Color = Color(0xFFA59DC5),
    /** Quiet icons: the dock's other tabs, chevrons. */
    val ink4: Color = Color(0xFF8C84AA),
    val accent: Color = Color(0xFFA88DFF),
    val accent2: Color = Color(0xFF7C5CF0),
    val accentInk: Color = Color(0xFF150A2C),
    val ok: Color = Color(0xFF8FE3C0),
    val warn: Color = Color(0xFFFFD08A),
    val bad: Color = Color(0xFFFF9AA9),
    val line: Color = Color(0x14FFFFFF),
    val glass: Color = Color(0x0DFFFFFF),
    val glassStrong: Color = Color(0x17FFFFFF),
    /** Sheets and dialogs: opaque night ink. */
    val surface1: Color = Color(0xFF110F1A),
    /** Tiles inside plates, raised rows. */
    val surface2: Color = Color(0xFF1A1724),
    /** Inputs, steppers, pressed states. */
    val surface3: Color = Color(0xFF231F2F),
    /** 1px edges. */
    val hairline: Color = Color(0x12FFFFFF),
    /** Plates over the night: translucent, the scene shows through a little. */
    val plate: Color = Color(0xD60C0B13),
    /** The light caught by a plate's top edge. */
    val plateEdge: Color = Color(0x26C8B4FF),
    /** 1 in the protected (coloured) night, 0 in the grey one: for the few things that blend the two. */
    val lit: Float = 1f,
)

/** The protected night's interface (the defaults). */
val protectedNight = GhostColors()

/** The unprotected night's interface: moonlit greys. */
val greyNight = GhostColors(
    bg = Color(0xFF040406),
    bgRaised = Color(0xFF0D0E12),
    ink = Color(0xFFEEF0F4),
    ink2 = Color(0xFFA3A9B6),
    ink3 = Color(0xFF8E94A3),
    ink4 = Color(0xFF7D8392),
    surface1 = Color(0xFF101116),
    surface2 = Color(0xFF18191F),
    surface3 = Color(0xFF202229),
    plate = Color(0xD60C0D11),
    plateEdge = Color(0x12FFFFFF),
    lit = 0f,
)

object Motion {
    /** --ease: cubic-bezier(.22, 1, .36, 1) */
    val Ease = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    val EaseInOut = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

    fun <T> bouncy() = spring<T>(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)
    fun <T> soft() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
    fun <T> quick(ms: Int = 320) = tween<T>(ms, easing = Ease)
}

val LocalGhostColors = staticCompositionLocalOf { GhostColors() }
val LocalReduceMotion = staticCompositionLocalOf { false }

/** How much snow falls on the night scene: 0 = off (settings × design.json `snow`). */
val LocalSnow = staticCompositionLocalOf { 0f }

/** Server-tunable look (see RemoteDesign). */
val LocalDesign = androidx.compose.runtime.compositionLocalOf { app.ghostly.core.design.DesignTokens() }

object Ghost {
    val colors: GhostColors @Composable get() = LocalGhostColors.current
}

@Composable
fun onestFamily(): FontFamily = FontFamily(
    Font(Res.font.onest_regular, FontWeight.Normal),
    Font(Res.font.onest_medium, FontWeight.Medium),
    Font(Res.font.onest_semibold, FontWeight.SemiBold),
    Font(Res.font.onest_bold, FontWeight.Bold),
    Font(Res.font.onest_extrabold, FontWeight.ExtraBold),
)

@Composable
fun GhostlyTheme(accentArgb: Long, reduceMotion: Boolean, protected: Boolean = false, content: @Composable () -> Unit) {
    val target = Color(accentArgb.toInt())
    val accent by animateColorAsState(target, Motion.quick(500))
    // Switched at once, not animated: an animation would recompose the whole interface on every frame while
    // the scene behind it is busy spreading its colour (the scene and the plates' edges fade on their own).
    val base = if (protected) protectedNight else greyNight
    val colors = base.copy(
        accent = accent,
        // Deeper companion shade of the accent for gradients (site: #a88dff → #7c5cf0).
        accent2 = lerp(accent, Color(0xFF3A1F9E), 0.38f),
        accentInk = lerp(accent, Color.Black, 0.86f),
    )
    val family = onestFamily()
    val text = TextStyle(fontFamily = family, color = colors.ink)
    val typography = Typography(
        displayLarge = text.copy(fontSize = 54.sp, lineHeight = 56.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-2.1).sp),
        displayMedium = text.copy(fontSize = 34.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.0).sp),
        headlineMedium = text.copy(fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.9).sp),
        headlineSmall = text.copy(fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        titleLarge = text.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = text.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = text.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = text.copy(fontSize = 16.sp, lineHeight = 22.sp),
        bodyMedium = text.copy(fontSize = 14.sp, lineHeight = 20.sp, color = colors.ink2),
        bodySmall = text.copy(fontSize = 12.sp, lineHeight = 16.sp, color = colors.ink3),
        labelLarge = text.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = text.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
        labelSmall = text.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
    )
    val scheme = darkColorScheme(
        primary = colors.accent,
        onPrimary = colors.accentInk,
        secondary = colors.ok,
        background = colors.bg,
        onBackground = colors.ink,
        surface = colors.bgRaised,
        onSurface = colors.ink,
        surfaceVariant = colors.surface2,
        onSurfaceVariant = colors.ink2,
        surfaceContainer = colors.surface1,
        surfaceContainerHigh = colors.surface2,
        surfaceContainerHighest = colors.surface3,
        surfaceContainerLow = colors.bgRaised,
        outline = colors.line,
        outlineVariant = colors.line,
        error = colors.bad,
    )
    CompositionLocalProvider(LocalGhostColors provides colors, LocalReduceMotion provides reduceMotion) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
