package app.glint.ui.theme

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.lerp
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

// Chrome and Gold: flowing liquid-metal tubes.

private class TubeLook(
    val base: List<Color>,
    /** Concentric strokes, outermost first, with their width relative to the tube. */
    val layers: List<Pair<Color, Float>>,
    val bubble: List<Pair<Float, Color>>,
    val bubbleRim: Color,
)

private val Tubes: List<Pair<Path, Float>> by lazy {
    // Tubes in a 390 x 844 design space, scaled to the screen.
    listOf(
        Path().apply {
            moveTo(-70f, 20f)
            cubicTo(-10f, 40f, 40f, 0f, 70f, -70f)
        } to 72f,
        Path().apply {
            moveTo(445f, 205f)
            cubicTo(350f, 210f, 318f, 290f, 360f, 345f)
            cubicTo(402f, 400f, 432f, 432f, 445f, 525f)
        } to 78f,
        Path().apply {
            moveTo(-70f, 455f)
            lineTo(-16f, 470f)
        } to 113f,
        Path().apply {
            moveTo(-60f, 770f)
            cubicTo(40f, 705f, 130f, 695f, 190f, 745f)
            cubicTo(250f, 795f, 300f, 835f, 360f, 795f)
            cubicTo(420f, 755f, 430f, 705f, 470f, 725f)
        } to 72f,
        Path().apply {
            moveTo(335f, 905f)
            cubicTo(300f, 825f, 340f, 745f, 405f, 735f)
        } to 82f,
    )
}

private val TubeBubbles =
    listOf(
        Triple(262f, 52f, 15f),
        Triple(300f, 95f, 7f),
        Triple(60f, 560f, 22f),
        Triple(18f, 612f, 6f),
        Triple(250f, 700f, 13f),
    )

private fun DrawScope.drawTubes(look: TubeLook) {
    drawRect(Brush.radialGradient(look.base, center = center, radius = size.maxDimension * 0.55f))
    withTransform({ scale(size.width / 390f, size.height / 844f, pivot = Offset.Zero) }) {
        Tubes.forEach { (path, width) ->
            look.layers.forEach { (color, factor) ->
                drawPath(path, color, style = Stroke(width = width * factor, cap = StrokeCap.Round))
            }
        }
        TubeBubbles.forEach { (x, y, r) ->
            val c = Offset(x, y)
            drawCircle(
                Brush.radialGradient(
                    *look.bubble.toTypedArray(),
                    center = Offset(x - r * 0.3f, y - r * 0.4f),
                    radius = r * 1.5f,
                ),
                radius = r,
                center = c,
            )
            drawCircle(look.bubbleRim, radius = r, center = c, style = Stroke(1f))
        }
    }
}

private val ChromeLook =
    TubeLook(
        base = listOf(Color(0xFF0A101C), Color(0xFF030406)),
        layers =
            listOf(
                Color(0x1A2F6BFF) to 1.36f,
                Color(0x2E2F6BFF) to 1.24f,
                Color(0x522F6BFF) to 1.13f,
                Color(0xFF8FB4FF) to 1.055f,
                Color(0xFF1A1E26) to 1f,
                Color(0xFF6F7787) to 0.8f,
                Color(0xFFB9BFCB) to 0.55f,
                Color(0xFFEEF1F6) to 0.28f,
                Color.White to 0.083f,
            ),
        bubble =
            listOf(
                0f to Color.White,
                0.3f to Color(0xFFCFD5DF),
                0.65f to Color(0xFF454B58),
                1f to Color(0xFF0A0C11),
            ),
        bubbleRim = Color(0x998FB4FF),
    )

