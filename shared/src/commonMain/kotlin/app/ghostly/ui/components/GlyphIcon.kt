package app.ghostly.ui.components

import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/** A small glyph shadow for icons that sit directly on the surface. */
@Composable
internal fun Icon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    shadow: Boolean = true,
) {
    val painter = rememberVectorPainter(imageVector)
    val halo = if (!shadow || tint == Color.Unspecified || tint.alpha <= 0.01f) Modifier else Modifier.drawWithCache {
        val scale = ContentScale.Fit.computeScaleFactor(painter.intrinsicSize, size)
        val glyphSize = Size(painter.intrinsicSize.width * scale.scaleX, painter.intrinsicSize.height * scale.scaleY)
        val x = (size.width - glyphSize.width) / 2f
        val y = (size.height - glyphSize.height) / 2f
        val spread = 0.55.dp.toPx()
        val opacity = (if (tint.luminance() < 0.16f) 0.035f else 0.12f) * tint.alpha
        val black = ColorFilter.tint(Color.Black)
        // Subpixel samples of the painter's cached vector mask, without a blur layer per icon.
        onDrawBehind {
            with(painter) {
                translate(x - spread, y + spread) { draw(glyphSize, opacity, black) }
                translate(x + spread, y + spread) { draw(glyphSize, opacity, black) }
                translate(x, y + 2f * spread) { draw(glyphSize, opacity, black) }
            }
        }
    }
    MaterialIcon(painter, contentDescription, modifier.then(halo), tint)
}
