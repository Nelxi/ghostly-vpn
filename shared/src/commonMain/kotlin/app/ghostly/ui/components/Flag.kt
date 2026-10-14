package app.ghostly.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import app.ghostly.ui.theme.Ghost

/**
 * Country flags drawn by the app itself. Windows' emoji font has no flags (🇫🇮 shows up as "FI"),
 * so flags in server names are rendered here the same way on every platform. Common VPN locations
 * are drawn as real flags; anything else becomes a neat code badge instead of raw letters.
 */

/** "🇫🇮" (two regional-indicator symbols) -> "FI"; also accepts a plain two-letter code. */
fun flagCode(flag: String?): String? {
    if (flag == null) return null
    val cps = buildList {
        var i = 0
        while (i < flag.length) {
            val cp = flag.codePointAtCompat(i)
            add(cp)
            i += if (cp > 0xFFFF) 2 else 1
        }
    }.filter { it in 0x1F1E6..0x1F1FF }
    if (cps.size >= 2) return cps.take(2).joinToString("") { ('A' + (it - 0x1F1E6)).toString() }
    val t = flag.trim()
    return t.takeIf { it.length == 2 && it.all { c -> c.isLetter() } }?.uppercase()
}

private fun String.codePointAtCompat(i: Int): Int {
    val c = this[i]
    if (c.isHighSurrogate() && i + 1 < length) {
        val d = this[i + 1]
        if (d.isLowSurrogate()) return ((c.code - 0xD800) shl 10) + (d.code - 0xDC00) + 0x10000
    }
    return c.code
}

@Composable
fun FlagIcon(flag: String, height: Dp, modifier: Modifier = Modifier) {
    val code = flagCode(flag) ?: return
    FlagBox(code, modifier.size(height * 1.4f, height), height)
}