private val GoldLook =
    TubeLook(
        base = listOf(Color(0xFF140D04), Color(0xFF040302)),
        layers =
            listOf(
                Color(0x1AFFA820) to 1.36f,
                Color(0x2EFFA820) to 1.24f,
                Color(0x52FFA820) to 1.13f,
                Color(0xFFFFD27A) to 1.055f,
                Color(0xFF1E1407) to 1f,
                Color(0xFF7A5A26) to 0.8f,
                Color(0xFFC9A55C) to 0.55f,
                Color(0xFFF6E2B0) to 0.28f,
                Color.White to 0.083f,
            ),
        bubble =
            listOf(
                0f to Color.White,
                0.3f to Color(0xFFF1DDAF),
                0.65f to Color(0xFF6B5226),
                1f to Color(0xFF120D05),
            ),
        bubbleRim = Color(0x99FFD27A),
    )

/** Liquid chrome on black with a blue rim glow and small silver bubbles. */
internal fun DrawScope.drawChrome() = drawTubes(ChromeLook)

/** The chrome tubes in polished gold with an amber glow. */
internal fun DrawScope.drawGold() = drawTubes(GoldLook)

// Aurora: northern lights over a starry sky and dark mountains.

private class AuroraBand(
    val points: List<Offset>,
    val base: Color,
    val top: Color,
    /** How tall the light curtain rises, as a fraction of the height. */
    val length: Float,
    val alpha: Float,
    val phase: Float,
)

private val AuroraBands =
    listOf(
        AuroraBand(
            listOf(Offset(-0.1f, 0.56f), Offset(0.4f, 0.48f), Offset(0.65f, 0.64f), Offset(1.1f, 0.54f)),
            base = Color(0xFF34F5A4), top = Color(0xFF3B82F6), length = 0.12f, alpha = 0.08f, phase = 2.1f),
        AuroraBand(
            listOf(Offset(-0.1f, 0.12f), Offset(0.4f, 0.24f), Offset(0.7f, 0.02f), Offset(1.1f, 0.10f)),
            base = Color(0xFF2DD4BF), top = Color(0xFF6366F1), length = 0.12f, alpha = 0.16f, phase = 4.0f),
        AuroraBand(
            listOf(Offset(-0.1f, 0.32f), Offset(0.3f, 0.10f), Offset(0.65f, 0.40f), Offset(1.1f, 0.16f)),
            base = Color(0xFF34F5A4), top = Color(0xFFA855F7), length = 0.26f, alpha = 0.24f, phase = 0.7f),
    )

private val FarRidge =
    listOf(0f to 0.80f, 0.12f to 0.76f, 0.22f to 0.79f, 0.35f to 0.72f, 0.47f to 0.78f, 0.58f to 0.75f,
        0.70f to 0.80f, 0.82f to 0.73f, 0.92f to 0.77f, 1f to 0.74f)

private val NearRidge =
    listOf(0f to 0.86f, 0.1f to 0.84f, 0.2f to 0.88f, 0.32f to 0.83f, 0.45f to 0.89f, 0.6f to 0.85f,
        0.75f to 0.90f, 0.88f to 0.86f, 1f to 0.88f)

