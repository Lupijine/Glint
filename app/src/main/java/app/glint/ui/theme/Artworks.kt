package app.glint.ui.theme

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntSize
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Theme artworks. Each one fills the whole screen and is drawn once, then kept.
 *
 * Positions are fractions of the screen (0 to 1) so the artwork fits any phone; small details
 * such as stars use dp so they look the same size everywhere. Artworks stay dark, keep bright
 * shapes near the edges, and leave the middle calm: lists and text sit there.
 */

/** Draws a theme's artwork with the darkened middle that keeps text readable. */
internal fun DrawScope.drawThemeArtwork(theme: AppTheme) {
    if (size.minDimension <= 0f) return
    theme.artwork(this)
    if (theme.scrim > 0f) {
        drawRect(
            Brush.radialGradient(
                0f to theme.background.copy(alpha = theme.scrim),
                0.7f to Color.Transparent,
                center = Offset(size.width / 2f, size.height * 0.48f),
                radius = size.maxDimension * 0.6f,
            ))
    }
}

/**
 * The share sheet's own glass material, in the theme's colours: its glass tint, a faint
 * copy of its artwork, and soft glows of its accent and second colour. It is translucent, so
 * the system blur of the app behind still shows through it.
 */
internal fun DrawScope.drawShareMaterial(theme: AppTheme) {
    if (size.minDimension <= 0f) return
    drawRect(theme.glass.copy(alpha = 0.18f))
    // The artwork at a quarter strength: enough to give the glass something to bend.
    drawIntoCanvas { it.saveLayer(size.toRect(), Paint().apply { alpha = 0.25f }) }
    drawThemeArtwork(theme)
    drawIntoCanvas { it.restore() }
    drawGlow(at(0.08f, 0f), 0.9f * size.width, theme.accent, 0.20f)
    drawGlow(at(0.95f, 1f), 0.8f * size.width, theme.secondary, 0.16f)
}

/**
 * Records [block] into [layer] at this scope's size, with a fixed density.
 *
 * Do not use DrawScope's own `layer.record { }` for drawing that reads the density (any
 * `dp.toPx()`): inside a layout node's draw, it makes the density refer back to itself, and the
 * first `toPx()` recurses until the stack overflows.
 */
internal fun DrawScope.recordInto(layer: GraphicsLayer, block: DrawScope.() -> Unit) {
    layer.record(Density(density, fontScale), layoutDirection, size.toIntSize(), block)
}

/** A fixed sequence of random numbers, so an artwork looks the same every time. */
internal class Seeded(seed: Int) {
    private var state = seed

    fun next(): Float {
        state = state * 1103515245 + 12345
        return ((state ushr 8) and 0xFFFFFF) / 16777216f
    }
}

/** A point given as fractions of the screen. */
internal fun DrawScope.at(x: Float, y: Float) = Offset(x * size.width, y * size.height)

/** A point on a cubic curve, for placing things along it. */
internal fun cubicPoint(p0: Offset, p1: Offset, p2: Offset, p3: Offset, t: Float): Offset {
    val u = 1f - t
    val a = u * u * u
    val b = 3f * u * u * t
    val c = 3f * u * t * t
    val d = t * t * t
    return Offset(a * p0.x + b * p1.x + c * p2.x + d * p3.x, a * p0.y + b * p1.y + c * p2.y + d * p3.y)
}

/** Scattered stars or specks between [top] and [bottom], fractions of the height. */
internal fun DrawScope.drawStars(
    count: Int,
    seed: Int,
    top: Float = 0f,
    bottom: Float = 1f,
    maxRadius: Dp = 1.3.dp,
    color: Color = Color.White,
    minAlpha: Float = 0.2f,
    maxAlpha: Float = 0.9f,
) {
    val random = Seeded(seed)
    val largest = maxRadius.toPx()
    repeat(count) {
        val x = random.next() * size.width
        val y = (top + random.next() * (bottom - top)) * size.height
        val s = random.next()
        // Most stars are small; a few are large.
        val radius = (0.35f + s * s * 0.65f) * largest
        val alpha = minAlpha + random.next() * (maxAlpha - minAlpha)
        drawCircle(color.copy(alpha = alpha), radius, Offset(x, y))
    }
}

/** A soft round glow that fades to nothing at [radius]. */
private fun DrawScope.drawGlow(center: Offset, radius: Float, color: Color, alpha: Float) {
    drawRect(
        Brush.radialGradient(
            0f to color.copy(alpha = alpha),
            0.5f to color.copy(alpha = alpha * 0.45f),
            1f to color.copy(alpha = 0f),
            center = center,
            radius = radius,
        ))
}

// Caustics: light from the surface rippling over a pool floor at night.

/** A repeatable random value for a grid cell. */
private fun cellHash(ix: Int, iy: Int, seed: Int): Float {
    var h = ix * 374761393 + iy * 668265263 + seed * 1442695041
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return ((h ushr 8) and 0xFFFFFF) / 16777216f
}

