package app.glint.engine

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.withContext

private inline fun <reified T : Enum<T>> SharedPreferences.enum(key: String, default: T): T =
    getString(key, null)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

/** The user's settings, saved on the device and read everywhere through [settings]. */
object SettingsStore {
    private lateinit var preferences: SharedPreferences
    private val mutableSettings = MutableStateFlow(GlintSettings())
    val settings: StateFlow<GlintSettings> = mutableSettings.asStateFlow()
    val current: GlintSettings
        get() = mutableSettings.value

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        mutableSettings.value = read(preferences)
    }

    fun update(transform: (GlintSettings) -> GlintSettings) {
        val next = mutableSettings.updateAndGet(transform)
        if (::preferences.isInitialized) write(next)
    }

    private fun read(p: SharedPreferences): GlintSettings {
        val d = GlintSettings()
        return GlintSettings(
            defaultType = p.enum("defaultType", d.defaultType),
            defaultQuality = p.enum("defaultQuality", d.defaultQuality),
            defaultAudioFormat = p.enum("defaultAudioFormat", d.defaultAudioFormat),
            videoPreference = p.enum("videoPreference", d.videoPreference),
            concurrentDownloads =
                p.getInt("concurrentDownloads", d.concurrentDownloads)
                    .coerceIn(GlintSettings.ConcurrentRange.first, GlintSettings.ConcurrentRange.last),
            fasterDownloads = p.getBoolean("fasterDownloads", d.fasterDownloads),
            folderLayout = p.enum("folderLayout", d.folderLayout),
            subtitles = p.getBoolean("subtitles", d.subtitles),
            subtitleLanguages = p.getString("subtitleLanguages", null) ?: d.subtitleLanguages,
            autoSubtitles = p.getBoolean("autoSubtitles", d.autoSubtitles),
            embedThumbnail = p.getBoolean("embedThumbnail", d.embedThumbnail),
            embedMetadata = p.getBoolean("embedMetadata", d.embedMetadata),
            sponsorBlock = p.enum("sponsorBlock", d.sponsorBlock),
            proxy = p.getString("proxy", null) ?: d.proxy,
            speedLimit = p.getString("speedLimit", null) ?: d.speedLimit,
            notifyFinished = p.getBoolean("notifyFinished", d.notifyFinished),
            updateChannel = p.enum("updateChannel", d.updateChannel),
            updateOnLaunch = p.getBoolean("updateOnLaunch", d.updateOnLaunch),
            storageLocation = p.enum("storageLocation", d.storageLocation),
            customFolderUri = p.getString("customFolderUri", null) ?: d.customFolderUri,
            customFolderName = p.getString("customFolderName", null) ?: d.customFolderName,
            followIntervalHours =
                p.getInt("followIntervalHours", d.followIntervalHours).takeIf { it in GlintSettings.FollowIntervals }
                    ?: d.followIntervalHours,
            followWifiOnly = p.getBoolean("followWifiOnly", d.followWifiOnly),
            theme = p.getString("theme", null) ?: d.theme,
        )
    }

    private fun write(s: GlintSettings) {
        preferences.edit()
            .putString("defaultType", s.defaultType.name)
            .putString("defaultQuality", s.defaultQuality.name)
            .putString("defaultAudioFormat", s.defaultAudioFormat.name)
            .putString("videoPreference", s.videoPreference.name)
            .putInt("concurrentDownloads", s.concurrentDownloads)
            .putBoolean("fasterDownloads", s.fasterDownloads)
            .putString("folderLayout", s.folderLayout.name)
            .putBoolean("subtitles", s.subtitles)
            .putString("subtitleLanguages", s.subtitleLanguages)
            .putBoolean("autoSubtitles", s.autoSubtitles)
            .putBoolean("embedThumbnail", s.embedThumbnail)
            .putBoolean("embedMetadata", s.embedMetadata)
            .putString("sponsorBlock", s.sponsorBlock.name)
            .putString("proxy", s.proxy)
            .putString("speedLimit", s.speedLimit)
            .putBoolean("notifyFinished", s.notifyFinished)
            .putString("updateChannel", s.updateChannel.name)
            .putBoolean("updateOnLaunch", s.updateOnLaunch)
            .putString("storageLocation", s.storageLocation.name)
            .putString("customFolderUri", s.customFolderUri)
            .putString("customFolderName", s.customFolderName)
            .putInt("followIntervalHours", s.followIntervalHours)
            .putBoolean("followWifiOnly", s.followWifiOnly)
            .putString("theme", s.theme)
            .apply()
    }
}