internal fun DrawScope.drawAurora() {
    val w = size.width
    val h = size.height
    drawRect(
        Brush.verticalGradient(0f to Color(0xFF010309), 0.5f to Color(0xFF03101F), 1f to Color(0xFF041019)))
    drawStars(count = 150, seed = 7, bottom = 0.75f, maxRadius = 1.4.dp)
    drawRect(
        Brush.radialGradient(
            0f to Color(0xFF1FD39A).copy(alpha = 0.14f),
            1f to Color(0xFF1FD39A).copy(alpha = 0f),
            center = at(0.5f, 0.8f),
            radius = 0.75f * w,
        ))

    AuroraBands.forEach { band ->
        val (p0, p1, p2, p3) = band.points.map { at(it.x, it.y) }
        val curve = Path().apply {
            moveTo(p0.x, p0.y)
            cubicTo(p1.x, p1.y, p2.x, p2.y, p3.x, p3.y)
        }
        // A soft glow along the bright lower edge.
        listOf(0.10f to 0.05f, 0.06f to 0.07f, 0.025f to 0.10f).forEach { (width, alpha) ->
            drawPath(
                curve,
                band.base.copy(alpha = alpha * band.alpha / 0.24f),
                style = Stroke(width = width * h, cap = StrokeCap.Round),
            )
        }
        // The curtain: thin rays rising from the curve, fading upward.
        val rays = 160
        val middle = lerp(band.base, band.top, 0.5f)
        for (i in 0..rays) {
            val t = i / rays.toFloat()
            val point = cubicPoint(p0, p1, p2, p3, t)
            val length =
                h * band.length * (0.65f + 0.35f * sin(t * PI.toFloat() * 5f + band.phase)) *
                    (0.7f + 0.3f * sin(t * PI.toFloat() * 13f + band.phase * 2f))
            val start = point.y + 0.012f * h
            val end = point.y - length
            drawLine(
                Brush.verticalGradient(
                    0f to band.base.copy(alpha = 0f),
                    0.06f to band.base.copy(alpha = band.alpha),
                    0.35f to middle.copy(alpha = band.alpha * 0.6f),
                    1f to band.top.copy(alpha = 0f),
                    startY = start,
                    endY = end,
                ),
                start = Offset(point.x, start),
                end = Offset(point.x, end),
                strokeWidth = w / rays * 2.2f,
                cap = StrokeCap.Butt,
            )
        }
    }

    fun ridge(points: List<Pair<Float, Float>>, fill: Color, rim: Color?) {
        val line = Path().apply {
            points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * w, y * h) else lineTo(x * w, y * h) }
        }
        val shape = Path().apply {
            addPath(line)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(shape, fill)
        if (rim != null) drawPath(line, rim, style = Stroke(width = 0.6.dp.toPx()))
    }
    ridge(FarRidge, Color(0xFF04131D), Color(0xFF34F5A4).copy(alpha = 0.3f))
    ridge(NearRidge, Color(0xFF01070D), null)
}

// Dunes: the desert at dusk, a low sun behind layered sand.

internal fun DrawScope.drawDunes() {
    val w = size.width
    val h = size.height
    drawRect(
        Brush.verticalGradient(
            0f to Color(0xFF090616),
            0.30f to Color(0xFF1F0F33),
            0.50f to Color(0xFF4A1B3A),
            0.62f to Color(0xFF8A3530),
            0.70f to Color(0xFFC25A2A),
        ))
    drawStars(count = 70, seed = 11, bottom = 0.32f, maxRadius = 1.2.dp, maxAlpha = 0.75f)

    val sun = at(0.68f, 0.665f)
    val sunGlow = Color(0xFFFF9A4D)
    drawRect(
        Brush.radialGradient(
            0f to sunGlow.copy(alpha = 0.45f),
            0.35f to sunGlow.copy(alpha = 0.15f),
            1f to sunGlow.copy(alpha = 0f),
            center = sun,
            radius = 0.6f * w,
        ))
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFFFFE2B0), Color(0xFFFFB066)), center = sun, radius = 0.075f * w),
        radius = 0.075f * w,
        center = sun,
    )

    /** A dune whose crest starts at [start] and follows cubic [segments] of six fractions each. */
    fun dune(start: Offset, segments: List<FloatArray>, top: Float, near: Color, far: Color, crest: Color?) {
        val edge = Path().apply {
            moveTo(start.x * w, start.y * h)
            segments.forEach { s -> cubicTo(s[0] * w, s[1] * h, s[2] * w, s[3] * h, s[4] * w, s[5] * h) }
        }
        val shape = Path().apply {
            addPath(edge)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(shape, Brush.verticalGradient(listOf(near, far), startY = top * h, endY = h))
        if (crest != null) drawPath(edge, crest, style = Stroke(width = 1.2.dp.toPx()))
    }

    dune(
        Offset(0f, 0.69f),
        listOf(floatArrayOf(0.2f, 0.655f, 0.38f, 0.70f, 0.55f, 0.675f), floatArrayOf(0.72f, 0.65f, 0.86f, 0.70f, 1f, 0.68f)),
        top = 0.65f, near = Color(0xFF9A4426), far = Color(0xFF3D160C), crest = null,
    )
    // Haze where the sand meets the sky.
    val haze = Color(0xFFFF8A4C)
    drawRect(
        Brush.verticalGradient(
            0f to haze.copy(alpha = 0f),
            0.5f to haze.copy(alpha = 0.16f),
            1f to haze.copy(alpha = 0f),
            startY = 0.63f * h,
            endY = 0.75f * h,
        ),
        topLeft = Offset(0f, 0.63f * h),
        size = Size(w, 0.12f * h),
    )
    dune(
        Offset(0f, 0.74f),
        listOf(floatArrayOf(0.18f, 0.70f, 0.30f, 0.71f, 0.46f, 0.755f), floatArrayOf(0.60f, 0.79f, 0.78f, 0.72f, 1f, 0.735f)),
        top = 0.70f, near = Color(0xFF6E2B17), far = Color(0xFF210A05), crest = Color(0x8CF08A4B),
    )
    dune(
        Offset(0f, 0.84f),
        listOf(floatArrayOf(0.25f, 0.78f, 0.45f, 0.80f, 0.62f, 0.83f), floatArrayOf(0.78f, 0.86f, 0.9f, 0.80f, 1f, 0.79f)),
        top = 0.78f, near = Color(0xFF4B1A0D), far = Color(0xFF140503), crest = Color(0x59D9773F),
    )
    dune(
        Offset(0f, 0.93f),
        listOf(floatArrayOf(0.3f, 0.88f, 0.6f, 0.95f, 1f, 0.90f)),
        top = 0.88f, near = Color(0xFF26100A), far = Color(0xFF080201), crest = null,
    )
}

