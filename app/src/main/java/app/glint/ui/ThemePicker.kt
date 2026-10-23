package app.glint.ui

import app.glint.ui.theme.PhotoBackground
import app.glint.R
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.glint.ui.icons.GlintIcons
import app.glint.ui.theme.AppTheme
import app.glint.ui.theme.AppThemes
import app.glint.ui.theme.drawThemeArtwork
import app.glint.ui.theme.recordInto

private val SwatchWidth = 96.dp
private val SwatchHeight = 164.dp
private val SwatchShape = RoundedCornerShape(22.dp)

/** A row of small previews, one per theme: the artwork with a little glass on top. */
@Composable
fun ThemePicker(
    selected: AppTheme,
    onSelect: (AppTheme) -> Unit,
    onPickPhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val photo by PhotoBackground.theme.collectAsState()
    val photoSelected = selected.id == PhotoBackground.ThemeId
    // The picture comes first, then the drawn themes. Open with the chosen one in view.
    val index = if (photoSelected) 0 else AppThemes.all.indexOfFirst { it.id == selected.id } + 1
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (index - 1).coerceAtLeast(0))
    LazyRow(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        state = state,
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = PhotoBackground.ThemeId) {
            val current = photo
            if (current == null) {
                PickPhotoSwatch(onClick = onPickPhoto)
            } else {
                // Tapping the chosen picture again picks another one.
                ThemeSwatch(
                    current,
                    selected = photoSelected,
                    onClick = { if (photoSelected) onPickPhoto() else onSelect(current) },
                    badge = GlintIcons.Plus,
                )
            }
        }
        items(AppThemes.all, key = { it.id }) { theme ->
            ThemeSwatch(theme, selected = theme.id == selected.id, onClick = { onSelect(theme) })
        }
    }
}

@Composable
private fun ThemeSwatch(
    theme: AppTheme,
    selected: Boolean,
    onClick: () -> Unit,
    badge: ImageVector? = null,
) {
    Column(
        modifier =
            Modifier.width(SwatchWidth)
                .clip(SwatchShape)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .height(SwatchHeight)
                    .clip(SwatchShape)
                    .drawWithCache {
                        // The artwork is detailed, so it is drawn once per size and kept.
                        val artwork = obtainGraphicsLayer()
                        var recorded = false
                        onDrawBehind {
                            if (!recorded) {
                                recordInto(artwork) { drawThemeArtwork(theme) }
                                recorded = true
                            }
                            drawLayer(artwork)
                        }
                    }
                    .border(
                        width = if (selected) 1.5.dp else 0.5.dp,
                        color = if (selected) theme.accent else Color.White.copy(alpha = 0.18f),
                        shape = SwatchShape,
                    ),
        ) {
            MiniCard(theme, Modifier.align(Alignment.Center))
            MiniIsland(theme, Modifier.align(Alignment.BottomCenter))
            if (badge != null) {
                Box(
                    modifier =
                        Modifier.align(Alignment.TopStart)
                            .padding(7.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f))
                            .border(0.5.dp, Color.White.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(badge, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                }
            }
            if (selected) {
                Box(
                    modifier =
                        Modifier.align(Alignment.TopEnd)
                            .padding(7.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(theme.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = GlintIcons.Check,
                        contentDescription = null,
                        tint = theme.colors.onPrimary,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
        Text(
            text = stringResource(theme.name),
            modifier = Modifier.padding(bottom = 2.dp),
            color = if (selected) Color.White else SecondaryText,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Before a picture is chosen: an empty glass tile that opens the gallery. */
@Composable
private fun PickPhotoSwatch(onClick: () -> Unit) {
    val label = stringResource(R.string.theme_photo)
    Column(
        modifier =
            Modifier.width(SwatchWidth)
                .clip(SwatchShape)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.photo_pick), onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .height(SwatchHeight)
                    .clip(SwatchShape)
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.22f), SwatchShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier.size(40.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.10f))
                        .border(0.5.dp, Color.White.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(GlintIcons.Plus, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        Text(
            text = label,
            modifier = Modifier.padding(bottom = 2.dp),
            color = SecondaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A tiny download card in the theme's glass, with a progress bar in its accent. */
@Composable
private fun MiniCard(theme: AppTheme, modifier: Modifier) {
    val shape = RoundedCornerShape(9.dp)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 7.dp)
                .clip(shape)
                .background(theme.panelFill)
                .border(0.5.dp, Color.White.copy(alpha = 0.22f), shape)
                .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.fillMaxWidth(0.8f).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.85f)))
        Box(Modifier.fillMaxWidth(0.5f).height(3.dp).clip(CircleShape).background(SecondaryText.copy(alpha = 0.6f)))
        Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))) {
            Box(Modifier.fillMaxWidth(0.6f).fillMaxHeight().clip(CircleShape).background(theme.accent))
        }
    }
}

/** The tab island and + button, in miniature. */
@Composable
private fun MiniIsland(theme: AppTheme, modifier: Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(7.dp).height(18.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.weight(1f)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(theme.panelFill)
                    .border(0.5.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                    .padding(3.dp),
        ) {
            Box(Modifier.size(width = 16.dp, height = 12.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)))
        }
        Box(
            modifier =
                Modifier.size(18.dp)
                    .clip(CircleShape)
                    .background(theme.accent.copy(alpha = 0.20f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.22f), CircleShape),
        )
    }
}
