package app.glint.engine

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * Runs downloads with yt-dlp: a few at a time, in the order they were added, each with live
 * progress. Every download is saved, so the list and the library survive restarts, and each keeps
 * its partly downloaded pieces until it finishes, so a paused or interrupted download continues
 * where it stopped. A foreground service keeps the process alive while anything is downloading.
 */
object DownloadManager {
    /** The end of a long error output is kept; that is where yt-dlp explains what went wrong. */
    private const val MaxErrorDetail = 6000

    private val mutableItems = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = mutableItems.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = DownloadSlots(SettingsStore.current.concurrentDownloads)

    private class Attempt(val itemId: String) {
        /** Identifies this attempt's yt-dlp process, so stopping an old attempt never stops a new one. */
        val token = UUID.randomUUID().toString()
        val progressUpdates = ProgressUpdateGate()
        var job: Job? = null
    }

    private val attemptLock = Any()
    // Keep the current attempt after completion so the media scanner can safely publish its URI.
    private val attempts = mutableMapOf<String, Attempt>()
    private lateinit var appContext: Context

    private val SpeedRegex = Regex("""\bat\s+(\S+/s)""")

    fun attach(context: Context) {
        appContext = context.applicationContext
        scope.launch {
            items
                .map { list -> list.any { it.isActive } }
                .distinctUntilChanged()
                .collect { active ->
                    // The service stops itself when downloads end, so its notification is removed
                    // in order with its last update rather than racing it from this thread.
                    if (active) {
                        runCatching {
                            ContextCompat.startForegroundService(
                                appContext, Intent(appContext, DownloadService::class.java))
                        }
                    }
                }
        }
        scope.launch {
            SettingsStore.settings
                .map { it.concurrentDownloads }
                .distinctUntilChanged()
                .collect { slots.setLimit(it) }
        }
        scope.launch { restoreAndPersist() }
    }

    fun enqueue(request: DownloadRequest) = enqueue(listOf(request))

    /** Adds downloads to the top of the list; they start in the order given. */
    fun enqueue(requests: List<DownloadRequest>) {
        if (requests.isEmpty()) return
        val now = System.currentTimeMillis()
        val created =
            requests.mapIndexed { index, request ->
                DownloadItem(
                    id = UUID.randomUUID().toString(),
                    url = request.url.trim(),
                    type = request.type,
                    formatSelector = request.formatSelector,
                    formatLabel = request.formatLabel,
                    audioFormat = request.audioFormat,
                    title = request.title,
                    thumbnail = request.thumbnail,
                    source = request.source,
                    durationSeconds = request.durationSeconds,
                    sizeBytes = request.sizeBytes,
                    folder = request.folder,
                    customArgs = request.customArgs,
                    sectionStart = request.sectionStart,
                    sectionEnd = request.sectionEnd,
                    preciseCut = request.preciseCut,
                    subscriptionId = request.subscriptionId,
                    // Newest first, while a batch keeps its own order.
                    createdAt = now + (requests.size - 1 - index),
                )
            }
        synchronized(attemptLock) {
            mutableItems.update { created + it }
            created.forEach(::launchLocked)
        }
    }

    /** Stops a download and keeps what it downloaded, so [resume] continues from there. */
    fun pause(id: String) {
        val attempt = synchronized(attemptLock) {
            val item = mutableItems.value.find { it.id == id } ?: return
            // Copying into a picked folder is quick and cannot continue part way.
            if (!item.isActive || item.status == DownloadItem.Status.Saving) return
            val current = attempts.remove(id)
            mutableItems.update { list ->
                list.map {
                    if (it.id == id) it.copy(status = DownloadItem.Status.Paused, speed = "", etaSeconds = -1) else it
                }
            }
            current
        }
        stop(attempt)
    }

    fun pauseAll() = items.value.filter { it.isActive }.forEach { pause(it.id) }

    /** Continues a paused, interrupted or failed download where it stopped. */
    fun resume(id: String) = retry(id)

    fun resumeAll() =
        items.value
            .filter { it.status == DownloadItem.Status.Paused || it.status == DownloadItem.Status.Interrupted }
            .sortedBy { it.createdAt }
            .forEach { retry(it.id) }

    /** Continues downloads from a followed channel that stopped when Glint was closed. */
    fun resumeInterrupted(subscriptionId: String) =
        items.value
            .filter { it.subscriptionId == subscriptionId && it.status == DownloadItem.Status.Interrupted }
            .forEach { retry(it.id) }