// Ocean: the deep sea, with light from the surface, drifting specks, bubbles and kelp.

private class Ray(val x: Float, val topWidth: Float, val bottomWidth: Float, val alpha: Float)

private val OceanRays =
    listOf(
        Ray(0.15f, 0.03f, 0.14f, 0.05f),
        Ray(0.3f, 0.05f, 0.2f, 0.08f),
        Ray(0.42f, 0.02f, 0.12f, 0.04f),
        Ray(0.55f, 0.04f, 0.22f, 0.07f),
        Ray(0.66f, 0.02f, 0.13f, 0.05f),
        Ray(0.8f, 0.05f, 0.2f, 0.08f),
        Ray(0.92f, 0.03f, 0.15f, 0.05f),
    )

/** Bubbles near the edges, as fractions of the screen with a radius in dp. */
private val OceanBubbles =
    listOf(
        Triple(0.97f, 0.30f, 7f),
        Triple(0.955f, 0.345f, 4f),
        Triple(0.03f, 0.52f, 9f),
        Triple(0.06f, 0.57f, 4.5f),
        Triple(0.975f, 0.66f, 5f),
        Triple(0.86f, 0.075f, 6f),
        Triple(0.92f, 0.11f, 3.5f),
    )

/** Kelp strands: a cubic curve from the sea floor upward, and a width in dp. */
private val Kelp =
    listOf(
        floatArrayOf(0.05f, 0.97f, 0.10f, 0.86f, 0.0f, 0.78f, 0.04f, 0.70f, 5f),
        floatArrayOf(0.10f, 0.97f, 0.16f, 0.88f, 0.07f, 0.82f, 0.12f, 0.76f, 4f),
        floatArrayOf(0.02f, 0.97f, 0.0f, 0.90f, 0.06f, 0.85f, 0.01f, 0.80f, 3.5f),
        floatArrayOf(0.93f, 0.97f, 0.88f, 0.88f, 0.98f, 0.82f, 0.93f, 0.74f, 5f),
        floatArrayOf(0.98f, 0.97f, 1.02f, 0.90f, 0.94f, 0.86f, 0.99f, 0.80f, 3.5f),
    )

