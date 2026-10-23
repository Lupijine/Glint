package app.glint.ui.screens

import android.os.Build
import android.widget.Toast
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import app.glint.ui.theme.GlassAlertDialog as AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.glint.R
import app.glint.engine.DownloadItem
import android.text.format.DateUtils
import app.glint.engine.DownloadManager
import app.glint.engine.ErrorKind
import app.glint.engine.FormatChoices
import app.glint.engine.Following
import app.glint.engine.MediaType
import app.glint.engine.Subscription
import app.glint.engine.Library
import app.glint.engine.LibraryKind
import app.glint.engine.LibrarySort
import app.glint.ui.ChoiceRow
import app.glint.ui.EmptyCard
import app.glint.ui.GlassIconButton
import app.glint.ui.GlassPill
import app.glint.ui.GlassSegmented
import app.glint.ui.SecondaryText
import app.glint.ui.Thumbnail
import app.glint.ui.formatDuration
import app.glint.ui.formatSize
import app.glint.ui.icons.GlintIcons
import app.glint.ui.openDownload
import app.glint.ui.openSourcePage
import app.glint.ui.shareDownload
import app.glint.ui.theme.glassSurface
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

private val LibraryKind.label: Int
    get() =
        when (this) {
            LibraryKind.All -> R.string.filter_all
            LibraryKind.Video -> R.string.type_video
            LibraryKind.Audio -> R.string.library_audio
        }

private val LibrarySort.label: Int
    get() =
        when (this) {
            LibrarySort.Newest -> R.string.sort_newest
            LibrarySort.Oldest -> R.string.sort_oldest
            LibrarySort.Title -> R.string.sort_title
            LibrarySort.Size -> R.string.sort_size
        }

/** The library's two views: finished files, and the channels and playlists being followed. */
private enum class LibraryView(val label: Int) {
    Files(R.string.library_files),
    Following(R.string.library_following),
}