    fun cancel(id: String) {
        val attempt = synchronized(attemptLock) {
            val item = mutableItems.value.find { it.id == id } ?: return
            if (!item.isActive && !item.canResume) return
            val current = attempts.remove(id)
            mutableItems.update { list ->
                list.map { if (it.id == id) it.copy(status = DownloadItem.Status.Canceled, speed = "") else it }
            }
            current
        }
        discardWork(id, attempt?.job)
        stop(attempt)
    }

    /** Starts a download again; a stopped one continues from what it already downloaded. */
    fun retry(id: String) {
        val previous = synchronized(attemptLock) {
            val item = mutableItems.value.find { it.id == id } ?: return
            if (item.isActive) return
            val fresh = item.copy(
                status = DownloadItem.Status.Queued,
                progress = if (item.canResume) item.progress else null,
                speed = "",
                etaSeconds = -1,
                filePath = null,
                fileUri = null,
                error = null,
                errorDetail = null,
                completedAt = null,
                inQueue = true,
                fileMissing = false,
            )
            val current = attempts[id]
            mutableItems.update { list -> list.map { if (it.id == id) fresh else it } }
            launchLocked(fresh)
            current
        }
        stop(previous)
    }

    /** Takes a download off the Downloads page. A finished file stays in the library. */
    fun remove(id: String) {
        val attempt = synchronized(attemptLock) {
            val item = mutableItems.value.find { it.id == id } ?: return
            if (item.status == DownloadItem.Status.Completed) {
                mutableItems.update { list ->
                    list.map { if (it.id == id) it.copy(inQueue = false) else it }
                }
                return
            }
            val current = attempts.remove(id)
            mutableItems.update { list -> list.filterNot { it.id == id } }
            current
        }
        discardWork(id, attempt?.job)
        stop(attempt)
    }

    /** Clears every download that is no longer running or paused from the Downloads page. */
    fun clearFinished() {
        val discarded = synchronized(attemptLock) {
            val finished =
                mutableItems.value.filter {
                    it.inQueue && !it.isActive && it.status != DownloadItem.Status.Paused
                }
            if (finished.isEmpty()) return
            val gone = finished.filter { it.status != DownloadItem.Status.Completed }.map { it.id }
            gone.forEach { attempts.remove(it) }
            val finishedIds = finished.mapTo(HashSet()) { it.id }
            mutableItems.update { list ->
                list.mapNotNull { item ->
                    when {
                        item.id !in finishedIds -> item
                        item.status == DownloadItem.Status.Completed -> item.copy(inQueue = false)
                        else -> null
                    }
                }
            }
            gone
        }
        discarded.forEach { discardWork(it, null) }
    }

    /**
     * Removes a finished download from the library and, when [deleteFile] is set, from the device.
     * Returns false and keeps the entry when the file could not be deleted.
     */
    suspend fun deleteFromLibrary(id: String, deleteFile: Boolean): Boolean {
        val item = synchronized(attemptLock) { mutableItems.value.find { it.id == id } } ?: return true
        if (deleteFile && !withContext(Dispatchers.IO) { deleteMediaFile(item) }) return false
        synchronized(attemptLock) {
            attempts.remove(id)
            mutableItems.update { list -> list.filterNot { it.id == id } }
        }
        return true
    }

    /** Notices finished files that were deleted or moved outside Glint. */
    fun refreshLibrary() {
        scope.launch {
            val finished = items.value.filter { it.hasFile }
            val checked = finished.mapTo(HashSet()) { it.id }
            val missing = finished.filterNot { Storage.exists(appContext, it) }.mapTo(HashSet()) { it.id }
            synchronized(attemptLock) {
                mutableItems.update { list ->
                    list.map {
                        val isMissing = it.id in missing
                        if (it.id in checked && it.fileMissing != isMissing) it.copy(fileMissing = isMissing)
                        else it
                    }
                }
            }
        }
    }

    private fun deleteMediaFile(item: DownloadItem): Boolean {
        val file = item.filePath?.let(::File)
        if (file == null) {
            // Saved in a picked folder: only its document provider can delete it.
            val uri = item.fileUri?.let(Uri::parse) ?: return true
            return Storage.deleteDocument(appContext, uri) || !Storage.exists(appContext, item)
        }
        if (file.exists() && !file.delete()) {
            // A file Glint no longer owns, for example after a reinstall, can still be removed
            // through the media store entry Glint created.
            val uri = item.fileUri?.let(Uri::parse) ?: return false
            val removed = runCatching { appContext.contentResolver.delete(uri, null, null) > 0 }
            if (removed.getOrDefault(false).not() && file.exists()) return false
        }
        // Rescanning a deleted path removes its media store entry, so galleries stop showing it.
        MediaScannerConnection.scanFile(appContext, arrayOf(file.absolutePath), null, null)
        return true
    }

