package app.glint.ui.screens

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.os.Build
import android.webkit.CookieManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.glint.R
import app.glint.SignInActivity
import app.glint.engine.AudioFormat
import app.glint.engine.CookieStore
import app.glint.engine.DownloadArgs
import app.glint.engine.Engine
import app.glint.engine.FolderLayout
import app.glint.engine.MediaType
import app.glint.engine.Quality
import app.glint.engine.SettingsStore
import app.glint.engine.SponsorBlockMode
import app.glint.engine.Storage
import app.glint.engine.StorageLocation
import app.glint.engine.UpdateChannel
import app.glint.engine.VideoPreference
import app.glint.engine.GlintSettings
import app.glint.ui.ChoiceSetting
import app.glint.ui.GlassPill
import app.glint.ui.GlassSegmented
import app.glint.ui.GlassTextField
import app.glint.ui.SecondaryText
import app.glint.ui.SettingText
import app.glint.ui.SettingsSection
import app.glint.ui.SwitchSetting
import app.glint.ui.ThemePicker
import app.glint.ui.icons.GlintIcons
import app.glint.ui.theme.LocalAppTheme
import app.glint.ui.theme.PhotoBackground
import kotlinx.coroutines.launch

private val FolderLayout.label: Int
    get() =
        when (this) {
            FolderLayout.OneFolder -> R.string.folders_one
            FolderLayout.ByType -> R.string.folders_type
            FolderLayout.BySite -> R.string.folders_site
        }

private val SponsorBlockMode.label: Int
    get() =
        when (this) {
            SponsorBlockMode.Off -> R.string.sponsorblock_off
            SponsorBlockMode.Mark -> R.string.sponsorblock_mark
            SponsorBlockMode.Remove -> R.string.sponsorblock_remove
        }

private val StorageLocation.label: Int
    get() =
        when (this) {
            StorageLocation.Downloads -> R.string.save_to_downloads
            StorageLocation.MediaFolders -> R.string.save_to_media
            StorageLocation.CustomFolder -> R.string.save_to_custom
        }

private fun isIgnoringBatteryLimits(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

/** Asks Android to let Glint start downloads from the background, for followed channels. */
@SuppressLint("BatteryLife")
private fun askToIgnoreBatteryLimits(context: Context) {
    val request =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    try {
        context.startActivity(request)
    } catch (_: ActivityNotFoundException) {
        runCatching { context.startActivity(fallback) }
    }
}

private val VideoPreference.label: Int
    get() =
        when (this) {
            VideoPreference.BestQuality -> R.string.video_best_quality
            VideoPreference.Compatible -> R.string.video_compatible
        }

private fun appVersion(context: Context): String =
    runCatching {
            val info =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        android.content.pm.PackageManager.PackageInfoFlags.of(0),
                    )
                } else {
                    @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, 0)
                }
            info.versionName
        }
        .getOrNull()
        .orEmpty()

