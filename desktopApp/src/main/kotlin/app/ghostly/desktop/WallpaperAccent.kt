package app.ghostly.desktop

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Material You on Windows: a seed colour taken from the wallpaper itself (the Windows accent is a separate,
 * hand-picked colour). Dark and grey pixels are skipped, hues are weighted by how colourful and bright they
 * are, the strongest hue wins; the result is lifted to a light pastel so it reads on the dark interface,
 * the way Android's Monet gives a light accent tone.
 */
object WallpaperAccent {
    private fun wallpaperFile(): File? {
        val fromRegistry = runCatching {
            val out = ProcessBuilder("reg", "query", "HKCU\\Control Panel\\Desktop", "/v", "WallPaper")
                .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
            Regex("REG_SZ\\s+(.+)").find(out)?.groupValues?.get(1)?.trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isFile }
        // A slideshow or a picture set through Settings is mirrored here as a JPEG without an extension.
        val transcoded = System.getenv("APPDATA")?.let { File(it, "Microsoft\\Windows\\Themes\\TranscodedWallpaper") }?.takeIf { it.isFile }
        return fromRegistry ?: transcoded
    }

    fun read(): Long? = runCatching {
        if (hostOs != HostOs.WINDOWS) return null
        val src = ImageIO.read(wallpaperFile() ?: return null) ?: return null
        val n = 64
        val img = BufferedImage(n, n, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(src, 0, 0, n, n, null)
            dispose()
        }
        val bins = DoubleArray(36)
        val sinSum = DoubleArray(36)
        val cosSum = DoubleArray(36)
        val hsb = FloatArray(3)
        for (y in 0 until n) for (x in 0 until n) {
            val rgb = img.getRGB(x, y)
            java.awt.Color.RGBtoHSB((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, hsb)
            if (hsb[2] < 0.18f || hsb[1] < 0.18f) continue
            val w = (hsb[1] * hsb[2]).toDouble()
            val b = ((hsb[0] * 36).toInt()).coerceIn(0, 35)
            bins[b] += w
            sinSum[b] += sin(hsb[0] * 2 * Math.PI) * w
            cosSum[b] += cos(hsb[0] * 2 * Math.PI) * w
        }
        // the strongest hue, smoothed with its neighbours so one noisy bin can't win
        val best = (0 until 36).maxByOrNull { bins[it] + 0.5 * (bins[(it + 35) % 36] + bins[(it + 1) % 36]) } ?: return null
        if (bins[best] < 1.0) return null // a grey or black wallpaper: nothing to take
        var hue = (atan2(sinSum[best], cosSum[best]) / (2 * Math.PI)).toFloat()
        if (hue < 0) hue += 1f
        val c = java.awt.Color.HSBtoRGB(hue, 0.42f, 1f)
        0xFF000000L or (c.toLong() and 0xFFFFFF)
    }.getOrNull()
}