internal fun DrawScope.drawOcean() {
    val w = size.width
    val h = size.height
    drawRect(
        Brush.verticalGradient(
            0f to Color(0xFF0A3550),
            0.25f to Color(0xFF062640),
            0.6f to Color(0xFF031427),
            1f to Color(0xFF01070F),
        ))
    val surfaceLight = Color(0xFF3FB7E0)
    drawRect(
        Brush.radialGradient(
            0f to surfaceLight.copy(alpha = 0.28f),
            1f to surfaceLight.copy(alpha = 0f),
            center = at(0.5f, -0.1f),
            radius = 0.9f * w,
        ))

    val rayColor = Color(0xFFD8F6FF)
    val rayEnd = 0.75f * h
    OceanRays.forEachIndexed { i, ray ->
        val topX = ray.x * w
        val bottomX = topX + (ray.x - 0.5f) * 0.4f * w + (if (i % 2 == 1) 0.03f else -0.02f) * w
        val shape = Path().apply {
            moveTo(topX - ray.topWidth * w / 2f, 0f)
            lineTo(topX + ray.topWidth * w / 2f, 0f)
            lineTo(bottomX + ray.bottomWidth * w / 2f, rayEnd)
            lineTo(bottomX - ray.bottomWidth * w / 2f, rayEnd)
            close()
        }
        drawPath(
            shape,
            Brush.verticalGradient(
                listOf(rayColor.copy(alpha = ray.alpha), rayColor.copy(alpha = 0f)), startY = 0f, endY = rayEnd),
        )
    }

    drawStars(count = 90, seed = 23, top = 0.1f, maxRadius = 1.1.dp, color = Color(0xFFBFEFFF), minAlpha = 0.1f, maxAlpha = 0.45f)

    val bubbleTint = Color(0xFFBFEFFF)
    OceanBubbles.forEach { (fx, fy, radiusDp) ->
        val c = at(fx, fy)
        val r = radiusDp.dp.toPx()
        drawCircle(
            Brush.radialGradient(
                0f to bubbleTint.copy(alpha = 0f),
                0.7f to bubbleTint.copy(alpha = 0.04f),
                1f to bubbleTint.copy(alpha = 0.3f),
                center = c,
                radius = r,
            ),
            radius = r,
            center = c,
        )
        drawCircle(Color.White.copy(alpha = 0.35f), radius = r, center = c, style = Stroke(0.8.dp.toPx()))
        drawCircle(Color.White.copy(alpha = 0.8f), radius = 0.22f * r, center = c + Offset(-0.35f * r, -0.35f * r))
    }

    Kelp.forEach { k ->
        val strand = Path().apply {
            moveTo(k[0] * w, k[1] * h)
            cubicTo(k[2] * w, k[3] * h, k[4] * w, k[5] * h, k[6] * w, k[7] * h)
        }
        drawPath(strand, Color(0xFF0B3B36), style = Stroke(width = k[8].dp.toPx(), cap = StrokeCap.Round))
        drawPath(strand, Color(0x802E8C78), style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round))
    }

    val floor = Path().apply {
        moveTo(0f, 0.90f * h)
        cubicTo(0.2f * w, 0.87f * h, 0.35f * w, 0.93f * h, 0.55f * w, 0.91f * h)
        cubicTo(0.75f * w, 0.89f * h, 0.88f * w, 0.94f * h, w, 0.92f * h)
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }
    drawPath(floor, Brush.verticalGradient(listOf(Color(0xFF020A12), Color(0xFF000102)), startY = 0.88f * h, endY = h))
}

// Nebula: glowing clouds of gas in deep space, crossed by a dark dust lane.

private class Cloud(val x: Float, val y: Float, val radius: Float, val color: Color, val alpha: Float)

private val NebulaClouds =
    listOf(
        Cloud(0.82f, 0.16f, 0.42f, Color(0xFFC026D3), 0.30f),
        Cloud(0.18f, 0.34f, 0.48f, Color(0xFF6D28D9), 0.38f),
        Cloud(0.72f, 0.60f, 0.46f, Color(0xFF1D4ED8), 0.30f),
        Cloud(0.10f, 0.86f, 0.40f, Color(0xFFDB2777), 0.22f),
        Cloud(0.95f, 0.92f, 0.36f, Color(0xFF0891B2), 0.22f),
        Cloud(0.5f, 0.42f, 0.30f, Color(0xFF9333EA), 0.18f),
    )

