package app.glint.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.glint.ui.theme.GlassAlertDialog as AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.glint.R
import app.glint.engine.Clips
import app.glint.engine.DownloadItem
import app.glint.engine.DownloadManager
import app.glint.engine.MediaType
import app.glint.engine.ErrorKind
import app.glint.engine.errorKind
import app.glint.ui.icons.GlintIcons
import app.glint.ui.theme.glassSurface
import coil.compose.AsyncImage
import kotlin.math.roundToInt

internal val Green = Color(0xFF86EFAC)

/**
 * One download with a quiet progress tint. Tapping opens a finished file or explains a failure;
 * long-press removes it from the list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadCard(item: DownloadItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val accent = MaterialTheme.colorScheme.primary
    val removeLabel = stringResource(R.string.action_remove)
    val locale = LocalConfiguration.current.locales[0]
    var showError by remember { mutableStateOf(false) }
    val meta = remember(item.source, item.durationSeconds, item.sizeBytes, item.formatLabel, item.sectionStart, locale) {
        listOfNotNull(
                item.source.takeIf { it.isNotBlank() },
                item.durationSeconds.takeIf { it > 0 && !item.isClip }?.let(::formatDuration),
                clipLabel(item),
                item.sizeBytes.takeIf { it > 0 }?.let { formatSize(it) },
                item.formatLabel.takeIf { it.isNotBlank() },
            )
            .joinToString(" · ")
    }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .glassSurface(RoundedCornerShape(24.dp))
                .combinedClickable(
                    onClick = {
                        if (item.hasFile) {
                            openDownload(context, item)
                        } else if (item.status == DownloadItem.Status.Failed) {
                            showError = true
                        }
                    },
                    onLongClickLabel = removeLabel,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        DownloadManager.remove(item.id)
                    },
                )) {
            if (item.isActive || item.status == DownloadItem.Status.Paused) {
                ProgressFill(
                    modifier = Modifier.matchParentSize(),
                    fraction = item.progress,
                    accent = accent,
                )
            }
            if (showError) {
                ErrorDialog(item = item, onDismiss = { showError = false })
            }
            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Thumbnail(url = item.thumbnail, type = item.type)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title.ifBlank { item.url },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (meta.isNotEmpty()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = meta,
                            fontSize = 12.5.sp,
                            color = SecondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    StatusLine(item = item, accent = accent)
                }
                Spacer(Modifier.width(10.dp))
                ActionButtons(item = item)
            }
        }
}

@Composable
private fun StatusLine(item: DownloadItem, accent: Color) {
    val progress = item.progress
    when (item.status) {
        DownloadItem.Status.Downloading ->
            if (progress != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "${(progress * 100).roundToInt()}%",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                    )
                    Spacer(Modifier.width(8.dp))
                    val details =
                        listOfNotNull(
                                item.speed.takeIf { it.isNotBlank() },
                                item.etaSeconds
                                    .takeIf { it > 0 }
                                    ?.let { stringResource(R.string.eta, formatEta(it)) },
                            )
                            .joinToString(" · ")
                    Text(
                        text = details,
                        fontSize = 12.sp,
                        color = SecondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 1.dp),
                    )
                }
            } else {
                SmallStatus(stringResource(R.string.status_preparing), SecondaryText)
            }
        DownloadItem.Status.Queued -> SmallStatus(stringResource(R.string.status_queued), SecondaryText)
        DownloadItem.Status.Paused ->
            SmallStatus(
                listOfNotNull(
                        stringResource(R.string.status_paused),
                        progress?.let { "${(it * 100).roundToInt()}%" },
                    )
                    .joinToString(" · "),
                SecondaryText,
            )
        DownloadItem.Status.Saving -> SmallStatus(stringResource(R.string.status_saving), accent)
        DownloadItem.Status.Preparing ->
            SmallStatus(stringResource(R.string.status_preparing), SecondaryText)
        DownloadItem.Status.Completed ->
            if (item.fileMissing) SmallStatus(stringResource(R.string.file_missing), MaterialTheme.colorScheme.error)
            else SmallStatus(stringResource(R.string.status_completed), Green)
        DownloadItem.Status.Canceled -> SmallStatus(stringResource(R.string.status_canceled), SecondaryText)
        DownloadItem.Status.Interrupted ->
            SmallStatus(stringResource(R.string.status_interrupted), SecondaryText)
        DownloadItem.Status.Failed ->
            SmallStatus(stringResource(item.errorKind.message), MaterialTheme.colorScheme.error, maxLines = 2)
    }
}

@Composable
private fun SmallStatus(text: String, color: Color, maxLines: Int = 1) {
    Text(
        text = text,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Medium,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** "✂ 1:05–2:10" for a clip, or null for a whole video. */
