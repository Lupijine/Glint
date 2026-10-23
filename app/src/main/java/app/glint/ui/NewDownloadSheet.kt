package app.glint.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.glint.R
import android.widget.Toast
import app.glint.engine.AudioFormat
import app.glint.engine.Chapter
import app.glint.engine.Chapters
import app.glint.engine.Clips
import app.glint.engine.ErrorKind
import app.glint.engine.CommandLine
import app.glint.engine.CommandStore
import app.glint.engine.CommandTemplate
import app.glint.engine.DownloadOutput
import app.glint.engine.DownloadRequest
import app.glint.engine.ErrorText
import app.glint.engine.Following
import app.glint.engine.FormatChoices
import app.glint.engine.Links
import app.glint.engine.MediaFormat
import app.glint.engine.MediaType
import app.glint.engine.PlaylistEntry
import app.glint.engine.Probe
import app.glint.engine.Prober
import app.glint.engine.Quality
import app.glint.engine.Search
import app.glint.engine.SettingsStore
import app.glint.engine.VideoChoice
import app.glint.ui.icons.GlintIcons
import app.glint.ui.theme.glassSurface
import app.glint.ui.theme.LocalGlassEffectsEnabled
import app.glint.ui.theme.liquidGlass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private sealed interface ProbeState {
    data object Idle : ProbeState

    data object Loading : ProbeState

    data class Failed(val message: String?) : ProbeState

    data class Ready(val probe: Probe) : ProbeState
}

/** What the sheet downloads: Glint's own video or audio choices, or a saved command. */
private enum class SheetKind(val label: Int) {
    Video(R.string.type_video),
    Audio(R.string.type_audio),
    Command(R.string.type_command),
}

/**
 * The new download sheet. A link is read as soon as it is pasted: a video offers its real
 * qualities with sizes, and a playlist lists its videos to pick from. Downloading never has to
 * wait for that: until the link is read, a preset quality is used.
 */
