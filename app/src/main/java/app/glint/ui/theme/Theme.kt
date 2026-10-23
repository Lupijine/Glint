package app.glint.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import app.glint.engine.SettingsStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Glint is dark by design: everything sits on the chosen theme's artwork, under glass. */
@Composable
fun GlintTheme(content: @Composable () -> Unit) {
    val themeId by
        remember { SettingsStore.settings.map { it.theme }.distinctUntilChanged() }
            .collectAsState(SettingsStore.current.theme)
    val photo by PhotoBackground.theme.collectAsState()
    // Until the saved picture has loaded, the quiet Midnight theme stands in for it.
    val theme =
        if (themeId == PhotoBackground.ThemeId) photo ?: AppThemes.Midnight else AppThemes.find(themeId)
    CompositionLocalProvider(LocalAppTheme provides theme) {
        MaterialTheme(colorScheme = theme.colors, content = content)
    }
}