/** Every finished download, searchable, with ways to open, share and delete files. */
@Composable
fun LibraryScreen(bottomInset: Dp) {
    val items by DownloadManager.items.collectAsStateWithLifecycle()
    var view by rememberSaveable { mutableStateOf(LibraryView.Files) }
    val subscriptions by Following.subscriptions.collectAsStateWithLifecycle()
    val checking by Following.checking.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(LibraryKind.All) }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.Newest) }
    var pendingDelete by remember { mutableStateOf<DownloadItem?>(null) }
    val locale = LocalConfiguration.current.locales[0]
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Files can be deleted or moved by other apps while Glint is closed.
    LaunchedEffect(Unit) { DownloadManager.refreshLibrary() }

    // A command can finish without saving a file; only downloads with a file belong here.
    val finished = remember(items) {
        items.filter { it.hasFile }
    }
    val files = remember(finished, query, kind, sort, locale) { Library.files(finished, query, kind, sort, locale) }
    val totalBytes = remember(files) { files.sumOf { it.sizeBytes } }

    Page(
        title = stringResource(R.string.tab_library),
        bottomInset = bottomInset,
        actions = {
            if (view == LibraryView.Following && subscriptions.any { it.enabled }) {
                GlassPill(
                    text = stringResource(R.string.check_all),
                    icon = GlintIcons.Retry,
                    onClick = { Following.checkNow() },
                )
            }
        },
    ) {
        item(key = "view") {
            GlassSegmented(
                modifier = Modifier.padding(horizontal = 20.dp),
                options = LibraryView.entries,
                selected = view,
                label = { stringResource(it.label) },
                onSelect = { view = it },
            )
        }
        if (view == LibraryView.Following) {
            followingItems(subscriptions, checking, locale)
            return@Page
        }
        if (finished.isEmpty()) {
            item(key = "empty") {
                EmptyCard(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    icon = GlintIcons.Library,
                    title = stringResource(R.string.empty_library_title),
                    body = stringResource(R.string.empty_library_body),
                )
            }
            return@Page
        }
        item(key = "search") {
            SearchField(
                query = query,
                onQueryChange = { query = it },
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        item(key = "kind") {
            GlassSegmented(
                modifier = Modifier.padding(horizontal = 20.dp),
                options = LibraryKind.entries,
                selected = kind,
                label = { stringResource(it.label) },
                onSelect = { kind = it },
            )
        }
        item(key = "sort") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ChoiceRow(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    options = LibrarySort.entries,
                    selected = sort,
                    label = { stringResource(it.label) },
                    onSelect = { sort = it },
                )
                Text(
                    text = pluralStringResource(R.plurals.library_summary, files.size, files.size, formatSize(totalBytes)),
                    modifier = Modifier.padding(horizontal = 24.dp),
                    color = SecondaryText,
                    fontSize = 12.5.sp,
                )
            }
        }
        if (files.isEmpty()) {
            item(key = "no matches") {
                EmptyCard(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    icon = GlintIcons.Search,
                    title = stringResource(R.string.no_matches_title),
                    body = stringResource(R.string.no_matches_body),
                )
            }
        } else {
            items(files, key = { it.id }) { file ->
                LibraryCard(
                    item = file,
                    locale = locale,
                    onDelete = { pendingDelete = file },
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
    }

    pendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_body, file.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            val deleted = DownloadManager.deleteFromLibrary(file.id, deleteFile = true)
                            if (!deleted) {
                                Toast.makeText(context, R.string.delete_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Text(stringResource(R.string.delete_confirm), color = MaterialTheme.colorScheme.error)
                    }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(50.dp)
                .glassSurface(RoundedCornerShape(25.dp))
                .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(GlintIcons.Search, contentDescription = null, tint = SecondaryText, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(text = stringResource(R.string.library_search_hint), color = SecondaryText.copy(alpha = 0.7f), fontSize = 15.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Box(
                modifier =
                    Modifier.size(38.dp)
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.clear_search)) {
                            onQueryChange("")
                        },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    GlintIcons.Close,
                    contentDescription = stringResource(R.string.clear_search),
                    tint = SecondaryText,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun LibraryCard(
    item: DownloadItem,
    locale: java.util.Locale,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    val meta = remember(item.source, item.durationSeconds, item.sizeBytes, item.formatLabel, locale) {
        listOfNotNull(
                item.source.takeIf { it.isNotBlank() },
                item.durationSeconds.takeIf { it > 0 }?.let(::formatDuration),
                item.sizeBytes.takeIf { it > 0 }?.let { formatSize(it) },
                item.formatLabel.takeIf { it.isNotBlank() },
            )
            .joinToString(" · ")
    }
    val date = remember(item.completedAt, item.createdAt, locale) {
        DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(item.completedAt ?: item.createdAt))
    }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .glassSurface(RoundedCornerShape(24.dp))
                .clickable(enabled = !item.fileMissing, role = Role.Button) { openDownload(context, item) }
                .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(url = item.thumbnail, type = item.type, width = 104, height = 64)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title.ifBlank { item.url },
                color = Color.White,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 19.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(text = meta, color = SecondaryText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(3.dp))
            if (item.fileMissing) {
                Text(
                    text = stringResource(R.string.file_missing),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            } else {
                Text(text = date, color = SecondaryText.copy(alpha = 0.75f), fontSize = 11.5.sp)
            }
        }
        Spacer(Modifier.width(8.dp))
        Box {
            GlassIconButton(
                icon = GlintIcons.More,
                contentDescription = stringResource(R.string.more_actions),
                onClick = { menuOpen = true },
                size = 42,
            )
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                shape = RoundedCornerShape(18.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                if (!item.fileMissing) {
                    MenuEntry(GlintIcons.Open, stringResource(R.string.action_open)) {
                        menuOpen = false
                        openDownload(context, item)
                    }
                    MenuEntry(GlintIcons.Share, stringResource(R.string.action_share)) {
                        menuOpen = false
                        shareDownload(context, item)
                    }
                }
                MenuEntry(GlintIcons.Copy, stringResource(R.string.action_copy_link)) {
                    menuOpen = false
                    clipboard.setText(AnnotatedString(item.url))
                    // Android 13 and newer confirm copies on their own.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
                    }
                }
                MenuEntry(GlintIcons.Globe, stringResource(R.string.action_open_page)) {
                    menuOpen = false
                    openSourcePage(context, item)
                }
                MenuEntry(GlintIcons.Library, stringResource(R.string.action_remove_library)) {
                    menuOpen = false
                    scope.launch { DownloadManager.deleteFromLibrary(item.id, deleteFile = false) }
                }
                if (!item.fileMissing) {
                    MenuEntry(GlintIcons.Trash, stringResource(R.string.action_delete_file), MaterialTheme.colorScheme.error) {
                        menuOpen = false
                        onDelete()
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuEntry(
    icon: ImageVector,
    text: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text = text, color = tint, fontSize = 14.sp) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
    )
}

/** The followed channels and playlists, each with its last check and controls. */
private fun androidx.compose.foundation.lazy.LazyListScope.followingItems(
    subscriptions: List<Subscription>,
    checking: Set<String>,
    locale: java.util.Locale,
) {
    if (subscriptions.isEmpty()) {
        item(key = "no following") {
            EmptyCard(
                modifier = Modifier.padding(horizontal = 20.dp),
                icon = GlintIcons.Follow,
                title = stringResource(R.string.following_empty_title),
                body = stringResource(R.string.following_empty_body),
            )
        }
        return
    }
    items(subscriptions, key = { "follow " + it.id }) { subscription ->
        SubscriptionCard(
            subscription = subscription,
            isChecking = subscription.id in checking,
            locale = locale,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

@Composable
private fun SubscriptionCard(
    subscription: Subscription,
    isChecking: Boolean,
    locale: java.util.Locale,
    modifier: Modifier = Modifier,
) {
    var confirmUnfollow by remember { mutableStateOf(false) }
    val kind =
        if (subscription.type == MediaType.Audio) {
            listOf(stringResource(R.string.type_audio), FormatChoices.audioLabel(subscription.audioFormat))
                .filter { it.isNotBlank() }
                .joinToString(" ")
        } else {
            subscription.quality.maxHeight?.let { "${it}p" } ?: stringResource(R.string.quality_best)
        }
    val checked =
        subscription.lastCheckedAt?.let {
            DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .glassSurface(RoundedCornerShape(24.dp))
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                GlintIcons.Follow,
                contentDescription = null,
                tint = if (subscription.enabled) MaterialTheme.colorScheme.primary else SecondaryText,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = subscription.title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(subscription.source.takeIf { it.isNotBlank() }, kind).joinToString(" · "),
                    color = SecondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text =
                when {
                    isChecking -> stringResource(R.string.following_checking)
                    !subscription.enabled -> stringResource(R.string.following_paused)
                    else ->
                        listOfNotNull(
                                checked?.let { stringResource(R.string.following_checked, it) },
                                pluralStringResource(
                                    R.plurals.following_new, subscription.downloadedCount, subscription.downloadedCount),
                            )
                            .joinToString(" · ")
                },
            color = SecondaryText,
            fontSize = 12.sp,
        )
        subscription.lastError?.let {
            // A known cause in plain words; otherwise only that the channel could not be read.
            val kind = ErrorKind.of(it)
            Text(
                text = stringResource(if (kind == ErrorKind.Unknown) R.string.probe_failed else kind.message),
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (subscription.enabled) {
                GlassPill(
                    text = stringResource(R.string.check_now),
                    icon = GlintIcons.Retry,
                    onClick = { if (!isChecking) Following.checkNow(subscription.id) },
                )
            }
            GlassPill(
                text = stringResource(if (subscription.enabled) R.string.following_pause else R.string.following_resume),
                icon = if (subscription.enabled) GlintIcons.Pause else GlintIcons.Open,
                onClick = { Following.setEnabled(subscription.id, !subscription.enabled) },
            )
            GlassPill(
                text = stringResource(R.string.unfollow),
                tint = MaterialTheme.colorScheme.error,
                onClick = { confirmUnfollow = true },
            )
        }
    }
    if (confirmUnfollow) {
        AlertDialog(
            onDismissRequest = { confirmUnfollow = false },
            title = { Text(stringResource(R.string.unfollow_title)) },
            text = { Text(stringResource(R.string.unfollow_body, subscription.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmUnfollow = false
                        Following.unfollow(subscription.id)
                    }) {
                        Text(stringResource(R.string.unfollow), color = MaterialTheme.colorScheme.error)
                    }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnfollow = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
