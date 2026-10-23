package app.glint.ui.theme

import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import app.glint.R

/**
 * One look for Glint: the artwork behind the glass and the colors that go with it. Every theme
 * is dark, so white text stays readable on the glass. THEMES.md explains how to add one.
 */
@Immutable
class AppTheme(
    /** Saved in settings. Never change it once released, or people lose their choice. */
    val id: String,
    @StringRes val name: Int,
    /** Buttons, the selected tab and progress bars. A light color: dark text sits on it. */
    val accent: Color,
    /** A second color related to the accent, for small highlights. */
    val secondary: Color,
    /** The darkest color of the artwork, shown wherever the artwork is not drawn. */
    val background: Color,
    /** A dark color from the artwork that tints the glass panels. */
    val glass: Color,
    /** How much the middle of the screen is darkened behind lists, from 0 (none) to 1. */
    val scrim: Float = 0.5f,
    /** Draws the full-screen artwork. It is drawn once and kept, so detail costs nothing later. */
    val artwork: DrawScope.() -> Unit,
) {
    val colors: ColorScheme = glassColorScheme(accent, secondary, background, glass)

    /** The fill of glass cards and panels over the artwork. */
    internal val panelFill: Brush =
        Brush.verticalGradient(
            listOf(
                lerp(glass, Color.White, 0.03f).copy(alpha = 0.145f),
                glass.copy(alpha = 0.078f),
                lerp(glass, Color.Black, 0.1f).copy(alpha = 0.16f),
            ))

    /** The fill of floating glass (the tab island and round buttons) over the artwork. */
    internal val liquidFill: Brush =
        Brush.verticalGradient(
            listOf(
                lerp(glass, Color.White, 0.06f).copy(alpha = 0.18f),
                glass.copy(alpha = 0.07f),
                lerp(glass, Color.Black, 0.1f).copy(alpha = 0.196f),
            ))

    /**
     * Floating glass over another app that the system already blurs. It stays dark enough for
     * white text even when the app behind is bright.
     */
    internal val frostedFill: Brush =
        Brush.verticalGradient(
            listOf(
                lerp(glass, Color.White, 0.06f).copy(alpha = 0.70f),
                glass.copy(alpha = 0.74f),
                lerp(glass, Color.Black, 0.1f).copy(alpha = 0.78f),
            ))
}

/** Material colors for a dark glass theme, derived from a few theme colors. */
internal fun glassColorScheme(accent: Color, secondary: Color, background: Color, glass: Color): ColorScheme {
    val surface = lerp(glass, Color.Black, 0.1f)
    return darkColorScheme(
        primary = accent,
        onPrimary = lerp(glass, Color.Black, 0.5f),
        primaryContainer = lerp(glass, accent, 0.3f),
        onPrimaryContainer = lerp(accent, Color.White, 0.6f),
        secondary = secondary,
        tertiary = Color(0xFF5EEAD4),
        background = background,
        onBackground = Color.White,
        surface = surface,
        onSurface = Color.White,
        onSurfaceVariant = lerp(Color(0xFFB4B7C2), accent, 0.12f),
        surfaceContainerLow = lerp(surface, Color.White, 0.025f),
        surfaceContainer = lerp(surface, Color.White, 0.05f),
        outline = lerp(surface, Color.White, 0.3f),
        outlineVariant = lerp(surface, Color.White, 0.12f),
        error = Color(0xFFFF8A8A),
    )
}

/** Every theme, in the order shown in Settings. */
object AppThemes {
    val Chrome =
        AppTheme(
            id = "chrome",
            name = R.string.theme_chrome,
            accent = Color(0xFFB4C7FF),
            secondary = Color(0xFF8FB4FF),
            background = Color(0xFF020306),
            glass = Color(0xFF10161F),
            scrim = 0.35f,
            artwork = { drawBundled(R.drawable.theme_chrome) },
        )

    val Mercury =
        AppTheme(
            id = "mercury",
            name = R.string.theme_mercury,
            accent = Color(0xFFE1E6EF),
            secondary = Color(0xFFB7C0CE),
            background = Color(0xFF000000),
            glass = Color(0xFF121417),
            scrim = 0.3f,
            artwork = { drawBundled(R.drawable.theme_mercury) },
        )

    val Drop =
        AppTheme(
            id = "drop",
            name = R.string.theme_drop,
            accent = Color(0xFFDDE3EE),
            secondary = Color(0xFFAEB6C4),
            background = Color(0xFF000000),
            glass = Color(0xFF111214),
            // The drop sits in the middle; only a light shade keeps it from fading.
            scrim = 0.15f,
            artwork = { drawBundled(R.drawable.theme_drop) },
        )

    val Caustics =
        AppTheme(
            id = "caustics",
            name = R.string.theme_caustics,
            accent = Color(0xFF8BE9FF),
            secondary = Color(0xFF5EEAD4),
            background = Color(0xFF020D17),
            glass = Color(0xFF082233),
            scrim = 0.45f,
            artwork = { drawCaustics() },
        )

    val Zellige =
        AppTheme(
            id = "zellige",
            name = R.string.theme_zellige,
            accent = Color(0xFFE6C98E),
            secondary = Color(0xFF7FD1B9),
            background = Color(0xFF05080B),
            glass = Color(0xFF0E171C),
            scrim = 0.6f,
            artwork = { drawZellige() },
        )

    val Bokeh =
        AppTheme(
            id = "bokeh",
            name = R.string.theme_bokeh,
            accent = Color(0xFFFFC79A),
            secondary = Color(0xFFFF8FC8),
            background = Color(0xFF05040A),
            glass = Color(0xFF170F24),
            scrim = 0.45f,
            artwork = { drawBokeh() },
        )

    val Default = Chrome

    /** Add new themes to this list. A picture from the gallery ([PhotoBackground]) comes first. */
    val all: List<AppTheme> =
        listOf(Chrome, Mercury, Drop, Caustics, Zellige, Bokeh)

    /** The theme with this id, or the default for an unknown or removed id. */
    fun find(id: String?): AppTheme = all.firstOrNull { it.id == id } ?: Default
}

val LocalAppTheme = staticCompositionLocalOf { AppThemes.Default }