/** A flag filling [modifier]'s size; [height] only scales the corner radius and the badge text. */
@Composable
private fun FlagBox(code: String, modifier: Modifier, height: Dp) {
    val shape = RoundedCornerShape(height * 0.22f)
    val painter = FLAGS[code]
    if (painter == null) {
        // Unknown country: a small badge with the code, not two bare letters.
        Box(modifier.clip(shape).background(Ghost.colors.accent.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
            Text(code, fontSize = (height.value * 0.46f).sp, fontWeight = FontWeight.Bold, color = Ghost.colors.ink)
        }
        return
    }
    Canvas(modifier.clip(shape)) {
        painter()
        // Soft top highlight and hairline edge so light flags don't melt into the glass.
        drawRect(Color.White.copy(alpha = 0.10f), size = Size(size.width, size.height * 0.45f))
        drawRect(Color.Black.copy(alpha = 0.18f), style = Stroke(1f))
    }
}

/** Two regional-indicator symbols anywhere in a string (start, end, several in a row). */
private val FLAG_PAIR = Regex("[\\x{1F1E6}-\\x{1F1FF}]{2}")

/**
 * Text whose emoji flags (anywhere, any number) are replaced by drawn flags inline — on Windows the
 * emoji font has no flags and shows "NL", "FI" instead.
 */
@Composable
fun FlagText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = androidx.compose.material3.LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    softWrap: Boolean = true,
    overflow: androidx.compose.ui.text.style.TextOverflow = androidx.compose.ui.text.style.TextOverflow.Clip,
) {
    val matches = FLAG_PAIR.findAll(text).toList()
    if (matches.isEmpty()) {
        Text(text, modifier, color = color, style = style, maxLines = maxLines, softWrap = softWrap, overflow = overflow)
        return
    }
    val codes = matches.mapNotNull { flagCode(it.value) }.toSet()
    val annotated = androidx.compose.ui.text.buildAnnotatedString {
        var last = 0
        for (m in matches) {
            append(text.substring(last, m.range.first))
            val code = flagCode(m.value)
            if (code != null) appendInlineContent("flag:$code", code) else append(m.value)
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
    val inline = codes.associate { code ->
        "flag:$code" to androidx.compose.foundation.text.InlineTextContent(
            androidx.compose.ui.text.Placeholder(
                width = androidx.compose.ui.unit.TextUnit(1.45f, androidx.compose.ui.unit.TextUnitType.Em),
                height = androidx.compose.ui.unit.TextUnit(0.95f, androidx.compose.ui.unit.TextUnitType.Em),
                placeholderVerticalAlign = androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter,
            ),
        ) {
            FlagBox(code, Modifier.fillMaxSize().padding(horizontal = androidx.compose.ui.unit.Dp(1.5f)), androidx.compose.ui.unit.Dp(12f))
        }
    }
    Text(annotated, modifier, color = color, style = style, maxLines = maxLines, softWrap = softWrap, overflow = overflow, inlineContent = inline)
}

private typealias FlagPainter = DrawScope.() -> Unit

private fun h(vararg c: Long): FlagPainter = {
    val band = size.height / c.size
    c.forEachIndexed { i, col -> drawRect(Color(col), Offset(0f, band * i), Size(size.width, band + 1f)) }
}

private fun v(vararg c: Long): FlagPainter = {
    val band = size.width / c.size
    c.forEachIndexed { i, col -> drawRect(Color(col), Offset(band * i, 0f), Size(band + 1f, size.height)) }
}

/** Nordic cross, optionally with a border around the cross. */
private fun nordic(bg: Long, cross: Long, border: Long? = null): FlagPainter = {
    drawRect(Color(bg))
    val w = size.width
    val hgt = size.height
    val cx = w * 0.36f
    val t = hgt * 0.22f
    if (border != null) {
        val b = hgt * 0.36f
        drawRect(Color(border), Offset(cx - b / 2, 0f), Size(b, hgt))
        drawRect(Color(border), Offset(0f, hgt / 2 - b / 2), Size(w, b))
    }
    drawRect(Color(cross), Offset(cx - t / 2, 0f), Size(t, hgt))
    drawRect(Color(cross), Offset(0f, hgt / 2 - t / 2), Size(w, t))
}

private val FLAGS: Map<String, FlagPainter> = mapOf(
    "RU" to h(0xFFFFFFFF, 0xFF0039A6, 0xFFD52B1E),
    "NL" to h(0xFFAE1C28, 0xFFFFFFFF, 0xFF21468B),
    "DE" to h(0xFF000000, 0xFFDD0000, 0xFFFFCE00),
    "AT" to h(0xFFED2939, 0xFFFFFFFF, 0xFFED2939),
    "EE" to h(0xFF0072CE, 0xFF000000, 0xFFFFFFFF),
    "LV" to h(0xFF9E3039, 0xFF9E3039, 0xFFFFFFFF, 0xFF9E3039, 0xFF9E3039),
    "LT" to h(0xFFFDB913, 0xFF006A44, 0xFFC1272D),
    "BG" to h(0xFFFFFFFF, 0xFF00966E, 0xFFD62612),
    "HU" to h(0xFFCE2939, 0xFFFFFFFF, 0xFF477050),
    "UA" to h(0xFF0057B7, 0xFFFFD700),
    "PL" to h(0xFFFFFFFF, 0xFFDC143C),
    "ID" to h(0xFFFF0000, 0xFFFFFFFF),
    "LU" to h(0xFFED2939, 0xFFFFFFFF, 0xFF00A1DE),
    "AM" to h(0xFFD90012, 0xFF0033A0, 0xFFF2A800),
    "RS" to h(0xFFC6363C, 0xFF0C4076, 0xFFFFFFFF),
    "FR" to v(0xFF0055A4, 0xFFFFFFFF, 0xFFEF4135),
    "IT" to v(0xFF009246, 0xFFFFFFFF, 0xFFCE2B37),
    "BE" to v(0xFF000000, 0xFFFDDA24, 0xFFEF3340),
    "IE" to v(0xFF169B62, 0xFFFFFFFF, 0xFFFF883E),
    "RO" to v(0xFF002B7F, 0xFFFCD116, 0xFFCE1126),
    "MD" to v(0xFF0046AE, 0xFFFFD200, 0xFFCC092F),
    "FI" to nordic(0xFFFFFFFF, 0xFF002F6C),
    "SE" to nordic(0xFF006AA7, 0xFFFECC00),
    "DK" to nordic(0xFFC8102E, 0xFFFFFFFF),
    "NO" to nordic(0xFFBA0C2F, 0xFF00205B, 0xFFFFFFFF),
    "IS" to nordic(0xFF02529C, 0xFFDC1E35, 0xFFFFFFFF),
    "CH" to {
        drawRect(Color(0xFFDA291C))
        val u = size.height * 0.18f
        drawRect(Color.White, Offset(size.width / 2 - u / 2, size.height * 0.2f), Size(u, size.height * 0.6f))
        drawRect(Color.White, Offset(size.width / 2 - size.height * 0.3f, size.height / 2 - u / 2), Size(size.height * 0.6f, u))
    },
    "JP" to {
        drawRect(Color.White)
        drawCircle(Color(0xFFBC002D), size.height * 0.3f, center)
    },
    "TR" to {
        drawRect(Color(0xFFE30A17))
        val c = Offset(size.width * 0.38f, size.height / 2)
        drawCircle(Color.White, size.height * 0.27f, c)
        drawCircle(Color(0xFFE30A17), size.height * 0.22f, c + Offset(size.height * 0.07f, 0f))
        drawCircle(Color.White, size.height * 0.07f, Offset(size.width * 0.62f, size.height / 2))
    },
    "KZ" to {
        drawRect(Color(0xFF00AFCA))
        drawCircle(Color(0xFFFEC50C), size.height * 0.2f, Offset(size.width * 0.52f, size.height * 0.45f))
    },
    // Flags of the app's languages (the language picker), simplified like the others.
    "BY" to {
        drawRect(Color(0xFFC8313E))
        drawRect(Color(0xFF4AA657), Offset(0f, size.height * 2 / 3), Size(size.width, size.height / 3 + 1f))
        drawRect(Color.White, size = Size(size.width * 0.16f, size.height))
    },
    "UZ" to {
        h(0xFF0099B5, 0xFFFFFFFF, 0xFF1EB53A)()
        val line = size.height * 0.04f
        drawRect(Color(0xFFCE1126), Offset(0f, size.height / 3 - line / 2), Size(size.width, line))
        drawRect(Color(0xFFCE1126), Offset(0f, size.height * 2 / 3 - line / 2), Size(size.width, line))
        drawCircle(Color.White, size.height * 0.11f, Offset(size.width * 0.18f, size.height * 0.17f))
    },
    "IR" to {
        h(0xFF239F40, 0xFFFFFFFF, 0xFFDA0000)()
        drawCircle(Color(0xFFDA0000), size.height * 0.1f, center, style = Stroke(size.height * 0.04f))
    },
    "SA" to {
        drawRect(Color(0xFF006C35))
        drawRect(Color.White, Offset(size.width * 0.25f, size.height * 0.62f), Size(size.width * 0.5f, size.height * 0.05f))
    },
    "CN" to {
        drawRect(Color(0xFFEE1C25))
        drawCircle(Color(0xFFFFFF00), size.height * 0.15f, Offset(size.width * 0.2f, size.height * 0.28f))
        drawCircle(Color(0xFFFFFF00), size.height * 0.05f, Offset(size.width * 0.38f, size.height * 0.14f))
        drawCircle(Color(0xFFFFFF00), size.height * 0.05f, Offset(size.width * 0.42f, size.height * 0.3f))
        drawCircle(Color(0xFFFFFF00), size.height * 0.05f, Offset(size.width * 0.38f, size.height * 0.46f))
    },
    "ES" to {
        drawRect(Color(0xFFAA151B))
        drawRect(Color(0xFFF1BF00), Offset(0f, size.height / 4), Size(size.width, size.height / 2))
    },
    "BR" to {
        drawRect(Color(0xFF009C3B))
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width / 2, size.height * 0.1f); lineTo(size.width * 0.92f, size.height / 2)
            lineTo(size.width / 2, size.height * 0.9f); lineTo(size.width * 0.08f, size.height / 2); close()
        }
        drawPath(path, Color(0xFFFFDF00))
        drawCircle(Color(0xFF002776), size.height * 0.22f, center)
    },
    "IN" to {
        h(0xFFFF9933, 0xFFFFFFFF, 0xFF138808)()
        drawCircle(Color(0xFF000080), size.height * 0.12f, center, style = Stroke(size.height * 0.035f))
    },
    "US" to {
        val stripe = size.height / 13
        for (i in 0 until 13) drawRect(Color(if (i % 2 == 0) 0xFFB22234 else 0xFFFFFFFF), Offset(0f, stripe * i), Size(size.width, stripe + 1f))
        drawRect(Color(0xFF3C3B6E), size = Size(size.width * 0.42f, stripe * 7))
    },
    // Not a country, but subscriptions use 🇪🇺 for European pools.
    "EU" to {
        drawRect(Color(0xFF003399))
        val r = size.height * 0.30f
        val dot = size.height * 0.06f
        for (i in 0 until 12) {
            val a = (i * 30 - 90) * (kotlin.math.PI / 180.0)
            drawCircle(
                Color(0xFFFFCC00), dot,
                center + Offset((r * kotlin.math.cos(a)).toFloat(), (r * kotlin.math.sin(a)).toFloat()),
            )
        }
    },
    "GB" to {
        drawRect(Color(0xFF012169))
        val w = size.width
        val hgt = size.height
        drawLine(Color.White, Offset(0f, 0f), Offset(w, hgt), hgt * 0.2f)
        drawLine(Color.White, Offset(w, 0f), Offset(0f, hgt), hgt * 0.2f)
        drawLine(Color(0xFFC8102E), Offset(0f, 0f), Offset(w, hgt), hgt * 0.07f)
        drawLine(Color(0xFFC8102E), Offset(w, 0f), Offset(0f, hgt), hgt * 0.07f)
        drawRect(Color.White, Offset(w / 2 - hgt * 0.17f, 0f), Size(hgt * 0.34f, hgt))
        drawRect(Color.White, Offset(0f, hgt / 2 - hgt * 0.17f), Size(w, hgt * 0.34f))
        drawRect(Color(0xFFC8102E), Offset(w / 2 - hgt * 0.1f, 0f), Size(hgt * 0.2f, hgt))
        drawRect(Color(0xFFC8102E), Offset(0f, hgt / 2 - hgt * 0.1f), Size(w, hgt * 0.2f))
    },
)