internal fun clipLabel(item: DownloadItem): String? {
    val start = item.sectionStart ?: return null
    val end = item.sectionEnd ?: return null
    return "✂ ${Clips.format(start)}–${Clips.format(end)}"
}

/** A card's buttons: pause and cancel while running, resume when stopped, open when done. */
@Composable
private fun ActionButtons(item: DownloadItem) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (item.status) {
            DownloadItem.Status.Queued,
            DownloadItem.Status.Preparing,
            DownloadItem.Status.Downloading -> {
                CardButton(GlintIcons.Pause, R.string.action_pause) { DownloadManager.pause(item.id) }
                CardButton(GlintIcons.Cancel, R.string.action_cancel) { DownloadManager.cancel(item.id) }
            }
            DownloadItem.Status.Saving -> Unit
            DownloadItem.Status.Paused,
            DownloadItem.Status.Interrupted -> {
                CardButton(GlintIcons.Open, R.string.action_resume, tint = MaterialTheme.colorScheme.primary) {
                    DownloadManager.resume(item.id)
                }
                CardButton(GlintIcons.Cancel, R.string.action_cancel) { DownloadManager.cancel(item.id) }
            }
            DownloadItem.Status.Failed,
            DownloadItem.Status.Canceled ->
                CardButton(GlintIcons.Retry, R.string.action_retry) { DownloadManager.retry(item.id) }
            DownloadItem.Status.Completed ->
                CardButton(GlintIcons.Open, R.string.action_open, tint = Green) { openDownload(context, item) }
        }
    }
}

@Composable
private fun CardButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: Int,
    tint: Color = SecondaryText,
    onClick: () -> Unit,
) {
    val description = stringResource(label)
    Box(
        modifier =
            Modifier.size(42.dp)
                .glassSurface(CircleShape)
                .clickable(onClickLabel = description, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun Thumbnail(
    url: String?,
    type: MediaType,
    width: Int = 92,
    height: Int = 60,
) {
    val shape = RoundedCornerShape(14.dp)
    val thumbModifier =
        Modifier.size(width = width.dp, height = height.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.045f))
            .border(0.75.dp, Color.White.copy(alpha = 0.10f), shape)
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = null,
            modifier = thumbModifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(thumbModifier, contentAlignment = Alignment.Center) {
            Icon(
                if (type == MediaType.Audio) GlintIcons.Audio else GlintIcons.Video,
                contentDescription = null,
                tint = SecondaryText.copy(alpha = 0.65f),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun ProgressFill(modifier: Modifier, fraction: Float?, accent: Color) {
    val animatedProgress = animateFloatAsState(
        targetValue = fraction ?: 0f,
        animationSpec = tween(180),
        label = "progress",
    )
    // Read the animation only while drawing, so frames do not recompose the card or its text.
    val progress = remember(animatedProgress) { { animatedProgress.value } }
    val determinate = fraction != null
    Box(
        modifier =
            modifier.drawWithCache {
                val fill =
                    Brush.horizontalGradient(
                        0f to accent.copy(alpha = 0.05f),
                        0.7f to accent.copy(alpha = 0.16f),
                        1f to accent.copy(alpha = if (determinate) 0.28f else 0.10f),
                        startX = 0f,
                        endX = size.width,
                    )
                val edgeWidth = 1.5.dp.toPx()
                onDrawBehind {
                    val width = if (determinate) size.width * progress().coerceIn(0f, 1f) else size.width
                    if (width >= 1f) {
                        clipRect(right = width) { drawRect(fill) }
                        if (determinate) {
                            val edge = edgeWidth.coerceAtMost(width)
                            drawRect(accent, topLeft = Offset(width - edge, 0f), size = Size(edge, size.height))
                        }
                    }
                }
            })
}


/** What went wrong with a download, with the downloader's own output below to copy, and retry. */
@Composable
private fun ErrorDialog(item: DownloadItem, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val detail = item.errorDetail ?: item.error
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.error_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = item.title.ifBlank { item.url },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // "Tap for details" would be odd here, under the details themselves.
                if (item.errorKind != ErrorKind.Unknown) {
                    Spacer(Modifier.height(8.dp))
                    Text(text = stringResource(item.errorKind.message), fontSize = 14.sp, lineHeight = 20.sp)
                }
                if (detail != null) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.error_details),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = SecondaryText,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = detail,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        color = SecondaryText,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    DownloadManager.retry(item.id)
                }) {
                    Text(stringResource(R.string.action_retry))
                }
        },
        dismissButton = {
            Row {
                if (detail != null) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString("${item.url}\n\n$detail")) }) {
                        Text(stringResource(R.string.copy_error))
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
    )
}