    /** A download's own folder for partly downloaded pieces, kept until it finishes or is dropped. */
    private fun workDirectory(id: String) = File(appContext.cacheDir, "downloads/$id")

    /**
     * Deletes a dropped download's pieces once its yt-dlp process has stopped, unless it was
     * started again in the meantime.
     */
    private fun discardWork(id: String, job: Job?) {
        val delete: () -> Unit = {
            scope.launch {
                // Give yt-dlp and ffmpeg a moment to exit after being stopped.
                if (job != null) delay(1500)
                val wanted = synchronized(attemptLock) {
                    mutableItems.value.any { it.id == id && (it.isActive || it.canResume) }
                }
                if (!wanted) {
                    workDirectory(id).deleteRecursively()
                    File(appContext.getExternalFilesDir("staging") ?: File(appContext.filesDir, "staging"), id)
                        .deleteRecursively()
                }
            }
        }
        if (job == null) delete() else job.invokeOnCompletion { delete() }
    }

    private suspend fun restoreAndPersist() {
        val dao = runCatching { GlintDatabase.get(appContext).downloads() }.getOrNull() ?: return
        val stored = runCatching { dao.all() }.getOrDefault(emptyList())
        val restored =
            stored.mapNotNull { it.toItemOrNull() }.map { item ->
                when {
                    item.isActive -> item.copy(status = DownloadItem.Status.Interrupted, speed = "", etaSeconds = -1)
                    item.hasFile -> item.copy(fileMissing = !Storage.exists(appContext, item))
                    else -> item
                }
            }
        synchronized(attemptLock) {
            mutableItems.update { current ->
                // Downloads added while the list was loading stay; nothing is listed twice.
                val known = current.mapTo(HashSet()) { it.id }
                (current + restored.filter { it.id !in known }).sortedByDescending { it.createdAt }
            }
        }
        pruneWork()

        // Write only what changed. Progress ticks map to equal rows and are skipped.
        var persisted = stored.associateBy { it.id }
        items
            .map { list -> list.map { it.toEntity() } }
            .distinctUntilChanged()
            .conflate()
            .collect { entities ->
                val next = entities.associateBy { it.id }
                val changed = entities.filter { persisted[it.id] != it }
                val removed = persisted.keys.filterNot { it in next }
                try {
                    if (changed.isNotEmpty()) dao.upsert(changed)
                    removed.chunked(500).forEach { dao.delete(it) }
                    persisted = next
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Downloads carry on; the next change retries the write.
                }
            }
    }

    /** Deletes pieces left by downloads that can no longer continue, including older versions' folders. */
    private fun pruneWork() {
        val keep = synchronized(attemptLock) {
            mutableItems.value.filter { it.isActive || it.canResume }.mapTo(HashSet()) { it.id }
        }
        File(appContext.cacheDir, "download-attempts").deleteRecursively()
        File(appContext.cacheDir, "downloads").listFiles()?.filter { it.name !in keep }?.forEach { it.deleteRecursively() }
        (appContext.getExternalFilesDir("staging"))?.listFiles()?.filter { it.name !in keep }?.forEach {
            it.deleteRecursively()
        }
    }

    private fun stop(attempt: Attempt?) {
        val job = synchronized(attemptLock) { attempt?.job }
        job?.cancel()
        if (attempt != null) {
            val processId = attempt.token
            scope.launch {
                // The wrapper runs a shell and waits for it. Keep that work off the UI thread.
                // A unique process ID also makes delayed cleanup safe after a retry.
                runCatching { YoutubeDL.destroyProcessById(processId) }
            }
        }
    }

    private fun update(attempt: Attempt, transform: (DownloadItem) -> DownloadItem) {
        synchronized(attemptLock) {
            if (attempts[attempt.itemId] !== attempt) return
            mutableItems.update { list ->
                list.map { if (it.id == attempt.itemId) transform(it) else it }
            }
        }
    }

