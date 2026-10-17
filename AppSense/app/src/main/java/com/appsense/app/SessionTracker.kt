package com.appsense.app

/**
 * Tracks one continuous session across any tracked app.
 *
 * A short period without a tracked app does not end the session. The session
 * ends after [GRACE_MS] without a tracked foreground tick, or immediately
 * when the screen turns off.
 */
class SessionTracker(
    private val graceMs: Long = GRACE_MS
) {
    companion object {
        const val GRACE_MS = 45_000L
    }

    private var sessionStartMs: Long? = null
    private var lastTrackedForegroundMs: Long? = null

    var newSessionStarted: Boolean = false
        private set

    var sessionEnded: Boolean = false
        private set

    /**
     * Returns the current session elapsed time in milliseconds, or 0 when
     * no session is active. [newSessionStarted] and [sessionEnded] describe
     * the transition caused by this tick.
     */
    fun onTick(
        nowMonotonicMs: Long,
        trackedAppInForeground: Boolean,
        screenOn: Boolean
    ): Long {
        newSessionStarted = false
        sessionEnded = false

        if (!screenOn) {
            sessionEnded = sessionStartMs != null
            clearSession()
            return 0L
        }

        if (trackedAppInForeground) {
            if (sessionStartMs == null) {
                sessionStartMs = nowMonotonicMs
                newSessionStarted = true
            }
            lastTrackedForegroundMs = nowMonotonicMs
            return (nowMonotonicMs - (sessionStartMs ?: nowMonotonicMs))
                .coerceAtLeast(0L)
        }

        val startMs = sessionStartMs ?: return 0L
        val lastTrackedMs = lastTrackedForegroundMs ?: nowMonotonicMs

        if (nowMonotonicMs - lastTrackedMs >= graceMs) {
            sessionEnded = true
            clearSession()
            return 0L
        }

        return (nowMonotonicMs - startMs).coerceAtLeast(0L)
    }

    private fun clearSession() {
        sessionStartMs = null
        lastTrackedForegroundMs = null
    }
}