/** Bright stars with a four-point flare, near the edges. */
private val NebulaBrightStars =
    listOf(0.78f to 0.045f, 0.965f to 0.38f, 0.035f to 0.55f, 0.95f to 0.86f, 0.06f to 0.18f)

internal fun DrawScope.drawNebula() {
    val w = size.width
    val h = size.height
    val m = size.maxDimension
    drawRect(Brush.verticalGradient(listOf(Color(0xFF07040F), Color(0xFF030208))))
    NebulaClouds.forEach { drawGlow(at(it.x, it.y), it.radius * m, it.color, it.alpha) }

    /** Overlapping glows along a curve, making a filament. */
    fun filament(points: List<Offset>, count: Int, smallest: Float, largest: Float, alpha: Float, color: (Float) -> Color) {
        val (p0, p1, p2, p3) = points.map { at(it.x, it.y) }
        for (i in 0 until count) {
            val t = i / (count - 1).toFloat()
            val radius = m * (smallest + (largest - smallest) * (0.5f + 0.5f * sin(t * 9f)))
            drawGlow(cubicPoint(p0, p1, p2, p3, t), radius, color(t), alpha)
        }
    }
    filament(
        listOf(Offset(1.05f, 0.05f), Offset(0.6f, 0.25f), Offset(0.55f, 0.55f), Offset(-0.05f, 0.75f)),
        count = 30, smallest = 0.06f, largest = 0.14f, alpha = 0.10f,
    ) { t -> lerp(Color(0xFFF472B6), Color(0xFF818CF8), t) }
    filament(
        listOf(Offset(1.05f, 0.10f), Offset(0.62f, 0.30f), Offset(0.5f, 0.58f), Offset(-0.05f, 0.82f)),
        count = 26, smallest = 0.03f, largest = 0.06f, alpha = 0.35f,
    ) { Color(0xFF020106) }

    drawStars(count = 230, seed = 31, maxRadius = 1.2.dp)

    val flare = 10.dp.toPx()
    val glow = 6.dp.toPx()
    NebulaBrightStars.forEach { (fx, fy) ->
        val c = at(fx, fy)
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.5f), Color.White.copy(alpha = 0f)), center = c, radius = glow),
            radius = glow,
            center = c,
        )
        val fade = listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.7f), Color.White.copy(alpha = 0f))
        drawLine(
            Brush.horizontalGradient(fade, startX = c.x - flare, endX = c.x + flare),
            start = Offset(c.x - flare, c.y),
            end = Offset(c.x + flare, c.y),
            strokeWidth = 0.8.dp.toPx(),
        )
        drawLine(
            Brush.verticalGradient(fade, startY = c.y - flare, endY = c.y + flare),
            start = Offset(c.x, c.y - flare),
            end = Offset(c.x, c.y + flare),
            strokeWidth = 0.8.dp.toPx(),
        )
        drawCircle(Color.White, radius = 1.6.dp.toPx(), center = c)
    }
}

// Midnight: plain black with a faint glow, for OLED screens.

internal fun DrawScope.drawMidnight() {
    drawRect(Color.Black)
    val top = Color(0xFF1A2340)
    drawRect(
        Brush.radialGradient(
            0f to top.copy(alpha = 0.55f),
            1f to top.copy(alpha = 0f),
            center = at(0.5f, 0f),
            radius = 0.9f * size.width,
        ))
    val bottom = Color(0xFF0E1528)
    drawRect(
        Brush.radialGradient(
            0f to bottom.copy(alpha = 0.5f),
            1f to bottom.copy(alpha = 0f),
            center = at(0.5f, 1.05f),
            radius = 0.7f * size.width,
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

// A picture chosen from the gallery.

/** Fills the screen with [image], cropped at its centre, and dims it so text stays readable. */
internal fun DrawScope.drawPhoto(image: ImageBitmap) {
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
    drawRect(Color.Black.copy(alpha = 0.3f))
}