    /** Called with [attemptLock] held; install the job before it can begin or be canceled. */
    private fun launchLocked(item: DownloadItem) {
        val attempt = Attempt(item.id)
        // Taken here, in the order downloads are added, so they start in that order.
        val ticket = slots.ticket()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val engine = Engine.state.first { it is Engine.State.Ready || it is Engine.State.Failed }
                ensureCurrent(attempt)
                if (engine is Engine.State.Failed) {
                    fail(attempt, engine.message)
                    return@launch
                }
                slots.run(ticket) {
                    ensureCurrent(attempt)
                    // Links read in the new download sheet already know their details.
                    if (item.title.isBlank()) {
                        update(attempt) { it.copy(status = DownloadItem.Status.Preparing) }
                        try {
                            fetchInfo(item, attempt)
                        } catch (error: Exception) {
                            // A command can handle links the details step cannot, such as playlists.
                            if (item.customArgs == null || error is CancellationException) throw error
                        }
                        ensureCurrent(attempt)
                    }
                    download(current(attempt) ?: item, attempt)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: YoutubeDL.CanceledException) {
                update(attempt) { it.copy(status = DownloadItem.Status.Canceled) }
            } catch (error: Exception) {
                fail(attempt, error.message)
            } finally {
                synchronized(attemptLock) {
                    attempt.job = null
                }
            }
        }
        // Runs even when the job is canceled before it starts, so the line never stalls.
        job.invokeOnCompletion { slots.forget(ticket) }
        attempt.job = job
        attempts[item.id] = attempt
        job.start()
    }

    private suspend fun ensureCurrent(attempt: Attempt) {
        currentCoroutineContext().ensureActive()
        synchronized(attemptLock) {
            if (attempts[attempt.itemId] !== attempt) {
                throw CancellationException("Download attempt is no longer current")
            }
        }
    }

    private fun current(attempt: Attempt): DownloadItem? =
        synchronized(attemptLock) { mutableItems.value.find { it.id == attempt.itemId } }

    /** A failed download keeps its pieces: trying again continues from them. */
    private fun fail(attempt: Attempt, message: String?) {
        update(attempt) {
            it.copy(
                status = DownloadItem.Status.Failed,
                speed = "",
                error = ErrorText.summarize(message),
                errorDetail = message?.trim()?.takeLast(MaxErrorDetail)?.takeIf { detail -> detail.isNotEmpty() },
            )
        }
        current(attempt)?.takeIf { it.status == DownloadItem.Status.Failed }?.let {
            FinishedNotifications.failed(appContext, it)
        }
    }

    private suspend fun fetchInfo(item: DownloadItem, attempt: Attempt) {
        // The wrapper's getInfo has no process ID. Interrupting its blocking wait cancels it too.
        val info = CookieStore.withRunCookies { cookies ->
            runInterruptible {
                YoutubeDL.getInfo(
                    YoutubeDLRequest(item.url).apply {
                        addOption("--no-playlist")
                        addOption("-R", "1")
                        addOption("--socket-timeout", "10")
                        addCommands(DownloadArgs.network(SettingsStore.current, cookies))
                    })
            }
        }
        ensureCurrent(attempt)
        update(attempt) {
            it.copy(
                title = info.title ?: info.fulltitle ?: it.url,
                thumbnail = info.thumbnail,
                source = info.extractorKey ?: info.extractor ?: "",
                durationSeconds = info.duration,
                sizeBytes = info.fileSize.takeIf { size -> size > 0 } ?: info.fileSizeApproximate,
            )
        }
    }

    private suspend fun download(item: DownloadItem, attempt: Attempt) {
        val settings = SettingsStore.current
        val target = Storage.target(appContext, item, settings)
        val dir = target.home
        check(dir.mkdirs() || dir.isDirectory) { "Unable to create the download folder" }
        val workDirectory = workDirectory(item.id)
        val temporaryDirectory = File(workDirectory, "media")
        check(temporaryDirectory.mkdirs() || temporaryDirectory.isDirectory) {
            "Unable to create the download's temporary directory"
        }
        val marker = File(workDirectory, "completed-path.txt")
        marker.delete()
        ensureCurrent(attempt)
        update(attempt) { it.copy(status = DownloadItem.Status.Downloading) }

        // yt-dlp gets its own copy of the cookies; see CookieStore.withRunCookies.
        CookieStore.withRunCookies { cookies ->
            val request =
                YoutubeDLRequest(item.url).apply {
                    // Every option goes in as written: some take two values, which addOption cannot.
                    addCommands(
                        DownloadArgs.download(
                            item = item,
                            settings = settings,
                            homeDirectory = dir.absolutePath,
                            temporaryDirectory = temporaryDirectory.absolutePath,
                            markerPath = marker.absolutePath,
                            // The same name on every attempt lets yt-dlp continue its partial files.
                            outputTemplate = DownloadOutput.template(item.id, item.title.takeIf { item.isClip }),
                            cookiesPath = cookies,
                            deviceLanguage = Locale.getDefault().language,
                        ))
                }

            runInterruptible {
                YoutubeDL.execute(request, attempt.token) { progress, eta, line ->
                    // yt-dlp can emit bursts of output; labels need at most four refreshes a second.
                    if (attempt.progressUpdates.shouldPublish(SystemClock.elapsedRealtime())) {
                        update(attempt) {
                            it.copy(
                                status = DownloadItem.Status.Downloading,
                                progress = if (progress >= 0f) (progress / 100f).coerceIn(0f, 1f) else it.progress,
                                etaSeconds = eta,
                                speed = SpeedRegex.find(line)?.groupValues?.get(1) ?: it.speed,
                            )
                        }
                    }
                }
            }
        }
        ensureCurrent(attempt)

        // A command may save several files, anywhere, or none; Glint's own downloads save one.
        val reported =
            if (item.customArgs != null) DownloadOutput.reportedFiles(marker)
            else listOf(DownloadOutput.completedFile(marker, dir))
        val sizeBytes = reported.sumOf(File::length)

        val saved: List<Saved> =
            if (target.tree != null) {
                update(attempt) { it.copy(status = DownloadItem.Status.Saving, speed = "", etaSeconds = -1) }
                val failures = mutableListOf<String>()
                // Each file with its staged original, which goes only once the whole set is saved.
                val copied: List<Pair<Saved, File?>> =
                    reported.map { file ->
                        // A file outside Glint's staging folder was placed by the command itself.
                        if (!file.startsWith(dir.canonicalFile)) return@map Saved(path = file, uri = null) to null
                        val name = DownloadOutput.finalName(file.name, item.id) { false }
                        try {
                            val document = Storage.copyToTree(appContext, target.tree, target.treeFolders, file, name)
                            Saved(path = null, uri = document) to file
                        } catch (error: IOException) {
                            failures += error.message ?: "Could not save \"$name\" to the chosen folder"
                            Saved(path = file, uri = null) to null
                        }
                    }
                if (failures.isNotEmpty()) {
                    // All or nothing: the copies already made are taken back and every staged file
                    // stays, so trying again saves the whole set once, never some files twice.
                    copied.forEach { (saved, _) -> saved.uri?.let { Storage.deleteDocument(appContext, it) } }
                    throw IOException(failures.joinToString("\n"))
                }
                copied.forEach { (_, staged) -> staged?.delete() }
                copied.map { it.first }
            } else {
                reported.map { Saved(path = DownloadOutput.moveToFinalName(file = it, itemId = item.id), uri = null) }
            }
        ensureCurrent(attempt)
        val last = saved.lastOrNull()

        update(attempt) {
            it.copy(
                status = DownloadItem.Status.Completed,
                progress = 1f,
                speed = "",
                filePath = last?.path?.absolutePath,
                fileUri = last?.uri?.toString(),
                sizeBytes = if (reported.isEmpty()) it.sizeBytes else sizeBytes,
                formatLabel = DownloadOutput.withFileCount(it.formatLabel, saved.size),
                completedAt = System.currentTimeMillis(),
                fileMissing = false,
            )
        }
        workDirectory.deleteRecursively()
        if (target.tree != null) dir.deleteRecursively()

        val lastPath = last?.path
        if (lastPath == null) {
            current(attempt)?.let { FinishedNotifications.completed(appContext, it) }
            return
        }
        saved.dropLast(1).mapNotNull { it.path }.forEach {
            MediaScannerConnection.scanFile(appContext, arrayOf(it.absolutePath), null, null)
        }
        MediaScannerConnection.scanFile(appContext, arrayOf(lastPath.absolutePath), null) { _, uri ->
            if (uri != null) update(attempt) { it.copy(fileUri = uri.toString()) }
            current(attempt)?.takeIf { it.status == DownloadItem.Status.Completed }?.let {
                FinishedNotifications.completed(appContext, it)
            }
        }
    }

    private class Saved(val path: File?, val uri: Uri?)
}
