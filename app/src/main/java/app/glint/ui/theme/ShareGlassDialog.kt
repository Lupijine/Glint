package app.glint.ui.theme

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.PowerManager
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import java.util.function.Consumer
import kotlin.math.roundToInt

/**
 * A real, sheet-sized translucent window. The system blurs the other application behind
 * this window; our AGSL renderer never receives those pixels. Android's cross-window blur
 * is used where the phone allows it; Galaxy phones switch it off, so Samsung's own blur is
 * tried next; otherwise the window is opaque.
 */
@Composable
internal fun ShareGlassDialog(
    onDismissRequest: () -> Unit,
    // Internal test seam: production always follows the system capability listener.
    windowBlurEnabledOverride: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val effectsEnabled = LocalGlassEffectsEnabled.current
    val glassColor = LocalAppTheme.current.glass.toArgb()
    val density = LocalDensity.current
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val widthPx = with(density) { minOf(screenWidth - 16.dp, 560.dp).roundToPx().coerceAtLeast(1) }
    val pixelDensity = density.density

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
            // A wrapping native window excludes the system bars and resizes above
            // the IME. Full-screen insets belong to the in-app sheet only.
            decorFitsSystemWindows = true,
        ),
    ) {
        val context = LocalContext.current
        val window = (LocalView.current.parent as DialogWindowProvider).window
        val manager = remember(context) {
            context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }
        var systemBlurEnabled by remember(window) { mutableStateOf(false) }
        // Set when the phone refuses Samsung's blur, so the sheet becomes opaque instead.
        var samsungRefused by remember(window) { mutableStateOf(false) }
        val powerSaving = remember(context) {
            context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        }
        val background = remember(window, pixelDensity) {
            ShareWindowBackground().apply { cornerRadius = ShareCornerRadius.value * pixelDensity }
        }

        DisposableEffect(window, manager, windowBlurEnabledOverride) {
            var listener: Consumer<Boolean>? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && windowBlurEnabledOverride == null) {
                listener = Consumer { enabled -> systemBlurEnabled = enabled }
                systemBlurEnabled = manager.isCrossWindowBlurEnabled
                manager.addCrossWindowBlurEnabledListener(context.mainExecutor, listener)
            }
            onDispose {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    listener?.let(manager::removeCrossWindowBlurEnabledListener)
                    if (background.requestedBlurRadius != 0) window.setBackgroundBlurRadius(0)
                    background.requestedBlurRadius = 0
                }
                if (background.samsungApplied) {
                    SamsungBlur.clear(window.decorView)
                    background.samsungApplied = false
                }
            }
        }

        val nativeBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && effectsEnabled &&
            (windowBlurEnabledOverride ?: systemBlurEnabled)
        // Battery saver turns Samsung's blur off too; without blur the sheet must stay readable.
        val samsungBlur = !nativeBlur && windowBlurEnabledOverride == null && effectsEnabled &&
            !powerSaving && !samsungRefused && SamsungBlur.available
        val blurred = nativeBlur || samsungBlur
        val backing = when {
            nativeBlur -> ShareBacking.NativeBlur
            samsungBlur -> ShareBacking.SamsungBlur
            else -> ShareBacking.Opaque
        }
        SideEffect {
            configureShareGlassWindow(window, background, widthPx, pixelDensity, glassColor, backing)
            if (samsungBlur && !background.samsungApplied) {
                // After layout, so the blur takes the window's real, sheet-sized bounds.
                window.decorView.post {
                    if (!window.decorView.isAttachedToWindow || background.samsungApplied) return@post
                    val applied = SamsungBlur.apply(
                        window.decorView,
                        radius = SamsungBlurRadius,
                        color = (glassColor and 0x00FFFFFF) or (SamsungTintAlpha shl 24),
                        cornerRadius = ShareCornerRadius.value * pixelDensity,
                    )
                    background.samsungApplied = applied
                    if (!applied) samsungRefused = true
                }
            } else if (!samsungBlur && background.samsungApplied) {
                SamsungBlur.clear(window.decorView)
                background.samsungApplied = false
            }
            // This Compose version reapplies ADJUST_UNSPECIFIED in its parent
            // SideEffect. Run after that update so the wrapping sheet still moves
            // above the keyboard instead of being covered by it.
            window.decorView.post {
                if (window.decorView.isAttachedToWindow &&
                    (window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST) !=
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) {
                    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                }
            }
        }

        CompositionLocalProvider(LocalWindowBlurred provides blurred) {
            if (effectsEnabled) {
                ShareGlassMaterial(content)
            } else {
                CompositionLocalProvider(
                    LocalGlassBackdrop provides null,
                    LocalLiveGlassBackdrop provides null,
                    content = content,
                )
            }
        }
    }
}

/**
 * The sheet's own liquid glass. The other app's pixels never reach our renderer, so the sheet
 * carries a material of its own, in the theme's colours ([drawShareMaterial]). It is shown
 * through the same lens as the app's glass, which bends it at the sheet's rim, and the cards
 * and buttons inside refract it as they do the wallpaper in the app. The system blur of the
 * app behind shows through the whole of it.
 */
