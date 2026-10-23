package app.glint.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * Lets a limited number of downloads run at once, strictly in the order they were queued. The
 * limit can change at any time: raising it starts waiting downloads, lowering it lets running
 * ones finish.
 */
internal class DownloadSlots(limit: Int) {
    data class State(val limit: Int, val running: Int, val waiting: List<Long>)

    private val mutableState = MutableStateFlow(State(limit.coerceAtLeast(1), 0, emptyList()))
    val state: StateFlow<State> = mutableState.asStateFlow()
    private var nextTicket = 0L

    /** A place in line. Every ticket must reach [run] or [forget]. */
    fun ticket(): Long =
        synchronized(this) {
            val ticket = nextTicket++
            mutableState.update { it.copy(waiting = it.waiting + ticket) }
            ticket
        }

    fun setLimit(limit: Int) = mutableState.update { it.copy(limit = limit.coerceAtLeast(1)) }

    /** Leaves the line without running, for example when a download is canceled while waiting. */
    fun forget(ticket: Long) = mutableState.update { it.copy(waiting = it.waiting - ticket) }

    suspend fun <T> run(ticket: Long, block: suspend () -> T): T {
        try {
            while (true) {
                state.first { it.canStart(ticket) }
                var started = false
                mutableState.update {
                    started = it.canStart(ticket)
                    if (started) it.copy(running = it.running + 1, waiting = it.waiting - ticket) else it
                }
                if (started) break
            }
        } catch (error: Throwable) {
            forget(ticket)
            throw error
        }
        try {
            return block()
        } finally {
            mutableState.update { it.copy(running = it.running - 1) }
        }
    }

    private fun State.canStart(ticket: Long) = running < limit && waiting.firstOrNull() == ticket
}
