package app.glint.ui.theme

import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect

/**
 * Reusable lens for one glass panel. Its input must contain only the backdrop: never
 * pass the panel's text or icons through the effect. The caller clips the final result.
 *
 * Width and height describe the visible panel, in pixels. The input layer can extend
 * [overscan] pixels on every side: its panel origin is then (overscan, overscan), and
 * the caller draws the result at (-overscan, -overscan). This gives blur and refraction
 * real neighbouring pixels instead of repeating a clipped panel edge.
 */
internal interface GlassRefraction {
    fun effect(
        width: Float,
        height: Float,
        cornerRadius: Float,
        density: Float,
        press: Float = 0f,
        overscan: Float = 0f,
        frostRadius: Float = 2f * density,
    ): RenderEffect?

    companion object {
        /** AGSL is available from Android 13. Older devices use the caller's blur fallback. */
        fun create(): GlassRefraction? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
            return try {
                RuntimeGlassRefraction()
            } catch (error: IllegalArgumentException) {
                // A shader compilation error must be visible in the device log, while
                // still allowing the app to use its frosted fallback on that device.
                Log.e("GlassRefraction", "The glass lens shader could not be compiled", error)
                null
            }
        }
    }
}

/** Convenience for size-cached callers. Animated panels should reuse [GlassRefraction]. */
internal fun buildGlassRefractionEffect(
    width: Float,
    height: Float,
    cornerRadius: Float,
    density: Float,
    press: Float = 0f,
    overscan: Float = 0f,
    frostRadius: Float = 2f * density,
): RenderEffect? =
    GlassRefraction.create()?.effect(width, height, cornerRadius, density, press, overscan, frostRadius)

private data class LensConfiguration(
    val width: Float,
    val height: Float,
    val radius: Float,
    val density: Float,
    val press: Float,
    val overscan: Float,
    val frost: Float,
)

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class RuntimeGlassRefraction : GlassRefraction {
    // Keep a separate RuntimeShader for each panel; sharing mutable uniforms would
    // make differently sized panels overwrite one another's lens geometry.
    private val shader = RuntimeShader(GlassLensShader)
    private var previous: LensConfiguration? = null
    private var previousEffect: RenderEffect? = null

    override fun effect(
        width: Float,
        height: Float,
        cornerRadius: Float,
        density: Float,
        press: Float,
        overscan: Float,
        frostRadius: Float,
    ): RenderEffect? {
        if (!width.isFinite() || !height.isFinite() || !density.isFinite() ||
            width <= 0f || height <= 0f || density <= 0f) return null

        val config = LensConfiguration(
            width = width,
            height = height,
            radius = cornerRadius.coerceIn(0f, minOf(width, height) * 0.5f),
            density = density,
            press = press.coerceIn(0f, 1f),
            overscan = overscan.coerceAtLeast(0f),
            frost = frostRadius.coerceAtLeast(0f),
        )
        if (config == previous) return previousEffect

        shader.setFloatUniform("panelSize", config.width, config.height)
        shader.setFloatUniform("cornerRadius", config.radius)
        shader.setFloatUniform("pixelDensity", config.density)
        shader.setFloatUniform("pressure", config.press)
        shader.setFloatUniform("overscan", config.overscan)

        val lens = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
        val nativeEffect = if (config.frost > 0f) {
            // Blur the source first, then refract it. Blurring the completed lens
            // would erase the thin specular rim and the opposing inner caustic.
            android.graphics.RenderEffect.createChainEffect(
                lens,
                android.graphics.RenderEffect.createBlurEffect(
                    config.frost, config.frost, Shader.TileMode.CLAMP,
                ),
            )
        } else lens

        return nativeEffect.asComposeRenderEffect().also {
            previous = config
            previousEffect = it
        }
    }
}

/**
 * The signed distance gives depth inside the rounded panel; its outward normal is
 * the direction of the edge's curved lens. The source point moves inward by up to
 * 10–11dp over an 18dp edge band, while the centre has a gentle 2% magnification.
 * Therefore a line moving behind the panel actually bends and expands at its rim.
 */
private const val GlassLensShader = """
    uniform shader content;
    uniform float2 panelSize;
    uniform float cornerRadius;
    uniform float pixelDensity;
    uniform float pressure;
    uniform float overscan;

    half4 main(float2 coordinate) {
        float2 halfSize = panelSize * 0.5;
        float2 point = coordinate - float2(overscan) - halfSize;
        float2 q = abs(point) - (halfSize - float2(cornerRadius));
        float2 outside = max(q, float2(0.0));
        float outsideLength = length(outside);
        float distance = outsideLength + min(max(q.x, q.y), 0.0) - cornerRadius;

        // Padding remains ordinary backdrop; the outer Compose clip owns coverage.
        if (distance > 0.0) return content.eval(coordinate);

        float2 normal;
        if (outsideLength > 0.001) {
            normal = outside / outsideLength;
        } else {
            normal = q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0);
        }
        normal *= float2(point.x < 0.0 ? -1.0 : 1.0, point.y < 0.0 ? -1.0 : 1.0);

        float depth = max(-distance, 0.0);
        float shortHalf = min(halfSize.x, halfSize.y);
        float edgeWidth = max(1.0, min(18.0 * pixelDensity, shortHalf * 0.8));
        float edge = 1.0 - smoothstep(0.0, edgeWidth, depth);
        // Cap the displacement slope below the central sampling scale: the lens
        // should stretch a line at the bevel, without folding it back on itself.
        float bend = min((10.0 + 3.0 * pressure) * pixelDensity, min(shortHalf * 0.42, edgeWidth * 0.62));
        bend *= edge;
        float magnification = 1.02 + 0.012 * pressure;
        float2 samplePoint = float2(overscan) + halfSize + point / magnification - normal * bend;

        // Keep dispersion below one physical dp; it should read as a lens edge,
        // rather than a visible rainbow or an RGB glitch around the labels.
        float dispersion = 0.35 * pixelDensity * edge * edge;
        float2 minimum = float2(0.5);
        float2 maximum = panelSize + float2(2.0 * overscan) - float2(0.5);
        half4 middle = content.eval(clamp(samplePoint, minimum, maximum));
        half4 red = content.eval(clamp(samplePoint + normal * dispersion, minimum, maximum));
        half4 blue = content.eval(clamp(samplePoint - normal * dispersion, minimum, maximum));

        float2 lightDirection = normalize(float2(-0.65, -0.76));
        float facing = max(dot(normal, lightDirection), 0.0);
        float opposite = max(dot(normal, -lightDirection), 0.0);
        float rim = 1.0 - smoothstep(0.0, 1.4 * pixelDensity, depth);
        float causticDistance = (depth - 4.0 * pixelDensity) / (2.0 * pixelDensity);
        float caustic = exp(-causticDistance * causticDistance);
        float highlight = rim * (0.07 + 0.36 * pow(facing, 3.0) + 0.11 * pow(opposite, 3.0));
        highlight += caustic * 0.10 * pow(opposite, 4.0);
        highlight *= 1.0 + 0.18 * pressure;

        half alpha = max(middle.a, max(red.a, blue.a));
        half3 rgb = half3(red.r, middle.g, blue.b);
        rgb += half3(0.92, 0.97, 1.0) * half(highlight) * alpha;
        return half4(clamp(rgb, half3(0.0), half3(alpha)), alpha);
    }
"""
