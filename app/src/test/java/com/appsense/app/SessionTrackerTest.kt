package com.appsense.app
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

class SessionTrackerTest {

    @Test
    fun switchingBetweenTrackedAppsKeepsTheSameSession() {
        val tracker = SessionTracker()

        assertEquals(0L, tracker.onTick(1_000L, true, true))
        assertTrue(tracker.newSessionStarted)

        assertEquals(12_000L, tracker.onTick(13_000L, true, true))
        assertFalse(tracker.newSessionStarted)
        assertFalse(tracker.sessionEnded)
    }

    @Test
    fun tenSecondWhatsAppCheckInsideGraceKeepsTheSession() {
        val tracker = SessionTracker()

        tracker.onTick(0L, true, true)
        assertEquals(5_000L, tracker.onTick(5_000L, false, true))
        assertFalse(tracker.sessionEnded)

        assertEquals(15_000L, tracker.onTick(15_000L, true, true))
        assertFalse(tracker.newSessionStarted)
        assertFalse(tracker.sessionEnded)
    }

    @Test
    fun sessionEndsWhenGracePeriodExpires() {
        val tracker = SessionTracker()

        tracker.onTick(0L, true, true)
        assertEquals(1_000L, tracker.onTick(1_000L, false, true))
        assertFalse(tracker.sessionEnded)

        assertEquals(
            SessionTracker.GRACE_MS - 1L,
            tracker.onTick(SessionTracker.GRACE_MS - 1L, false, true)
        )
        assertFalse(tracker.sessionEnded)

        assertEquals(0L, tracker.onTick(SessionTracker.GRACE_MS, false, true))
        assertTrue(tracker.sessionEnded)
    }

    @Test
    fun screenOffEndsSessionImmediately() {
        val tracker = SessionTracker()

        tracker.onTick(1_000L, true, true)
        assertEquals(0L, tracker.onTick(11_000L, false, false))
        assertTrue(tracker.sessionEnded)

        assertEquals(0L, tracker.onTick(12_000L, true, true))
        assertTrue(tracker.newSessionStarted)
        assertFalse(tracker.sessionEnded)
    }

    @Test
    fun longGapBetweenTicksExpiresSessionOnNextUntrackedTick() {
        val tracker = SessionTracker()

        tracker.onTick(2_000L, true, true)

        assertEquals(0L, tracker.onTick(62_000L, false, true))
        assertTrue(tracker.sessionEnded)
    }

    @Test
    fun resumedTrackedAppAfterShortGapKeepsOriginalSessionClock() {

        val tracker = SessionTracker()

        assertEquals(
            0L,
            tracker.onTick(1_000L, true, true)
        )

        // Temporary untracked app.
        assertEquals(
            5_000L,
            tracker.onTick(6_000L, false, true)
        )

        // Another tracked app becomes foreground.
        assertEquals(
            12_000L,
            tracker.onTick(13_000L, true, true)
        )

        assertFalse(tracker.newSessionStarted)
        assertFalse(tracker.sessionEnded)
    }
}