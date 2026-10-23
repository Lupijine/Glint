package app.glint.ui.theme

import android.os.Build
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** Accessibility fallback: opaque surfaces, without lenses or decorative light. */
val LocalGlassEffectsEnabled = staticCompositionLocalOf { true }

/** A display list, never a CPU screenshot. A source must not sample its own layer. */
@Stable
class GlassBackdropState internal constructor(
    val layer: GraphicsLayer,
    internal val view: View,
) {
    internal var coordinates: LayoutCoordinates? = null
    internal var origin by mutableStateOf(Offset.Zero)
    internal var revision by mutableIntStateOf(0)
        private set
    private var recordingCount = 0

    internal fun recorded() {
        // Do not read revision here: subscribing a producer draw to its own revision
        // would schedule an endless redraw loop.
        revision = ++recordingCount
    }

    internal fun placed(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        origin = coordinates.localToWindow(Offset.Zero)
    }

    internal fun windowOrigin(): Offset {
        // Always observe placement: cached children can move without another draw.
        val placedOrigin = origin
        return coordinates?.takeIf { it.isAttached }?.localToWindow(Offset.Zero) ?: placedOrigin
    }
}

/** Independent wallpaper source for cards inside the captured page. */
val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdropState?> { null }

/** Page content source, provided only to the floating controls outside that page. */
val LocalLiveGlassBackdrop = staticCompositionLocalOf<GlassBackdropState?> { null }

/** The system, rather than our renderer, blurs another application behind this window. */
val LocalWindowBlurred = staticCompositionLocalOf { false }

private val SamplingSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun rememberGlassSceneState(): GlassBackdropState {
    val layer = rememberGraphicsLayer()
    val view = LocalView.current
    return remember(layer, view) { GlassBackdropState(layer, view) }
}

/** The original artwork stays cached and is also available to the page's glass cards. */
@Composable
fun GlassBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val theme = LocalAppTheme.current
    val effects = LocalGlassEffectsEnabled.current
    val state = rememberGlassSceneState()
    val artwork = remember(state, theme, effects) {
        Modifier.onGloballyPositioned(state::placed).drawWithCache {
            var recorded = false
            onDrawBehind {
                if (!recorded) {
                    recordInto(state.layer) {
                        if (effects) drawThemeArtwork(theme) else drawRect(theme.background)
                    }
                    state.recorded()
                    recorded = true
                }
                drawLayer(state.layer)
            }
        }
    }
    Box(modifier.then(artwork)) {
        CompositionLocalProvider(LocalGlassBackdrop provides if (effects) state else null) {
            content()
        }
    }
}

/**
 * Capture only the scrolling page, including its wallpaper. The dock and modal sheet
 * are siblings outside this source. Descendants can sample the independent wallpaper,
 * but cannot sample this scene: that would create a recursive render dependency.
 */
@Composable
fun GlassScene(
    state: GlassBackdropState,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val wallpaper = LocalGlassBackdrop.current
    val effects = LocalGlassEffectsEnabled.current && SamplingSupported
    val source = remember(state, wallpaper, effects) {
        Modifier.onGloballyPositioned(state::placed)
            // An unchanged scene must keep its own display list when a sibling
            // lens redraws. Without this boundary a root View paint could record
            // the source again, publish another revision and keep lenses drawing.
            .graphicsLayer()
            .drawWithContent {
                if (!effects) {
                    drawContent()
                } else {
                    val contentScope = this
                    val wallpaperOffset = state.windowOrigin() - (wallpaper?.windowOrigin() ?: Offset.Zero)
                    val recordingDensity = Density(density, fontScale)
                    // This is DrawScope's record extension, which retargets drawContent's
                    // canvas/draw node. recordInto() is only for standalone artwork.
                    state.layer.record {
                        // Compose's pre-2026-09 DrawScope.record delegates density back to
                        // this draw scope (AndroidX ea527c9). Break that cycle before the
                        // captured page converts any dp; retain the extension's draw-node restore.
                        drawContext.density = recordingDensity
                        if (wallpaper != null) {
                            translate(-wallpaperOffset.x, -wallpaperOffset.y) { drawLayer(wallpaper.layer) }
                        }
                        contentScope.drawContent()
                    }
                    state.recorded()
                    drawLayer(state.layer)
                }
            }
    }
    Box(modifier.then(source)) {
        CompositionLocalProvider(LocalLiveGlassBackdrop provides null) { content() }
    }
}

private fun lightRim(strong: Boolean): Brush =
    Brush.linearGradient(
        0f to Color.White.copy(alpha = if (strong) 0.52f else 0.32f),
        0.24f to Color.White.copy(alpha = 0.08f),
        0.5f to Color.Transparent,
        0.76f to Color.White.copy(alpha = 0.04f),
        1f to Color.White.copy(alpha = if (strong) 0.28f else 0.16f),
    )

private val GlassSheen = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.07f),
    0.25f to Color.White.copy(alpha = 0.018f),
    1f to Color.Transparent,
)

/**
 * Page cards refract only the independent cached artwork. Scrolling does not cause
 * a live page capture per card; the labels are always drawn after the background lens.
 */