private fun smoothStep(from: Float, to: Float, x: Float): Float {
    val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** How close (u, v) is to the border between two cells of a jittered grid: 0 on the border. */
private fun cellEdge(u: Float, v: Float, seed: Int): Float {
    val iu = floor(u).toInt()
    val iv = floor(v).toInt()
    var nearest = 9f
    var second = 9f
    for (dy in -1..1) for (dx in -1..1) {
        val cx = iu + dx
        val cy = iv + dy
        val px = cx + 0.15f + 0.7f * cellHash(cx, cy, seed)
        val py = cy + 0.15f + 0.7f * cellHash(cx, cy, seed + 1)
        val d = hypot(px - u, py - v)
        if (d < nearest) {
            second = nearest
            nearest = d
        } else if (d < second) second = d
    }
    return second - nearest
}

/** The light network, small and soft: it is drawn stretched, which keeps its lines gentle. */
private const val CausticColumns = 144
private var causticCache: ImageBitmap? = null

private fun causticImage(rows: Int): ImageBitmap {
    causticCache?.takeIf { it.height == rows }?.let { return it }
    val columns = CausticColumns
    val cells = 5.5f
    val pixels = IntArray(columns * rows)
    for (y in 0 until rows) {
        val fy = y / rows.toFloat()
        // Brightest near the surface, quieter in the middle and the depths.
        val depth = 0.9f - 0.55f * smoothStep(0.05f, 0.6f, fy) + 0.25f * smoothStep(0.75f, 1f, fy)
        for (x in 0 until columns) {
            val u = x / columns.toFloat() * cells
            val v = y / columns.toFloat() * cells
            val wu = u + 0.32f * sin(v * 1.9f + 1.3f) + 0.18f * sin(u * 2.6f + v * 0.8f)
            val wv = v + 0.32f * sin(u * 1.6f + 0.4f) + 0.18f * sin(v * 2.3f - u * 0.6f)
            val a = 1f - smoothStep(0f, 0.16f, cellEdge(wu, wv, 3))
            val b = 1f - smoothStep(0f, 0.12f, cellEdge(wu * 1.7f + 4.1f, wv * 1.7f + 2.3f, 9))
            val light = min(1f, 0.8f * a * a + 0.45f * b * b) * depth
            val alpha = (255f * min(1f, light * 0.52f)).roundToInt()
            val red = (127f + 128f * light).roundToInt().coerceAtMost(255)
            val green = (231f + 24f * light).roundToInt().coerceAtMost(255)
            pixels[y * columns + x] = (alpha shl 24) or (red shl 16) or (green shl 8) or 255
        }
    }
    return Bitmap.createBitmap(pixels, columns, rows, Bitmap.Config.ARGB_8888)
        .asImageBitmap()
        .also { causticCache = it }
}

internal fun DrawScope.drawCaustics() {
    val w = size.width
    val h = size.height
    drawRect(
        Brush.verticalGradient(0f to Color(0xFF0B3A55), 0.45f to Color(0xFF06233A), 1f to Color(0xFF020D17)))
    val surface = Color(0xFF4FC3E8)
    drawRect(
        Brush.radialGradient(
            0f to surface.copy(alpha = 0.30f),
            1f to surface.copy(alpha = 0f),
            center = at(0.5f, -0.1f),
            radius = 0.9f * w,
        ))
    val rows = (CausticColumns * h / w).roundToInt().coerceIn(16, 1024)
    drawImage(
        causticImage(rows),
        dstSize = IntSize(w.roundToInt(), h.roundToInt()),
        filterQuality = FilterQuality.High,
    )
}

// Zellige: eight-pointed stars in emerald and cobalt, gold outlines and small diamonds.

/** An eight-pointed star of two overlapping squares, [radius] to its tips. */
private fun eightPointStar(cx: Float, cy: Float, radius: Float): Path {
    val inner = radius * 0.7654f
    return Path().apply {
        for (k in 0 until 16) {
            val angle = k * PI.toFloat() / 8f
            val r = if (k % 2 == 1) inner else radius
            val x = cx + r * cos(angle)
            val y = cy + r * sin(angle)
            if (k == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}

internal fun DrawScope.drawZellige() {
    val w = size.width
    val h = size.height
    drawRect(
        Brush.radialGradient(
            listOf(Color(0xFF091015), Color(0xFF05080B)),
            center = at(0.5f, 0.45f),
            radius = size.maxDimension * 0.7f,
        ))
    val s = 72.dp.toPx()
    val star = 0.40f * s
    val diamond = 0.11f * s
    val columns = ceil(w / s).toInt() + 2
    val rows = ceil(h / s).toInt() + 2
    val ox = (w - (columns - 1) * s) / 2f
    val oy = (h - (rows - 1) * s) / 2f
    val line = Stroke(0.9.dp.toPx())

    // Short bands joining each star's tips to its neighbours and to the diamonds between.
    val tip = star * 0.7071f
    val bands = Path()
    for (j in 0 until rows) for (i in 0 until columns) {
        val cx = ox + i * s
        val cy = oy + j * s
        bands.moveTo(cx + star, cy)
        bands.lineTo(cx + s - star, cy)
        bands.moveTo(cx, cy + star)
        bands.lineTo(cx, cy + s - star)
        bands.moveTo(cx + tip, cy + tip)
        bands.lineTo(cx + s / 2f - diamond / 2f, cy + s / 2f - diamond / 2f)
        bands.moveTo(cx - tip, cy + tip)
        bands.lineTo(cx - s / 2f + diamond / 2f, cy + s / 2f - diamond / 2f)
    }
    drawPath(bands, Color(0x33C9D3DE), style = line)

    for (j in 0 until rows) for (i in 0 until columns) {
        val cx = ox + i * s
        val cy = oy + j * s
        val shape = eightPointStar(cx, cy, star)
        drawPath(shape, if ((i + j) % 2 == 1) Color(0x610F4A3E) else Color(0x61163468))
        drawPath(shape, Color(0x52D8B26A), style = line)
        val dx = cx + s / 2f
        val dy = cy + s / 2f
        val rhombus = Path().apply {
            moveTo(dx, dy - diamond)
            lineTo(dx + diamond, dy)
            lineTo(dx, dy + diamond)
            lineTo(dx - diamond, dy)
            close()
        }
        drawPath(rhombus, Color(0x0FFFFFFF))
        drawPath(rhombus, Color(0x38C9D3DE), style = line)
    }
}

// Bokeh: city lights at night, out of focus, kept to the top and the bottom.

private val BokehPalette =
    listOf(
        Color(0xFFFFB347),
        Color(0xFFFFE7C2),
        Color(0xFFFF4FA3),
        Color(0xFF4FD8FF),
        Color(0xFF9B7BFF),
        Color(0xFFFFB347),
    )

internal fun DrawScope.drawBokeh() {
    val w = size.width
    val h = size.height
    drawRect(
        Brush.verticalGradient(0f to Color(0xFF0B0718), 0.5f to Color(0xFF120A22), 1f to Color(0xFF05040A)))
    val random = Seeded(57)
    repeat(70) {
        val band = random.next()
        val x = random.next() * w
        // Most lights sit near the top or the bottom; the middle stays calm for lists.
        val y =
            when {
                band < 0.48f -> random.next() * 0.34f
                band < 0.9f -> 0.68f + random.next() * 0.32f
                else -> 0.34f + random.next() * 0.34f
            } * h
        val s = random.next()
        val radius = (8f + s * s * s * 38f).dp.toPx()
        val color = BokehPalette[(random.next() * BokehPalette.size).toInt().coerceAtMost(BokehPalette.size - 1)]
        val alpha = 0.10f + random.next() * 0.25f
        val c = Offset(x, y)
        drawCircle(
            Brush.radialGradient(
                0f to color.copy(alpha = alpha * 0.7f),
                0.82f to color.copy(alpha = alpha * 0.85f),
                0.95f to color.copy(alpha = alpha),
                1f to color.copy(alpha = 0f),
                center = c,
                radius = radius,
            ),
            radius = radius,
            center = c,
            blendMode = BlendMode.Screen,
        )
    }
    drawStars(
        count = 40, seed = 61, top = 0.36f, bottom = 0.62f, maxRadius = 1.0.dp,
        color = Color(0xFFFFE7C2), minAlpha = 0.15f, maxAlpha = 0.5f,
    )
}

// Pictures: the ones that ship with Glint, and one chosen from the gallery.

/** Fills the screen with a picture that ships with Glint, once it is decoded. */
internal fun DrawScope.drawBundled(@DrawableRes res: Int) {
    BundledArt.image(res, size.width)?.let { drawPhoto(it, dim = 0f) }
}

/** Fills the screen with [image], cropped at its centre, and dims it so text stays readable. */
internal fun DrawScope.drawPhoto(image: ImageBitmap, dim: Float = 0.3f) {
    val w = size.width
    val h = size.height
    if (image.width <= 0 || image.height <= 0) return
    val scale = max(w / image.width, h / image.height)
    val sourceWidth = (w / scale).roundToInt().coerceIn(1, image.width)
    val sourceHeight = (h / scale).roundToInt().coerceIn(1, image.height)
    drawImage(
        image,
        srcOffset = IntOffset((image.width - sourceWidth) / 2, (image.height - sourceHeight) / 2),
        srcSize = IntSize(sourceWidth, sourceHeight),
        dstSize = IntSize(w.roundToInt(), h.roundToInt()),
        filterQuality = FilterQuality.High,
    )
    if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
}