@Composable
internal fun NewDownloadSheet(
    initialUrl: String,
    onClose: () -> Unit,
    onDownload: (List<DownloadRequest>) -> Unit,
    initialCommandId: String? = null,
    windowManagedInsets: Boolean = false,
) {
    val defaults = remember { SettingsStore.current }
    val templates by CommandStore.templates.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var type by rememberSaveable { mutableStateOf(defaults.defaultType) }
    var quality by rememberSaveable { mutableStateOf(defaults.defaultQuality) }
    var audioFormat by rememberSaveable { mutableStateOf(defaults.defaultAudioFormat) }
    var commandMode by rememberSaveable { mutableStateOf(initialCommandId != null) }
    var commandId by rememberSaveable { mutableStateOf(initialCommandId) }
    var wholePlaylist by rememberSaveable { mutableStateOf(false) }
    // A chosen resolution, or null for the best one.
    var choiceHeight by rememberSaveable { mutableStateOf<Int?>(null) }
    // One exact stream picked from the full list; it overrides the quality chips.
    var customFormatId by rememberSaveable { mutableStateOf<String?>(null) }
    var showAllFormats by rememberSaveable { mutableStateOf(false) }
    // The link these choices belong to, so a new link starts fresh but rotation does not.
    var choicesFor by rememberSaveable { mutableStateOf<String?>(null) }
    var excluded by remember { mutableStateOf(emptySet<String>()) }
    var probeState by remember { mutableStateOf<ProbeState>(ProbeState.Idle) }
    var probeAttempt by remember { mutableIntStateOf(0) }
    // Words typed instead of a link, once the user asks to search for them.
    var searchQuery by rememberSaveable { mutableStateOf<String?>(null) }
    var trimOn by rememberSaveable { mutableStateOf(false) }
    var clipStart by rememberSaveable { mutableIntStateOf(0) }
    var clipEnd by rememberSaveable { mutableIntStateOf(0) }
    var startText by rememberSaveable { mutableStateOf("") }
    var endText by rememberSaveable { mutableStateOf("") }
    var preciseCut by rememberSaveable { mutableStateOf(false) }
    // Chosen chapters by position, and whether chapters in a row become one clip.
    var chosenChapters by rememberSaveable { mutableStateOf(listOf<Int>()) }
    var joinChapters by rememberSaveable { mutableStateOf(false) }
    var showChapters by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val followed by Following.subscriptions.collectAsStateWithLifecycle()

    val link = remember(url) { Links.first(url) }
    LaunchedEffect(link, wholePlaylist, probeAttempt, searchQuery) {
        val query = searchQuery
        val target =
            when {
                link != null -> link
                query != null -> Search.query(query)
                else -> {
                    probeState = ProbeState.Idle
                    return@LaunchedEffect
                }
            }
        probeState = ProbeState.Loading
        // Typing restarts this effect, so a link is read once the text settles.
        delay(350)
        probeState =
            try {
                ProbeState.Ready(Prober.probe(target, wholePlaylist = wholePlaylist || link == null))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ProbeState.Failed(ErrorText.summarize(error.message))
            }
    }

    val probe = (probeState as? ProbeState.Ready)?.probe
    val video = probe as? Probe.Video
    val playlist = probe as? Probe.Playlist
    val choices = remember(video) { video?.let { FormatChoices.video(it.formats, it.durationSeconds) }.orEmpty() }
    val audioOnly = video != null && video.formats.isNotEmpty() && video.formats.none { it.hasVideo }
    val customFormat = video?.formats?.firstOrNull { it.id == customFormatId }

    LaunchedEffect(probe) {
        val key = probe?.let { "${it.url}|${it is Probe.Playlist}" } ?: return@LaunchedEffect
        if (choicesFor == key) return@LaunchedEffect
        choicesFor = key
        choiceHeight = null
        customFormatId = null
        showAllFormats = false
        // Search results start with nothing selected; a playlist starts with everything.
        excluded =
            if (probe is Probe.Playlist && probe.isSearch) probe.entries.mapTo(HashSet()) { it.url } else emptySet()
        if (audioOnly) type = MediaType.Audio
        val duration = (probe as? Probe.Video)?.durationSeconds ?: 0
        trimOn = false
        chosenChapters = emptyList()
        joinChapters = false
        showChapters = false
        clipStart = 0
        clipEnd = duration
        startText = Clips.format(0)
        endText = Clips.format(duration)
    }

    val command: CommandTemplate? = templates.firstOrNull { it.id == commandId } ?: templates.firstOrNull()
    val kind =
        when {
            commandMode && command != null -> SheetKind.Command
            type == MediaType.Audio -> SheetKind.Audio
            else -> SheetKind.Video
        }
    val selectedChoice: VideoChoice? =
        if (customFormat != null) null
        else choices.firstOrNull { it.height == choiceHeight } ?: FormatChoices.pick(choices, quality)
    // A playlist can list a video more than once; it is downloaded once.
    val downloadable =
        playlist?.entries?.filter { !it.isPlaylist && it.url !in excluded }?.distinctBy { it.url }.orEmpty()

    fun requests(): List<DownloadRequest> {
        val target = link ?: url.trim()
        val presetSelector = FormatChoices.preset(type, quality)
        val presetLabel = FormatChoices.presetLabel(type, quality, audioFormat)
        return when {
            kind == SheetKind.Command && command != null ->
                // A command runs on the whole link, so a playlist stays one download.
                listOf(
                    DownloadRequest(
                        url = target,
                        type = if (CommandLine.extractsAudio(command.args)) MediaType.Audio else MediaType.Video,
                        formatSelector = "",
                        formatLabel = command.name,
                        title = video?.title ?: playlist?.title.orEmpty(),
                        thumbnail = video?.thumbnail,
                        source = probe?.source.orEmpty(),
                        durationSeconds = video?.durationSeconds ?: 0,
                        customArgs = command.args,
                    ))
            video != null -> {
                val (selector, label) =
                    when {
                        customFormat != null ->
                            FormatChoices.selectorFor(customFormat) to
                                (FormatChoices.resolutionLabel(customFormat)
                                    ?: FormatChoices.codecName(customFormat.audioCodec)
                                    ?: customFormat.id)
                        type == MediaType.Audio -> presetSelector to presetLabel
                        selectedChoice != null ->
                            selectedChoice.selector to selectedChoice.label + if (selectedChoice.hdr) " HDR" else ""
                        else -> presetSelector to presetLabel
                    }
                val size =
                    when {
                        customFormat != null -> FormatChoices.size(customFormat, video.durationSeconds)?.bytes
                        type == MediaType.Video -> selectedChoice?.sizeBytes
                        audioFormat == AudioFormat.Original ->
                            FormatChoices.bestAudio(video.formats)?.let {
                                FormatChoices.size(it, video.durationSeconds)?.bytes
                            }
                        else -> null
                    }
                val full = size ?: 0L
                val picked = chosenChapters.sorted().mapNotNull { i -> video.chapters.getOrNull(i)?.let { i to it } }
                fun clipRequest(title: String, start: Int, end: Int) =
                    DownloadRequest(
                        url = target,
                        type = type,
                        formatSelector = selector,
                        formatLabel = label,
                        audioFormat = audioFormat,
                        title = title,
                        thumbnail = video.thumbnail,
                        source = video.source,
                        durationSeconds = video.durationSeconds,
                        sizeBytes = Clips.scaledSize(full, start, end, video.durationSeconds),
                        sectionStart = start,
                        sectionEnd = end,
                        preciseCut = preciseCut,
                    )
                if (picked.isNotEmpty()) {
                    // Chapters in a row can become one clip; otherwise each is its own file.
                    return if (joinChapters && Chapters.areConsecutive(picked.map { it.first })) {
                        val first = picked.first().second
                        val last = picked.last().second
                        listOf(clipRequest(Chapters.joinedTitle(video.title, first, last), first.start, last.end))
                    } else {
                        picked.map { (i, chapter) ->
                            clipRequest(Chapters.title(video.title, i, chapter), chapter.start, chapter.end)
                        }
                    }
                }
                val clip = if (trimOn) Clips.clip(clipStart, clipEnd, video.durationSeconds) else null
                listOf(
                    DownloadRequest(
                        url = target,
                        type = type,
                        formatSelector = selector,
                        formatLabel = label,
                        audioFormat = audioFormat,
                        title = video.title,
                        thumbnail = video.thumbnail,
                        source = video.source,
                        durationSeconds = video.durationSeconds,
                        sizeBytes =
                            (size ?: 0).let { full ->
                                if (clip == null) full else Clips.scaledSize(full, clip.first, clip.second, video.durationSeconds)
                            },
                        sectionStart = clip?.first,
                        sectionEnd = clip?.second,
                        preciseCut = clip != null && preciseCut,
                    ))
            }
            playlist != null -> {
                // Search results are separate videos, so they are not grouped into a folder.
                val folder = if (playlist.isSearch) null else DownloadOutput.folderName(playlist.title)
                downloadable.map { entry ->
                    DownloadRequest(
                        url = entry.url,
                        type = type,
                        formatSelector = presetSelector,
                        formatLabel = presetLabel,
                        audioFormat = audioFormat,
                        title = entry.title,
                        thumbnail = entry.thumbnail,
                        source = playlist.source,
                        durationSeconds = entry.durationSeconds,
                        folder = folder,
                    )
                }
            }
            else ->
                listOf(
                    DownloadRequest(
                        url = target,
                        type = type,
                        formatSelector = presetSelector,
                        formatLabel = presetLabel,
                        audioFormat = audioFormat,
                    ))
        }
    }

    val sheetShape = RoundedCornerShape(34.dp)
    // In a share dialog the native rounded window owns frosting and its backing.
    // Applying another frosted panel here would hide the compositor's blur.
    val sheetMaterial = if (windowManagedInsets && LocalGlassEffectsEnabled.current) {
        Modifier.clip(sheetShape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.055f), Color.Transparent)))
            .border(0.65.dp, Color.White.copy(alpha = 0.22f), sheetShape)
    } else if (windowManagedInsets) Modifier.clip(sheetShape)
        else Modifier.liquidGlass(sheetShape)
    val outerInsets = if (windowManagedInsets) Modifier
        else Modifier.navigationBarsPadding().imePadding().statusBarsPadding().padding(8.dp)

    BoxWithConstraints(
        modifier =
            Modifier.fillMaxWidth()
                .then(outerInsets),
    ) {
        LazyColumn(
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .then(sheetMaterial)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {},
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 22.dp),
        ) {
            item(key = "header") {
                Column {
                    Box(
                        modifier =
                            Modifier.align(Alignment.CenterHorizontally)
                                .size(width = 40.dp, height = 5.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.32f)))
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.new_download),
                            modifier = Modifier.weight(1f),
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        GlassIconButton(
                            icon = GlintIcons.Close,
                            contentDescription = stringResource(R.string.close),
                            onClick = onClose,
                            size = 44,
                        )
                    }
                }
            }

            item(key = "link") {
                Column(
                    modifier = Modifier.padding(top = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FieldLabel(stringResource(R.string.link_label))
                    LinkField(
                        url = url,
                        onUrlChange = {
                            url = it
                            wholePlaylist = false
                            searchQuery = null
                        },
                        onPaste = {
                            val text = clipboard.getText()?.text.orEmpty()
                            url = Links.first(text) ?: text.trim()
                            wholePlaylist = false
                            searchQuery = null
                        },
                        onSearch = { if (Search.canSearch(url)) searchQuery = url.trim() },
                    )
                    if (link == null && searchQuery == null && Search.canSearch(url)) {
                        GlassPill(
                            text = stringResource(R.string.search_for, url.trim()),
                            icon = GlintIcons.Search,
                            tint = MaterialTheme.colorScheme.primary,
                            onClick = { searchQuery = url.trim() },
                        )
                    }
                }
            }

            when (val state = probeState) {
                ProbeState.Idle -> Unit
                ProbeState.Loading ->
                    item(key = "loading") { ProbeLoading(Modifier.padding(top = 14.dp)) }
                is ProbeState.Failed ->
                    item(key = "failed") {
                        ProbeFailed(
                            message = state.message,
                            onRetry = { probeAttempt++ },
                            modifier = Modifier.padding(top = 14.dp),
                        )
                    }
                is ProbeState.Ready ->
                    when (val result = state.probe) {
                        is Probe.Video ->
                            item(key = "preview") {
                                VideoPreview(
                                    video = result,
                                    onLoadPlaylist = { wholePlaylist = true },
                                    modifier = Modifier.padding(top = 14.dp),
                                )
                            }
                        is Probe.Playlist -> {
                            item(key = "playlist") {
                                val isFollowing = followed.any { it.url == result.url }
                                PlaylistHeader(
                                    playlist = result,
                                    selectable = kind != SheetKind.Command,
                                    selectedCount = downloadable.size,
                                    onSelectAll = { excluded = emptySet() },
                                    onSelectNone = {
                                        excluded = result.entries.mapTo(HashSet()) { it.url }
                                    },
                                    isFollowing = isFollowing,
                                    onFollow = {
                                        if (isFollowing) {
                                            followed.firstOrNull { it.url == result.url }?.let { Following.unfollow(it.id) }
                                        } else {
                                            Following.follow(result, type, quality, audioFormat)
                                            Toast.makeText(
                                                    context,
                                                    context.getString(R.string.followed, result.title),
                                                    Toast.LENGTH_LONG,
                                                )
                                                .show()
                                        }
                                    },
                                    modifier = Modifier.padding(top = 14.dp),
                                )
                            }
                            // A playlist can list the same video twice, so rows are keyed by position.
                            val rows = if (kind == SheetKind.Command) emptyList() else result.entries
                            itemsIndexed(rows, key = { index, _ -> "entry $index" }) { _, entry ->
                                PlaylistEntryRow(
                                    entry = entry,
                                    selected = entry.url !in excluded,
                                    onToggle = {
                                        excluded =
                                            if (entry.url in excluded) excluded - entry.url
                                            else excluded + entry.url
                                    },
                                    onOpen = {
                                        url = entry.url
                                        wholePlaylist = true
                                        searchQuery = null
                                    },
                                    // A search result can be opened on its own to pick its quality.
                                    onOpenSingle =
                                        if (result.isSearch) {
                                            {
                                                url = entry.url
                                                wholePlaylist = false
                                                searchQuery = null
                                            }
                                        } else null,
                                )
                            }
                        }
                    }
            }

            item(key = "type") {
                Column(
                    modifier = Modifier.padding(top = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FieldLabel(stringResource(R.string.type_label))
                    val kinds =
                        SheetKind.entries.filter {
                            (it != SheetKind.Command || templates.isNotEmpty()) &&
                                (it != SheetKind.Video || !audioOnly)
                        }
                    GlassSegmented(
                        options = kinds,
                        selected = kind,
                        label = { stringResource(it.label) },
                        onSelect = {
                            commandMode = it == SheetKind.Command
                            if (it != SheetKind.Command) {
                                type = if (it == SheetKind.Audio) MediaType.Audio else MediaType.Video
                                if (customFormat != null && customFormat.hasVideo != (type == MediaType.Video)) {
                                    customFormatId = null
                                }
                            }
                        },
                        height = 52,
                    )
                }
            }

            item(key = "quality") {
                Column(
                    modifier = Modifier.padding(top = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (kind == SheetKind.Command && command != null) {
                        FieldLabel(stringResource(R.string.type_command))
                        ChoiceRow(
                            options = templates,
                            selected = command,
                            label = { it.name },
                            onSelect = { commandId = it.id },
                        )
                        Text(
                            text = command.args,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .glassSurface(RoundedCornerShape(16.dp))
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                            color = SecondaryText,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.command_whole_link),
                            modifier = Modifier.padding(start = 4.dp),
                            color = SecondaryText,
                            fontSize = 12.sp,
                        )
                    } else if (type == MediaType.Video) {
                        FieldLabel(stringResource(R.string.quality_label))
                        if (choices.isNotEmpty()) {
                            ChoiceRow(
                                options = choices,
                                selected = selectedChoice,
                                label = { it.label + if (it.hdr) " HDR" else "" },
                                detail = { choice ->
                                    choice.sizeBytes?.let { formatSize(it, choice.sizeIsEstimate) }
                                        ?: FormatChoices.codecName(choice.codec)
                                },
                                onSelect = {
                                    choiceHeight = it.height
                                    customFormatId = null
                                },
                            )
                        } else {
                            ChoiceRow(
                                options = Quality.entries,
                                selected = quality,
                                label = { q ->
                                    q.maxHeight?.let { "${it}p" } ?: stringResource(R.string.quality_best)
                                },
                                onSelect = { quality = it },
                            )
                        }
                    } else {
                        FieldLabel(stringResource(R.string.audio_format_label))
                        ChoiceRow(
                            options = AudioFormat.entries,
                            selected = audioFormat,
                            label = { f -> f.codec?.uppercase() ?: stringResource(R.string.audio_original) },
                            onSelect = { audioFormat = it },
                        )
                    }
                }
            }

            if (video != null && video.chapters.size >= 2 && kind != SheetKind.Command) {
                item(key = "chapters") {
                    ChaptersHeader(
                        count = video.chapters.size,
                        chosen = chosenChapters.size,
                        expanded = showChapters,
                        onToggleExpanded = { showChapters = !showChapters },
                        onSelectAll = {
                            chosenChapters = video.chapters.indices.toList()
                            trimOn = false
                        },
                        onSelectNone = { chosenChapters = emptyList() },
                        canJoin = Chapters.areConsecutive(chosenChapters),
                        joined = joinChapters,
                        onJoinedChange = { joinChapters = it },
                        precise = preciseCut,
                        onPreciseChange = { preciseCut = it },
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
                if (showChapters) {
                    itemsIndexed(video.chapters, key = { index, _ -> "chapter $index" }) { index, chapter ->
                        ChapterRow(
                            number = index + 1,
                            chapter = chapter,
                            selected = index in chosenChapters,
                            onToggle = {
                                chosenChapters =
                                    if (index in chosenChapters) chosenChapters - index else chosenChapters + index
                                // A chapter choice replaces a trimmed range; the two do not combine.
                                trimOn = false
                            },
                        )
                    }
                }
            }

            if (video != null && video.durationSeconds > 1 && kind != SheetKind.Command) {
                item(key = "trim") {
                    TrimControls(
                        duration = video.durationSeconds,
                        enabled = trimOn,
                        onEnabledChange = {
                            trimOn = it
                            if (it) chosenChapters = emptyList()
                        },
                        start = clipStart,
                        end = clipEnd,
                        startText = startText,
                        endText = endText,
                        onRangeChange = { from, to ->
                            clipStart = from
                            clipEnd = to
                            startText = Clips.format(from)
                            endText = Clips.format(to)
                        },
                        onStartText = { text ->
                            startText = text
                            Clips.parse(text)?.let { clipStart = it.coerceIn(0, (clipEnd - 1).coerceAtLeast(0)) }
                        },
                        onEndText = { text ->
                            endText = text
                            Clips.parse(text)?.let { clipEnd = it.coerceIn(clipStart + 1, video.durationSeconds) }
                        },
                        precise = preciseCut,
                        onPreciseChange = { preciseCut = it },
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }

            // Only the streams that fit the chosen type are listed: audio streams for audio.
            val listedFormats =
                if (video == null || kind == SheetKind.Command) emptyList()
                else FormatChoices.listFor(video.formats, type).asReversed()
            if (video != null && listedFormats.size > 1) {
                item(key = "all formats") {
                    AllFormatsToggle(
                        count = listedFormats.size,
                        expanded = showAllFormats,
                        onToggle = { showAllFormats = !showAllFormats },
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
                if (showAllFormats) {
                    itemsIndexed(listedFormats, key = { index, _ -> "format $index" }) { _, format ->
                        FormatRow(
                            format = format,
                            durationSeconds = video.durationSeconds,
                            selected = format.id == customFormatId,
                            onSelect = {
                                customFormatId = format.id
                                type = if (format.hasVideo) MediaType.Video else MediaType.Audio
                            },
                        )
                    }
                }
            }

            item(key = "download") {
                val requestSize = if (video != null) requests().singleOrNull()?.sizeBytes ?: 0L else 0L
                val sizeIsEstimate =
                    when {
                        customFormat != null -> customFormat.sizeIsEstimate
                        type == MediaType.Video -> selectedChoice?.sizeIsEstimate ?: true
                        else -> FormatChoices.bestAudio(video?.formats.orEmpty())?.sizeIsEstimate ?: true
                    }
                val label =
                    when {
                        kind == SheetKind.Command && command != null ->
                            stringResource(R.string.run_named_command, command.name)
                        playlist != null ->
                            pluralStringResource(R.plurals.download_count, downloadable.size, downloadable.size)
                        video != null &&
                            chosenChapters.size > 1 &&
                            !(joinChapters && Chapters.areConsecutive(chosenChapters)) ->
                            pluralStringResource(R.plurals.download_chapters, chosenChapters.size, chosenChapters.size)
                        requestSize > 0 ->
                            stringResource(R.string.download_with_size, formatSize(requestSize, sizeIsEstimate))
                        else -> stringResource(R.string.download)
                    }
                val canDownload =
                    url.isNotBlank() && (kind == SheetKind.Command || playlist == null || downloadable.isNotEmpty())
                DownloadButton(
                    label = label,
                    enabled = canDownload,
                    onClick = { onDownload(requests()) },
                    modifier = Modifier.padding(top = 22.dp),
                )
            }
        }
    }
}

@Composable
private fun LinkField(url: String, onUrlChange: (String) -> Unit, onPaste: () -> Unit, onSearch: () -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .height(56.dp)
                .glassSurface(RoundedCornerShape(20.dp))
                .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (url.isEmpty()) {
                Text(
                    text = stringResource(R.string.link_or_search_hint),
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = url,
                onValueChange = onUrlChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(8.dp))
        Row(
            modifier =
                Modifier.height(44.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(Color.White.copy(alpha = 0.14f))
                    .clickable(role = Role.Button, onClick = onPaste)
                    .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(GlintIcons.Paste, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.paste),
                color = Color.White,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun ProbeLoading(modifier: Modifier = Modifier) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .glassSurface(RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(12.dp))
        Text(text = stringResource(R.string.probe_loading), color = SecondaryText, fontSize = 13.5.sp)
    }
}

@Composable
private fun ProbeFailed(message: String?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .glassSurface(RoundedCornerShape(20.dp))
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // A known cause in plain words; otherwise only that the link could not be read.
        val kind = ErrorKind.of(message)
        Text(
            text = stringResource(if (kind == ErrorKind.Unknown) R.string.probe_failed else kind.message),
            color = MaterialTheme.colorScheme.error,
            fontSize = 13.5.sp,
            lineHeight = 19.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        Text(text = stringResource(R.string.probe_failed_hint), color = SecondaryText, fontSize = 12.5.sp)
        GlassPill(text = stringResource(R.string.probe_retry), icon = GlintIcons.Retry, onClick = onRetry)
    }
}

@Composable
private fun VideoPreview(video: Probe.Video, onLoadPlaylist: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .glassSurface(RoundedCornerShape(22.dp))
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(url = video.thumbnail, type = MediaType.Video, width = 112, height = 64)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.title,
                    color = Color.White,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 19.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta =
                    listOfNotNull(
                            video.source.takeIf { it.isNotBlank() },
                            video.uploader,
                            video.durationSeconds.takeIf { it > 0 }?.let(::formatDuration),
                        )
                        .joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = meta,
                        color = SecondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (video.inPlaylist) {
            GlassPill(
                text = stringResource(R.string.show_playlist),
                icon = GlintIcons.Playlist,
                onClick = onLoadPlaylist,
            )
        }
    }
}

@Composable
private fun PlaylistHeader(
    playlist: Probe.Playlist,
    selectable: Boolean,
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    isFollowing: Boolean,
    onFollow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectableCount = playlist.entries.count { !it.isPlaylist }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .glassSurface(RoundedCornerShape(22.dp))
                .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (playlist.isSearch) GlintIcons.Search else GlintIcons.Playlist,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta =
                    listOfNotNull(
                            pluralStringResource(R.plurals.playlist_items, playlist.entries.size, playlist.entries.size),
                            playlist.uploader,
                        )
                        .joinToString(" · ")
                Text(text = meta, color = SecondaryText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // A channel's own page lists its tabs; following works on a tab or a playlist of videos.
        if (!playlist.isSearch && selectableCount > 0) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassPill(
                    text = stringResource(if (isFollowing) R.string.following_this else R.string.follow),
                    icon = if (isFollowing) GlintIcons.Check else GlintIcons.Follow,
                    tint = if (isFollowing) Green else MaterialTheme.colorScheme.primary,
                    onClick = onFollow,
                )
                Text(
                    text = stringResource(if (isFollowing) R.string.following_hint_on else R.string.follow_hint),
                    color = SecondaryText,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                )
            }
        }
        if (selectable && selectableCount > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.playlist_selected, selectedCount, selectableCount),
                    modifier = Modifier.weight(1f),
                    color = SecondaryText,
                    fontSize = 12.5.sp,
                )
                if (selectedCount < selectableCount) {
                    GlassPill(text = stringResource(R.string.select_all), onClick = onSelectAll)
                } else {
                    GlassPill(text = stringResource(R.string.select_none), onClick = onSelectNone)
                }
            }
        }
    }
}

@Composable
private fun PlaylistEntryRow(
    entry: PlaylistEntry,
    selected: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onOpenSingle: (() -> Unit)? = null,
) {
    val rowModifier =
        Modifier.fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .let {
                if (entry.isPlaylist) it.clickable(role = Role.Button, onClick = onOpen)
                else it.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            }
            .padding(vertical = 4.dp, horizontal = 4.dp)
    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
        if (entry.isPlaylist) {
            Box(
                modifier = Modifier.size(width = 72.dp, height = 44.dp).glassSurface(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(GlintIcons.Folder, contentDescription = null, tint = SecondaryText, modifier = Modifier.size(22.dp))
            }
        } else {
            Thumbnail(url = entry.thumbnail, type = MediaType.Video, width = 72, height = 44)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                color = if (selected || entry.isPlaylist) Color.White else SecondaryText.copy(alpha = 0.6f),
                fontSize = 13.5.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val details =
                listOfNotNull(entry.uploader, entry.durationSeconds.takeIf { it > 0 }?.let(::formatDuration))
                    .joinToString(" · ")
            if (details.isNotEmpty()) {
                Text(text = details, color = SecondaryText, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(10.dp))
        if (onOpenSingle != null && !entry.isPlaylist) {
            GlassIconButton(
                icon = GlintIcons.Chevron,
                contentDescription = stringResource(R.string.open_result),
                onClick = onOpenSingle,
                size = 34,
            )
            Spacer(Modifier.width(8.dp))
        }
        if (entry.isPlaylist) {
            Icon(GlintIcons.Chevron, contentDescription = null, tint = SecondaryText, modifier = Modifier.size(20.dp))
        } else {
            CheckCircle(checked = selected)
        }
    }
}

@Composable
private fun CheckCircle(checked: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val fill by animateColorAsState(if (checked) accent else Color.Transparent, label = "check fill")
    val rim by animateColorAsState(
        if (checked) accent else Color.White.copy(alpha = 0.35f),
        label = "check rim",
    )
    Box(
        modifier = Modifier.size(24.dp).clip(CircleShape).background(fill).border(1.5.dp, rim, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                GlintIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun AllFormatsToggle(count: Int, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.all_formats, count),
            modifier = Modifier.weight(1f),
            color = SecondaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Icon(
            if (expanded) GlintIcons.ChevronDown else GlintIcons.Chevron,
            contentDescription = null,
            tint = SecondaryText,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun FormatRow(
    format: MediaFormat,
    durationSeconds: Int,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val title =
        listOfNotNull(
                FormatChoices.resolutionLabel(format)
                    ?: if (!format.hasVideo) stringResource(R.string.format_audio_only) else null,
                format.ext.takeIf { it.isNotBlank() },
            )
            .joinToString(" · ")
    val details =
        listOfNotNull(
                FormatChoices.codecName(format.videoCodec),
                FormatChoices.codecName(format.audioCodec),
                format.bitrateKbps?.let { "${it.toInt()} kbps" },
                FormatChoices.size(format, durationSeconds)?.let { formatSize(it.bytes, it.isEstimate) },
                format.language,
                if (format.hasVideo && !format.hasAudio) stringResource(R.string.format_video_only) else null,
                format.note.takeIf { it.isNotBlank() },
            )
            .joinToString(" · ")
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = if (selected) 0.12f else 0.035f))
                .toggleable(value = selected, role = Role.RadioButton, onValueChange = { onSelect() })
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.ifEmpty { format.id },
                color = Color.White,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = details,
                color = SecondaryText,
                fontSize = 11.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(text = format.id, color = SecondaryText.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1)
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                GlintIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun DownloadButton(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(56.dp)
                .liquidGlass(
                    CircleShape,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.72f else 0.08f),
                    interactionSource = interaction,
                )
                .clickable(
                    interactionSource = interaction, indication = null,
                    enabled = enabled, role = Role.Button, onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = GlintIcons.Download,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.onPrimary else SecondaryText.copy(alpha = 0.45f),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                color = if (enabled) MaterialTheme.colorScheme.onPrimary else SecondaryText.copy(alpha = 0.45f),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Downloads only part of a video: a range slider for quick picks, fields for exact times. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrimControls(
    duration: Int,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    start: Int,
    end: Int,
    startText: String,
    endText: String,
    onRangeChange: (Int, Int) -> Unit,
    onStartText: (String) -> Unit,
    onEndText: (String) -> Unit,
    precise: Boolean,
    onPreciseChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .glassSurface(RoundedCornerShape(22.dp))
                .padding(vertical = 4.dp),
    ) {
        SwitchSetting(
            title = stringResource(R.string.trim),
            subtitle =
                if (enabled) stringResource(R.string.trim_length, Clips.format((end - start).coerceAtLeast(0)))
                else stringResource(R.string.trim_hint),
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )
        if (!enabled) return@Column
        RangeSlider(
            value = start.toFloat()..end.toFloat(),
            onValueChange = { range ->
                val from = range.start.toInt()
                val to = range.endInclusive.toInt().coerceAtLeast(from + 1).coerceAtMost(duration)
                onRangeChange(from.coerceAtMost(to - 1), to)
            },
            valueRange = 0f..duration.toFloat(),
            colors =
                SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color.White.copy(alpha = 0.14f),
                ),
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldLabel(stringResource(R.string.trim_start))
                GlassTextField(
                    value = startText,
                    onValueChange = onStartText,
                    placeholder = "0:00",
                    monospace = true,
                    keyboardType = KeyboardType.Text,
                    isError = Clips.parse(startText) == null,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldLabel(stringResource(R.string.trim_end))
                GlassTextField(
                    value = endText,
                    onValueChange = onEndText,
                    placeholder = Clips.format(duration),
                    monospace = true,
                    keyboardType = KeyboardType.Text,
                    isError = Clips.parse(endText).let { it == null || it > duration },
                )
            }
        }
        SwitchSetting(
            title = stringResource(R.string.precise_cut),
            subtitle = stringResource(R.string.precise_cut_hint),
            checked = precise,
            onCheckedChange = onPreciseChange,
        )
    }
}

/** The chapters header: how many there are, how many are chosen, and how to save them. */
@Composable
private fun ChaptersHeader(
    count: Int,
    chosen: Int,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    canJoin: Boolean,
    joined: Boolean,
    onJoinedChange: (Boolean) -> Unit,
    precise: Boolean,
    onPreciseChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .glassSurface(RoundedCornerShape(22.dp))
                .padding(vertical = 4.dp),
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(role = Role.Button, onClick = onToggleExpanded)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                GlintIcons.Scissors,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.chapters_title, count),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text =
                        if (chosen == 0) stringResource(R.string.chapters_hint)
                        else stringResource(R.string.playlist_selected, chosen, count),
                    color = SecondaryText,
                    fontSize = 12.sp,
                )
            }
            Icon(
                if (expanded) GlintIcons.ChevronDown else GlintIcons.Chevron,
                contentDescription = null,
                tint = SecondaryText,
                modifier = Modifier.size(18.dp),
            )
        }
        if (!expanded && chosen == 0) return@Column
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (chosen < count) GlassPill(text = stringResource(R.string.select_all), onClick = onSelectAll)
            if (chosen > 0) GlassPill(text = stringResource(R.string.select_none), onClick = onSelectNone)
        }
        if (canJoin) {
            ChoiceRow(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                options = listOf(false, true),
                selected = joined,
                label = { stringResource(if (it) R.string.chapters_joined else R.string.chapters_separate) },
                onSelect = onJoinedChange,
            )
        }
        if (chosen > 0) {
            SwitchSetting(
                title = stringResource(R.string.precise_cut),
                subtitle = stringResource(R.string.precise_cut_hint),
                checked = precise,
                onCheckedChange = onPreciseChange,
            )
        }
    }
}

@Composable
private fun ChapterRow(number: Int, chapter: Chapter, selected: Boolean, onToggle: () -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = if (selected) 0.10f else 0.03f))
                .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "%02d".format(number),
            color = SecondaryText,
            fontSize = 12.5.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chapter.title,
                color = if (selected) Color.White else Color.White.copy(alpha = 0.85f),
                fontSize = 13.5.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${Clips.format(chapter.start)}–${Clips.format(chapter.end)} · ${Clips.format(chapter.length)}",
                color = SecondaryText,
                fontSize = 11.5.sp,
            )
        }
        Spacer(Modifier.width(10.dp))
        CheckCircle(checked = selected)
    }
}