@Composable
fun SettingsScreen(bottomInset: Dp) {
    val settings by SettingsStore.settings.collectAsStateWithLifecycle()
    val cookieSites by CookieStore.sites.collectAsStateWithLifecycle()
    val engine by Engine.state.collectAsStateWithLifecycle()
    val version by Engine.version.collectAsStateWithLifecycle()
    val galleryVersion by Engine.galleryVersion.collectAsStateWithLifecycle()
    val lastUpdate by Engine.lastUpdate.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deviceLanguage = LocalConfiguration.current.locales[0].language
    fun change(transform: (GlintSettings) -> GlintSettings) = SettingsStore.update(transform)

    val pickFolder =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            if (Storage.keepAccess(context, uri)) {
                val name = Storage.folderName(context, uri)
                change {
                    it.copy(
                        storageLocation = StorageLocation.CustomFolder,
                        customFolderUri = uri.toString(),
                        customFolderName = name,
                    )
                }
            } else {
                Toast.makeText(context, R.string.folder_failed, Toast.LENGTH_LONG).show()
            }
        }
    var backgroundAllowed by remember { mutableStateOf(isIgnoringBatteryLimits(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    // The battery setting is changed in a system screen, so it is read again on return.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) backgroundAllowed = isIgnoringBatteryLimits(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pickPhoto =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                if (PhotoBackground.set(context, uri)) {
                    change { it.copy(theme = PhotoBackground.ThemeId) }
                } else {
                    Toast.makeText(context, R.string.photo_failed, Toast.LENGTH_LONG).show()
                }
            }
        }

    val importCookies =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val imported = CookieStore.import(context, uri)
                Toast.makeText(
                        context,
                        if (imported) R.string.cookies_imported else R.string.cookies_import_failed,
                        Toast.LENGTH_SHORT,
                    )
                    .show()
            }
        }

    Page(title = stringResource(R.string.tab_settings), bottomInset = bottomInset) {
        item(key = "appearance") {
            val theme = LocalAppTheme.current
            SettingsSection(stringResource(R.string.section_appearance), Modifier.padding(horizontal = 20.dp)) {
                Column(
                    modifier = Modifier.padding(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SettingText(
                        title = stringResource(R.string.theme),
                        subtitle = stringResource(theme.name),
                        modifier = Modifier.padding(horizontal = 18.dp),
                    )
                    ThemePicker(
                        selected = theme,
                        onSelect = { picked -> change { it.copy(theme = picked.id) } },
                        onPickPhoto = {
                            pickPhoto.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    )
                    if (theme.id == PhotoBackground.ThemeId) {
                        Text(
                            text = stringResource(R.string.photo_change_hint),
                            modifier = Modifier.padding(horizontal = 18.dp),
                            color = SecondaryText,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                }
            }
        }

        item(key = "downloads") {
            SettingsSection(stringResource(R.string.section_downloads), Modifier.padding(horizontal = 20.dp)) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SettingText(stringResource(R.string.default_type), null, Modifier.padding(horizontal = 4.dp))
                    GlassSegmented(
                        options = MediaType.entries,
                        selected = settings.defaultType,
                        label = {
                            stringResource(if (it == MediaType.Video) R.string.type_video else R.string.type_audio)
                        },
                        onSelect = { type -> change { it.copy(defaultType = type) } },
                    )
                }
                ChoiceSetting(
                    title = stringResource(R.string.default_quality),
                    subtitle = stringResource(R.string.default_quality_hint),
                    options = Quality.entries,
                    selected = settings.defaultQuality,
                    label = { q -> q.maxHeight?.let { "${it}p" } ?: stringResource(R.string.quality_best) },
                    onSelect = { q -> change { it.copy(defaultQuality = q) } },
                )
                ChoiceSetting(
                    title = stringResource(R.string.default_audio_format),
                    options = AudioFormat.entries,
                    selected = settings.defaultAudioFormat,
                    label = { f -> f.codec?.uppercase() ?: stringResource(R.string.audio_original) },
                    onSelect = { f -> change { it.copy(defaultAudioFormat = f) } },
                )
                ChoiceSetting(
                    title = stringResource(R.string.video_preference),
                    subtitle = stringResource(R.string.video_preference_hint),
                    options = VideoPreference.entries,
                    selected = settings.videoPreference,
                    label = { stringResource(it.label) },
                    detail = { if (it == VideoPreference.Compatible) "H.264 · AAC · MP4" else "AV1 · VP9 · H.264" },
                    onSelect = { p -> change { it.copy(videoPreference = p) } },
                )
                ChoiceSetting(
                    title = stringResource(R.string.concurrent_downloads),
                    options = GlintSettings.ConcurrentRange.toList(),
                    selected = settings.concurrentDownloads,
                    label = { it.toString() },
                    onSelect = { n -> change { it.copy(concurrentDownloads = n) } },
                )
                SwitchSetting(
                    title = stringResource(R.string.faster_downloads),
                    subtitle = stringResource(R.string.faster_downloads_hint),
                    checked = settings.fasterDownloads,
                    onCheckedChange = { on -> change { it.copy(fasterDownloads = on) } },
                )
                val customReady = Storage.customTree(context, settings) != null
                ChoiceSetting(
                    title = stringResource(R.string.save_to),
                    subtitle =
                        when (settings.storageLocation) {
                            StorageLocation.Downloads -> "Download/Glint"
                            StorageLocation.MediaFolders -> "Movies/Glint · Music/Glint"
                            StorageLocation.CustomFolder ->
                                if (customReady) settings.customFolderName.ifBlank { "…" }
                                else stringResource(R.string.save_to_custom_missing)
                        },
                    options = StorageLocation.entries,
                    selected = settings.storageLocation,
                    label = { stringResource(it.label) },
                    onSelect = { location ->
                        if (location == StorageLocation.CustomFolder) {
                            pickFolder.launch(settings.customFolderUri.takeIf { it.isNotBlank() }?.let(Uri::parse))
                        } else {
                            change { it.copy(storageLocation = location) }
                        }
                    },
                )
                if (settings.storageLocation == StorageLocation.CustomFolder) {
                    Text(
                        text = stringResource(R.string.save_to_custom_hint),
                        modifier = Modifier.padding(horizontal = 18.dp),
                        color = SecondaryText,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
                ChoiceSetting(
                    title = stringResource(R.string.folders),
                    subtitle = stringResource(R.string.folders_hint),
                    options = FolderLayout.entries,
                    selected = settings.folderLayout,
                    label = { stringResource(it.label) },
                    onSelect = { layout -> change { it.copy(folderLayout = layout) } },
                )
            }
        }

        item(key = "extras") {
            SettingsSection(stringResource(R.string.section_extras), Modifier.padding(horizontal = 20.dp)) {
                SwitchSetting(
                    title = stringResource(R.string.subtitles),
                    subtitle = stringResource(R.string.subtitles_hint),
                    checked = settings.subtitles,
                    onCheckedChange = { on -> change { it.copy(subtitles = on) } },
                )
                if (settings.subtitles) {
                    Column(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        GlassTextField(
                            value = settings.subtitleLanguages,
                            onValueChange = { text -> change { it.copy(subtitleLanguages = text) } },
                            placeholder =
                                DownloadArgs.subtitleLanguages(GlintSettings(), deviceLanguage),
                            monospace = true,
                        )
                        Text(
                            text = stringResource(R.string.subtitle_languages_hint),
                            color = SecondaryText,
                            fontSize = 12.sp,
                        )
                    }
                    SwitchSetting(
                        title = stringResource(R.string.auto_subtitles),
                        subtitle = stringResource(R.string.auto_subtitles_hint),
                        checked = settings.autoSubtitles,
                        onCheckedChange = { on -> change { it.copy(autoSubtitles = on) } },
                    )
                }
                SwitchSetting(
                    title = stringResource(R.string.embed_thumbnail),
                    subtitle = stringResource(R.string.embed_thumbnail_hint),
                    checked = settings.embedThumbnail,
                    onCheckedChange = { on -> change { it.copy(embedThumbnail = on) } },
                )
                SwitchSetting(
                    title = stringResource(R.string.embed_metadata),
                    subtitle = stringResource(R.string.embed_metadata_hint),
                    checked = settings.embedMetadata,
                    onCheckedChange = { on -> change { it.copy(embedMetadata = on) } },
                )
                ChoiceSetting(
                    title = stringResource(R.string.sponsorblock),
                    subtitle = stringResource(R.string.sponsorblock_hint),
                    options = SponsorBlockMode.entries,
                    selected = settings.sponsorBlock,
                    label = { stringResource(it.label) },
                    onSelect = { mode -> change { it.copy(sponsorBlock = mode) } },
                )
            }
        }

        item(key = "network") {
            SettingsSection(stringResource(R.string.section_network), Modifier.padding(horizontal = 20.dp)) {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SettingText(
                        title = stringResource(R.string.cookies),
                        subtitle =
                            if (cookieSites.isEmpty()) stringResource(R.string.cookies_none)
                            else
                                pluralStringResource(
                                    R.plurals.cookies_sites,
                                    cookieSites.size,
                                    cookieSites.take(4).joinToString(", ") +
                                        if (cookieSites.size > 4) "…" else "",
                                ),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassPill(
                            text = stringResource(R.string.sign_in),
                            icon = GlintIcons.Globe,
                            onClick = { context.startActivity(Intent(context, SignInActivity::class.java)) },
                        )
                        GlassPill(
                            text = stringResource(R.string.import_cookies),
                            icon = GlintIcons.Folder,
                            onClick = { importCookies.launch(arrayOf("*/*")) },
                        )
                    }
                    if (cookieSites.isNotEmpty()) {
                        GlassPill(
                            text = stringResource(R.string.clear_cookies),
                            icon = GlintIcons.Trash,
                            tint = MaterialTheme.colorScheme.error,
                            onClick = {
                                scope.launch {
                                    CookieStore.clear()
                                    CookieManager.getInstance().removeAllCookies(null)
                                }
                            },
                        )
                    }
                }
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingText(stringResource(R.string.proxy), stringResource(R.string.proxy_hint))
                    GlassTextField(
                        value = settings.proxy,
                        onValueChange = { text -> change { it.copy(proxy = text.trim()) } },
                        placeholder = "socks5://127.0.0.1:1080",
                        monospace = true,
                        keyboardType = KeyboardType.Uri,
                    )
                }
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val valid = GlintSettings.isValidSpeedLimit(settings.speedLimit)
                    SettingText(stringResource(R.string.speed_limit), stringResource(R.string.speed_limit_hint))
                    GlassTextField(
                        value = settings.speedLimit,
                        onValueChange = { text -> change { it.copy(speedLimit = text.trim()) } },
                        placeholder = "2M",
                        monospace = true,
                        isError = !valid,
                    )
                    if (!valid) {
                        Text(
                            text = stringResource(R.string.speed_limit_invalid),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        item(key = "following") {
            SettingsSection(stringResource(R.string.library_following), Modifier.padding(horizontal = 20.dp)) {
                ChoiceSetting(
                    title = stringResource(R.string.follow_interval),
                    subtitle = stringResource(R.string.follow_interval_hint),
                    options = GlintSettings.FollowIntervals,
                    selected = settings.followIntervalHours,
                    label = { stringResource(R.string.hours_short, it) },
                    onSelect = { hours -> change { it.copy(followIntervalHours = hours) } },
                )
                SwitchSetting(
                    title = stringResource(R.string.follow_wifi_only),
                    checked = settings.followWifiOnly,
                    onCheckedChange = { on -> change { it.copy(followWifiOnly = on) } },
                )
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SettingText(
                        title = stringResource(R.string.background_downloads),
                        subtitle =
                            stringResource(
                                if (backgroundAllowed) R.string.background_downloads_on
                                else R.string.background_downloads_off),
                    )
                    if (!backgroundAllowed) {
                        GlassPill(
                            text = stringResource(R.string.background_downloads_allow),
                            icon = GlintIcons.Check,
                            tint = MaterialTheme.colorScheme.primary,
                            onClick = { askToIgnoreBatteryLimits(context) },
                        )
                    }
                }
            }
        }

        item(key = "notifications") {
            SettingsSection(stringResource(R.string.section_notifications), Modifier.padding(horizontal = 20.dp)) {
                SwitchSetting(
                    title = stringResource(R.string.notify_finished),
                    subtitle = stringResource(R.string.notify_finished_hint),
                    checked = settings.notifyFinished,
                    onCheckedChange = { on -> change { it.copy(notifyFinished = on) } },
                )
            }
        }

        item(key = "engine") {
            SettingsSection("yt-dlp", Modifier.padding(horizontal = 20.dp)) {
                ChoiceSetting(
                    title = stringResource(R.string.update_channel),
                    subtitle = stringResource(R.string.update_channel_hint),
                    options = UpdateChannel.entries,
                    selected = settings.updateChannel,
                    label = {
                        stringResource(
                            if (it == UpdateChannel.Stable) R.string.channel_stable else R.string.channel_nightly)
                    },
                    onSelect = { channel -> change { it.copy(updateChannel = channel) } },
                )
                SwitchSetting(
                    title = stringResource(R.string.update_on_launch),
                    checked = settings.updateOnLaunch,
                    onCheckedChange = { on -> change { it.copy(updateOnLaunch = on) } },
                )
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val status =
                        when {
                            engine == Engine.State.Updating -> stringResource(R.string.engine_updating)
                            lastUpdate == Engine.UpdateResult.Updated -> stringResource(R.string.update_done)
                            lastUpdate == Engine.UpdateResult.UpToDate -> stringResource(R.string.update_current)
                            lastUpdate == Engine.UpdateResult.Failed -> stringResource(R.string.update_failed)
                            lastUpdate == Engine.UpdateResult.Busy -> stringResource(R.string.update_busy)
                            else -> null
                        }
                    SettingText(
                        title = stringResource(R.string.ytdlp_version, version ?: "…"),
                        subtitle = status,
                    )
                    // The second downloader, for pictures and posts yt-dlp does not get.
                    galleryVersion?.let {
                        Text(text = "gallery-dl $it", color = SecondaryText, fontSize = 12.5.sp)
                    }
                    GlassPill(
                        text = stringResource(R.string.update_now),
                        icon = GlintIcons.Retry,
                        onClick = Engine::updateNow,
                    )
                }
            }
        }

        item(key = "about") {
            val appVersion = remember { appVersion(context) }
            Text(
                text = stringResource(R.string.about, appVersion),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 4.dp),
                color = SecondaryText,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
    }
}
