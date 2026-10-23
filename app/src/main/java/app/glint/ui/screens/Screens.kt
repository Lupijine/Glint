package app.glint.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.glint.R
import app.glint.ui.EmptyCard
import app.glint.ui.GlassIconButton
import app.glint.ui.GlassPill
import app.glint.ui.GlassSegmented
import app.glint.ui.LargeTitle
import app.glint.ui.DownloadCard
import app.glint.ui.SecondaryText
import app.glint.ui.icons.GlintIcons
import app.glint.engine.DownloadItem
import app.glint.engine.DownloadManager
import app.glint.engine.Engine
import app.glint.ui.theme.glassSurface
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

enum class QueueFilter(val label: Int) {
    All(R.string.filter_all),
    Active(R.string.filter_active),
    Done(R.string.filter_done),
}

/** A scrolling page with a large title; [bottomInset] keeps content clear of the island. */
@Composable
internal fun Page(
    title: String,
    bottomInset: Dp,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = bottomInset + 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "title") { LargeTitle(title = title, actions = actions) }
        content()
    }
}

@Composable
fun DownloadsScreen(bottomInset: Dp) {
    var filter by rememberSaveable { mutableStateOf(QueueFilter.All) }
    val items by DownloadManager.items.collectAsStateWithLifecycle()
    val engine by Engine.state.collectAsStateWithLifecycle()
    val queue = remember(items) { items.filter { it.inQueue } }
    val visible = remember(queue, filter) {
        when (filter) {
            QueueFilter.All -> queue
            QueueFilter.Active -> queue.filter { it.isActive || it.status == DownloadItem.Status.Paused }
            QueueFilter.Done -> queue.filter { it.status == DownloadItem.Status.Completed }
        }
    }
    val hasFinished = queue.any { !it.isActive && it.status != DownloadItem.Status.Paused }
    val canPause = queue.any { it.isActive && it.status != DownloadItem.Status.Saving }
    val canResume =
        queue.any { it.status == DownloadItem.Status.Paused || it.status == DownloadItem.Status.Interrupted }

    Page(
        title = stringResource(R.string.tab_downloads),
        bottomInset = bottomInset,
        actions = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canPause) {
                    GlassIconButton(
                        icon = GlintIcons.Pause,
                        contentDescription = stringResource(R.string.pause_all),
                        onClick = DownloadManager::pauseAll,
                        size = 40,
                    )
                } else if (canResume) {
                    GlassIconButton(
                        icon = GlintIcons.Open,
                        contentDescription = stringResource(R.string.resume_all),
                        onClick = DownloadManager::resumeAll,
                        tint = MaterialTheme.colorScheme.primary,
                        size = 40,
                    )
                }
                if (hasFinished) {
                    GlassPill(
                        text = stringResource(R.string.clear_finished),
                        onClick = DownloadManager::clearFinished,
                    )
                }
            }
        },
    ) {
        item(key = "filter") {
            GlassSegmented(
                modifier = Modifier.padding(horizontal = 20.dp),
                options = QueueFilter.entries,
                selected = filter,
                label = { stringResource(it.label) },
                onSelect = { filter = it },
            )
        }
        when (val state = engine) {
            Engine.State.Starting ->
                item(key = "engine") { EngineNotice(stringResource(R.string.engine_starting), isError = false) }
            Engine.State.Updating ->
                item(key = "engine") { EngineNotice(stringResource(R.string.engine_updating), isError = false) }
            is Engine.State.Failed ->
                item(key = "engine") {
                    EngineNotice(stringResource(R.string.engine_failed, state.message), isError = true)
                }
            Engine.State.Ready -> Unit
        }
        if (visible.isEmpty()) {
            item(key = "empty") {
                EmptyCard(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    icon = GlintIcons.Download,
                    title = stringResource(R.string.empty_downloads_title),
                    body = stringResource(R.string.empty_downloads_body),
                )
            }
        } else {
            items(visible, key = { it.id }) { download ->
                DownloadCard(
                    item = download,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            item(key = "hint") {
                Text(
                    text = stringResource(R.string.remove_hint),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    color = SecondaryText,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun EngineNotice(text: String, isError: Boolean) {
    Text(
        text = text,
        modifier =
            Modifier.padding(horizontal = 20.dp)
                .fillMaxWidth()
                .glassSurface(RoundedCornerShape(20.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
        color = if (isError) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.8f),
        fontSize = 13.5.sp,
    )
}