@Composable
fun Modifier.glassSurface(
    shape: Shape = RoundedCornerShape(28.dp),
    tint: Color = Color.Unspecified,
): Modifier {
    val colors = MaterialTheme.colorScheme
    if (!LocalGlassEffectsEnabled.current) return opaqueGlass(shape, tint, colors.surface, colors.outline)
    val theme = LocalAppTheme.current
    val source = LocalGlassBackdrop.current?.takeIf { it.view === LocalView.current }
    val overBackground = source != null || LocalWindowBlurred.current
    val fill = if (overBackground) theme.panelFill
        else SolidColor(colors.surfaceContainerLow.copy(alpha = 0.88f))
    val pressure = remember { mutableStateOf(0f) }
    val lens = if (SamplingSupported && source != null) {
        Modifier.refractBackdrop(source, shape, pressure)
    } else Modifier

    return clip(shape)
        .then(lens)
        .background(fill)
        .background(tint.orTransparent())
        .background(GlassSheen)
        .border(0.65.dp, remember { lightRim(false) }, shape)
}

/** Live texture-coordinate refraction on Android 13+, live frost on Android 12. */
@Composable
fun Modifier.liquidGlass(
    shape: Shape = CircleShape,
    tint: Color = Color.Unspecified,
    interactionSource: MutableInteractionSource? = null,
): Modifier {
    val colors = MaterialTheme.colorScheme
    if (!LocalGlassEffectsEnabled.current) return opaqueGlass(shape, tint, colors.surface, colors.outline)
    val theme = LocalAppTheme.current
    val view = LocalView.current
    val source = (LocalLiveGlassBackdrop.current ?: LocalGlassBackdrop.current)
        ?.takeIf { it.view === view }
    val sampling = SamplingSupported && source != null
    val pressed = interactionSource?.collectIsPressedAsState()?.value ?: false
    val pressure = animateFloatAsState(if (pressed) 1f else 0f, tween(180), label = "glass pressure")
    val fill = when {
        sampling -> theme.liquidFill
        LocalWindowBlurred.current -> theme.frostedFill
        source != null -> SolidColor(colors.surfaceContainer.copy(alpha = 0.52f))
        else -> SolidColor(colors.surfaceContainer.copy(alpha = 0.82f))
    }
    val lens = if (sampling) Modifier.refractBackdrop(source!!, shape, pressure) else Modifier

    return shadow(
            8.dp, shape,
            ambientColor = Color.Black.copy(alpha = 0.24f),
            spotColor = Color.Black.copy(alpha = 0.24f),
        )
        .clip(shape)
        .then(lens)
        .background(fill)
        .background(tint.orTransparent())
        .background(GlassSheen)
        .border(0.65.dp, remember { lightRim(true) }, shape)
}

/** Reuse the GPU lens and padded source layer as the scene's display list changes. */
@Composable
private fun Modifier.refractBackdrop(
    source: GlassBackdropState,
    shape: Shape,
    pressure: State<Float>,
): Modifier {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var position by remember { mutableStateOf(Offset.Zero) }
    val refraction = remember(source) { GlassRefraction.create() }
    return onGloballyPositioned {
            coordinates = it
            position = it.localToWindow(Offset.Zero)
        }
        .drawWithCache {
            // Blur and the bevel need real pixels beyond the panel's visible edge.
            val padding = 32.dp.toPx()
            val texture = obtainGraphicsLayer()
            val textureSize = IntSize(
                ceil(size.width + padding * 2f).toInt(),
                ceil(size.height + padding * 2f).toInt(),
            )
            val outline = shape.createOutline(size, layoutDirection, this)
            val corner = when (outline) {
                is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
                is Outline.Rectangle -> 0f
                else -> null
            }
            val fallback = BlurEffect(6.dp.toPx(), 6.dp.toPx(), TileMode.Clamp)
            var lastRevision = -1
            var lastPosition = Offset.Unspecified
            onDrawBehind {
                val revision = source.revision
                // Reading only localToWindow() does not subscribe this draw to scrolling.
                // The same LayoutCoordinates object can move with its cached RenderNode.
                val placedPosition = position
                val panelOrigin = coordinates?.takeIf { it.isAttached }?.localToWindow(Offset.Zero)
                    ?: placedPosition
                val offset = panelOrigin - source.windowOrigin()
                if (revision != lastRevision || offset != lastPosition) {
                    texture.record(Density(density, fontScale), layoutDirection, textureSize) {
                        translate(padding - offset.x, padding - offset.y) { drawLayer(source.layer) }
                    }
                    lastRevision = revision
                    lastPosition = offset
                }
                texture.renderEffect = if (corner != null) {
                    refraction?.effect(size.width, size.height, corner, density, pressure.value, padding)
                        ?: fallback
                } else fallback
                translate(-padding, -padding) { drawLayer(texture) }
            }
        }
}

private fun Modifier.opaqueGlass(shape: Shape, tint: Color, surface: Color, outline: Color): Modifier =
    clip(shape)
        .background(if (tint != Color.Unspecified) tint.compositeOver(surface) else surface)
        .border(1.dp, outline, shape)

private fun Color.orTransparent() = if (this == Color.Unspecified) Color.Transparent else this

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    tint: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.glassSurface(shape, tint),
        shape = shape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        content = content,
    )
}

