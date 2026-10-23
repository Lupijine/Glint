package app.glint.engine

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The yt-dlp engine: Python, yt-dlp and ffmpeg, unpacked on first launch. Sites such as YouTube
 * change often and reject old yt-dlp releases, so by default yt-dlp is updated on every launch
 * before downloads start.
 */
object Engine {
    sealed interface State {
        data object Starting : State

        data object Updating : State

        data object Ready : State

        data class Failed(val message: String) : State
    }

    /** What the last update check found. */
    enum class UpdateResult {
        Updated,
        UpToDate,
        Failed,
        /** Updating replaces yt-dlp, which running downloads are still using. */
        Busy,
    }

    private val mutableState = MutableStateFlow<State>(State.Starting)
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val mutableVersion = MutableStateFlow<String?>(null)
    /** The installed yt-dlp version, once known. */
    val version: StateFlow<String?> = mutableVersion.asStateFlow()

    private val mutableLastUpdate = MutableStateFlow<UpdateResult?>(null)
    val lastUpdate: StateFlow<UpdateResult?> = mutableLastUpdate.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updateLock = Mutex()
    private lateinit var appContext: Context

    fun initialize(context: Context) {
        appContext = context.applicationContext
        scope.launch {
            val started = runCatching {
                YoutubeDL.init(appContext)
                FFmpeg.init(appContext)
            }
            started.exceptionOrNull()?.let {
                mutableState.value = State.Failed(ErrorText.describe(it))
                return@launch
            }
            if (SettingsStore.current.updateOnLaunch) {
                mutableState.value = State.Updating
                // An update failure (for example when offline) keeps the installed yt-dlp.
                updateLocked()
            }
            mutableVersion.value = runCatching { YoutubeDL.versionName(appContext) }.getOrNull()
            mutableState.value = State.Ready
        }
    }

    /** Updates yt-dlp now, from the channel chosen in settings. */
    fun updateNow() {
        if (state.value != State.Ready) return
        if (DownloadManager.items.value.any { it.isActive }) {
            mutableLastUpdate.value = UpdateResult.Busy
            return
        }
        scope.launch {
            mutableState.value = State.Updating
            updateLocked()
            mutableVersion.value = runCatching { YoutubeDL.versionName(appContext) }.getOrNull()
            mutableState.value = State.Ready
        }
    }

    private suspend fun updateLocked() {
        updateLock.withLock {
            val channel =
                when (SettingsStore.current.updateChannel) {
                    UpdateChannel.Stable -> YoutubeDL.UpdateChannel.STABLE
                    UpdateChannel.Nightly -> YoutubeDL.UpdateChannel.NIGHTLY
                }
            mutableLastUpdate.value =
                runCatching { YoutubeDL.updateYoutubeDL(appContext, channel) }
                    .fold(
                        onSuccess = {
                            if (it == YoutubeDL.UpdateStatus.DONE) UpdateResult.Updated else UpdateResult.UpToDate
                        },
                        onFailure = { UpdateResult.Failed },
                    )
        }
    }
}
