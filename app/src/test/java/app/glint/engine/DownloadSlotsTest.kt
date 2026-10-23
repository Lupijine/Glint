package app.glint.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadSlotsTest {
    @Test
    fun `downloads start in the order they were queued, a limited number at a time`() = runBlocking {
        val slots = DownloadSlots(limit = 2)
        val started = mutableListOf<Int>()
        val release = List(4) { CompletableDeferred<Unit>() }
        val tickets = List(4) { slots.ticket() }

        // Start the coroutines in reverse to show that tickets, not launch order, decide.
        val jobs = (3 downTo 0).map { i ->
            launch {
                slots.run(tickets[i]) {
                    synchronized(started) { started += i }
                    release[i].await()
                }
            }
        }
        withTimeout(5_000) { slots.state.first { it.running == 2 } }
        assertEquals(listOf(0, 1), synchronized(started) { started.toList() })

        release[1].complete(Unit)
        withTimeout(5_000) { slots.state.first { it.running == 2 && it.waiting.size == 1 } }
        assertEquals(listOf(0, 1, 2), synchronized(started) { started.toList() })

        release.forEach { it.complete(Unit) }
        jobs.forEach { it.join() }
        assertEquals(listOf(0, 1, 2, 3), started)
        assertEquals(0, slots.state.value.running)
    }

    @Test
    fun `raising the limit starts waiting downloads`() = runBlocking {
        val slots = DownloadSlots(limit = 1)
        val gate = CompletableDeferred<Unit>()
        val first = slots.ticket()
        val second = slots.ticket()
        val a = async { slots.run(first) { gate.await() } }
        val b = async { slots.run(second) { gate.await() } }
        withTimeout(5_000) { slots.state.first { it.running == 1 } }

        slots.setLimit(2)

        withTimeout(5_000) { slots.state.first { it.running == 2 } }
        gate.complete(Unit)
        a.await()
        b.await()
    }

    @Test
    fun `a forgotten ticket does not hold up the line`() = runBlocking {
        val slots = DownloadSlots(limit = 1)
        val abandoned = slots.ticket()
        val next = slots.ticket()

        slots.forget(abandoned)

        withTimeout(5_000) { assertEquals(7, slots.run(next) { 7 }) }
    }

    @Test
    fun `canceling a waiting download frees its place`() = runBlocking {
        val slots = DownloadSlots(limit = 1)
        val gate = CompletableDeferred<Unit>()
        val runningTicket = slots.ticket()
        val running = launch { slots.run(runningTicket) { gate.await() } }
        val waitingTicket = slots.ticket()
        val waiting = launch { slots.run(waitingTicket) {} }
        val last = slots.ticket()
        withTimeout(5_000) { slots.state.first { it.running == 1 } }
        yield()

        waiting.cancel()
        waiting.join()
        gate.complete(Unit)
        running.join()

        withTimeout(5_000) { assertEquals("done", slots.run(last) { "done" }) }
    }
}
