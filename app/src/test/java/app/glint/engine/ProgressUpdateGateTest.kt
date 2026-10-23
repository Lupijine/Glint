package app.glint.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressUpdateGateTest {
    @Test
    fun `first progress is immediate even at clock zero`() {
        assertTrue(ProgressUpdateGate().shouldPublish(0L))
    }

    @Test
    fun `bursts cannot publish again before 250 milliseconds`() {
        val gate = ProgressUpdateGate()
        assertTrue(gate.shouldPublish(1_000L))

        for (time in 1_000L..1_249L) assertFalse(gate.shouldPublish(time))
        assertTrue(gate.shouldPublish(1_250L))
    }

    @Test
    fun `suppressed callbacks do not postpone the next refresh`() {
        val gate = ProgressUpdateGate()
        assertTrue(gate.shouldPublish(0L))
        assertFalse(gate.shouldPublish(249L))
        assertTrue(gate.shouldPublish(250L))
        assertFalse(gate.shouldPublish(499L))
        assertTrue(gate.shouldPublish(500L))
    }

    @Test
    fun `idle time permits an immediate fresh update`() {
        val gate = ProgressUpdateGate()
        assertTrue(gate.shouldPublish(5_000L))
        assertTrue(gate.shouldPublish(50_000L))
        assertFalse(gate.shouldPublish(50_001L))
    }

    @Test
    fun `a retry or separate download has its own fresh gate`() {
        val previousAttempt = ProgressUpdateGate()
        val newAttempt = ProgressUpdateGate()
        assertTrue(previousAttempt.shouldPublish(1_000L))
        assertFalse(previousAttempt.shouldPublish(1_001L))
        assertTrue(newAttempt.shouldPublish(1_001L))
    }

    @Test
    fun `one thousand callbacks in one second publish only four updates`() {
        val gate = ProgressUpdateGate()
        val publishedAt = (0L until 1_000L).filter(gate::shouldPublish)

        assertEquals(listOf(0L, 250L, 500L, 750L), publishedAt)
    }
}