@Composable
private fun ShareGlassMaterial(content: @Composable () -> Unit) {
    val theme = LocalAppTheme.current
    val state = rememberGlassSceneState()
    val material = remember(state, theme) {
        Modifier.onGloballyPositioned(state::placed)
            .drawWithCache {
                val corner = ShareCornerRadius.toPx()
                val shown = obtainGraphicsLayer()
                val lens = GlassRefraction.create()?.effect(size.width, size.height, corner, density)
                val rim = Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.55f),
                    0.25f to Color.White.copy(alpha = 0.10f),
                    0.5f to Color.Transparent,
                    0.75f to Color.White.copy(alpha = 0.06f),
                    1f to Color.White.copy(alpha = 0.35f),
                )
                val rimWidth = 1.dp.toPx()
                var recorded = false
                onDrawWithContent {
                    if (!recorded) {
                        // The raw material is what the inner glass samples; the sheet itself
                        // shows it through the lens.
                        recordInto(state.layer) { drawShareMaterial(theme) }
                        state.recorded()
                        recordInto(shown) { drawLayer(state.layer) }
                        shown.renderEffect = lens
                        recorded = true
                    }
                    drawLayer(shown)
                    drawContent()
                    drawRoundRect(
                        rim,
                        topLeft = Offset(rimWidth / 2f, rimWidth / 2f),
                        size = Size(size.width - rimWidth, size.height - rimWidth),
                        cornerRadius = CornerRadius(corner),
                        style = Stroke(rimWidth),
                    )
                }
            }
    }
    Box(material) {
        CompositionLocalProvider(
            LocalGlassBackdrop provides state,
            LocalLiveGlassBackdrop provides state,
            content = content,
        )
    }
}

/** The share sheet's corners, as drawn by the sheet and clipped by its window. */
private val ShareCornerRadius = 34.dp

/** How the share window's rounded backing is made. */
private enum class ShareBacking { NativeBlur, SamsungBlur, Opaque }

/** Samsung's blur strength and the alpha of the theme tint over it (out of 255). */
private const val SamsungBlurRadius = 60
private const val SamsungTintAlpha = 115

/** Configure the native surface, not a translucent rectangle in a full-screen window. */
private class ShareWindowBackground : GradientDrawable() {
    var requestedBlurRadius = -1
    var translucentSurfaceConfigured = false
    var samsungApplied = false
}

private fun configureShareGlassWindow(
    window: Window,
    background: ShareWindowBackground,
    width: Int,
    density: Float,
    glassColor: Int,
    backing: ShareBacking,
) {
    if (!background.translucentSurfaceConfigured) {
        // A fallback can make the drawable opaque without making the native
        // surface opaque. Explicit format preserves later opaque-to-blur updates.
        window.setFormat(PixelFormat.TRANSLUCENT)
        background.translucentSurfaceConfigured = true
    }
    val color = glassColor or (0xFF shl 24)
    if (background.color?.defaultColor != color) background.setColor(color)
    // Dimmed, blurred pixels still show through the backing. When system blur is
    // disabled (including power saver), an opaque fill prevents sharp app content
    // from leaking through the controls. No synthetic wallpaper is substituted.
    // Samsung's blur carries its own tint, so the window backing stays clear above it.
    val blurred = backing != ShareBacking.Opaque
    val alpha = when (backing) {
        ShareBacking.NativeBlur -> (0.5f * 255f).roundToInt()
        ShareBacking.SamsungBlur -> 0
        ShareBacking.Opaque -> 255
    }
    if (background.alpha != alpha) background.alpha = alpha
    val decor = window.decorView
    if (decor.background !== background) window.setBackgroundDrawable(background)
    if (decor.paddingLeft != 0 || decor.paddingTop != 0 || decor.paddingRight != 0 || decor.paddingBottom != 0) {
        decor.setPadding(0, 0, 0, 0)
    }
    if (!decor.clipToOutline) decor.clipToOutline = true
    // Blur-behind would affect the entire screen. Background blur follows the
    // rounded drawable and the actual wrapping window bounds instead.
    val attrs = window.attributes
    val flags = (attrs.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND) and
        WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
    val gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    val margin = (8f * density).roundToInt()
    val dim = if (blurred) 0.32f else 0.56f
    if (attrs.flags != flags || attrs.gravity != gravity || attrs.width != width ||
        attrs.height != WindowManager.LayoutParams.WRAP_CONTENT || attrs.y != margin || attrs.dimAmount != dim) {
        window.attributes = attrs.apply {
            this.flags = flags
            this.gravity = gravity
            this.width = width
            height = WindowManager.LayoutParams.WRAP_CONTENT
            y = margin
            dimAmount = dim
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val radius = if (backing == ShareBacking.NativeBlur) (40f * density).roundToInt() else 0
        if (background.requestedBlurRadius != radius) {
            window.setBackgroundBlurRadius(radius)
            background.requestedBlurRadius = radius
        }
    }
}