/** Saved yt-dlp command templates. New installs start with a few useful ones. */
object CommandStore {
    private const val Key = "templates"
    private lateinit var preferences: SharedPreferences
    private val mutableTemplates = MutableStateFlow<List<CommandTemplate>>(emptyList())
    val templates: StateFlow<List<CommandTemplate>> = mutableTemplates.asStateFlow()

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences("commands", Context.MODE_PRIVATE)
        mutableTemplates.value =
            preferences.getString(Key, null)?.let(CommandTemplates::decode) ?: CommandTemplates.Starters
    }

    fun newId(): String = UUID.randomUUID().toString()

    /** Adds a template, or replaces the one with the same id. */
    fun save(template: CommandTemplate) {
        val next = mutableTemplates.updateAndGet { list ->
            if (list.any { it.id == template.id }) list.map { if (it.id == template.id) template else it }
            else list + template
        }
        write(next)
    }

    fun delete(id: String) {
        write(mutableTemplates.updateAndGet { list -> list.filterNot { it.id == id } })
    }

    private fun write(templates: List<CommandTemplate>) {
        if (::preferences.isInitialized) preferences.edit().putString(Key, CommandTemplates.encode(templates)).apply()
    }
}

/** The cookies passed to yt-dlp, so it can reach videos that need an account. */
object CookieStore {
    /** Larger files are not cookie exports. */
    private const val MaxImportBytes = 5L * 1024 * 1024

    private lateinit var file: File
    private val mutableSites = MutableStateFlow<List<String>>(emptyList())
    /** The sites with saved cookies. */
    val sites: StateFlow<List<String>> = mutableSites.asStateFlow()

    fun initialize(context: Context) {
        file = File(context.filesDir, "cookies.txt")
        mutableSites.value = runCatching { NetscapeCookies.sites(file.readText()) }.getOrDefault(emptyList())
    }

    /** The cookies file for yt-dlp, or null when there are no cookies. */
    fun path(): String? = file.takeIf { mutableSites.value.isNotEmpty() && it.isFile }?.absolutePath

    /** Adds the cookies in a cookies.txt file. False when the file holds no cookies. */
    suspend fun import(context: Context, uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            val text =
                runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            val bytes = input.readNBytesCompat(MaxImportBytes + 1)
                            if (bytes.size > MaxImportBytes) null else bytes.toString(Charsets.UTF_8)
                        }
                    }
                    .getOrNull() ?: return@withContext false
            val lines = NetscapeCookies.lines(text)
            if (lines.isEmpty()) return@withContext false
            store(NetscapeCookies.merge(existing(), lines))
            true
        }

    /** Saves cookies a site set in the sign-in browser, replacing that site's older cookies. */
    suspend fun saveFromBrowser(host: String, cookieHeader: String): Int =
        withContext(Dispatchers.IO) {
            val lines = NetscapeCookies.fromBrowser(host, cookieHeader, System.currentTimeMillis() / 1000)
            if (lines.isNotEmpty()) store(NetscapeCookies.merge(existing(), lines))
            lines.size
        }

    suspend fun clear() =
        withContext(Dispatchers.IO) {
            file.delete()
            mutableSites.value = emptyList()
        }

    private fun existing(): String = runCatching { file.readText() }.getOrDefault("")

    private fun store(text: String) {
        val temporary = File(file.parentFile, "cookies.txt.tmp")
        temporary.writeText(text)
        if (!temporary.renameTo(file)) {
            file.writeText(text)
            temporary.delete()
        }
        mutableSites.update { NetscapeCookies.sites(text) }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (total < limit) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), limit - total).toInt())
            if (read < 0) break
            output.write(buffer, 0, read)
            total += read
        }
        return output.toByteArray()
    }
}
