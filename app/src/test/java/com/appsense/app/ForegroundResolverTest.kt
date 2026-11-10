package com.appsense.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundResolverTest {

    private fun event(
        packageName: String?,
        className: String?,
        type: ForegroundEvent.Type,
        timestamp: Long
    ) = ForegroundEvent(
        packageName = packageName,
        className = className,
        type = type,
        timestamp = timestamp
    )

    @Test
    fun resumedEventSetsForegroundState() {

        val result = ForegroundResolver.resolve(
            events = listOf(
                event("app.A", "ActivityA", ForegroundEvent.Type.FOREGROUND, 100L)
            ),
            previous = ForegroundState(null, null)
        )

        assertEquals(
            ForegroundState("app.A", "ActivityA"),
            result
        )
    }

    @Test
    fun pausedThenResumedDifferentActivityKeepsNewActivity() {

        val result = ForegroundResolver.resolve(
            events = listOf(
                event("app.A", "ActivityA", ForegroundEvent.Type.PAUSED, 100L),
                event("app.A", "ActivityB", ForegroundEvent.Type.FOREGROUND, 101L)
            ),
            previous = ForegroundState("app.A", "ActivityA")
        )

        assertEquals(
            ForegroundState("app.A", "ActivityB"),
            result
        )
    }

    @Test
    fun overlappingActivitiesDoNotClearNewerActivity() {

        val result = ForegroundResolver.resolve(
            events = listOf(
                event("app.A", "ActivityA", ForegroundEvent.Type.FOREGROUND, 100L),
                event("app.A", "ActivityB", ForegroundEvent.Type.FOREGROUND, 101L),
                event("app.A", "ActivityA", ForegroundEvent.Type.PAUSED, 102L)
            ),
            previous = ForegroundState(null, null)
        )

        assertEquals(
            ForegroundState("app.A", "ActivityB"),
            result
        )
    }

    @Test
    fun pausedDifferentClassIsIgnored() {

        val result = ForegroundResolver.resolve(
            events = listOf(
                event("app.A", "OldActivity", ForegroundEvent.Type.PAUSED, 200L)
            ),
            previous = ForegroundState("app.A", "NewActivity")
        )

        assertEquals(
            ForegroundState("app.A", "NewActivity"),
            result
        )
    }

    @Test
    fun pausedWithoutResumedClearsPreviousState() {

        val result = ForegroundResolver.resolve(
            events = listOf(
                event("app.A", "ActivityA", ForegroundEvent.Type.PAUSED, 200L)
            ),
            previous = ForegroundState("app.A", "ActivityA")
        )

        assertEquals(
            ForegroundState(null, null),
            result
        )
    }

    @Test
    fun systemStopClearsForegroundState() {

        val result = ForegroundResolver.resolve(
            events = listOf(
                event("android", null, ForegroundEvent.Type.SYSTEM_STOP, 200L)
            ),
            previous = ForegroundState("app.A", "ActivityA")
        )

        assertEquals(
            ForegroundState(null, null),
            result
        )
    }

    @Test
    fun pausedEventFromAnotherPackageDoesNotClearLatest() {

        val result = ForegroundResolver.resolve(
            events = listOf(
                event("app.B", "ActivityB", ForegroundEvent.Type.PAUSED, 200L)
            ),
            previous = ForegroundState("app.A", "ActivityA")
        )

        assertEquals(
            ForegroundState("app.A", "ActivityA"),
            result
        )
    }

    @Test
    fun equalTimestampsPreserveGivenEventOrder() {

        val result = ForegroundResolver.resolve(
            events = listOf(
                event("app.A", "ActivityA", ForegroundEvent.Type.FOREGROUND, 100L),
                event("app.B", "ActivityB", ForegroundEvent.Type.FOREGROUND, 100L)
            ),
            previous = ForegroundState(null, null)
        )

        assertEquals(
            ForegroundState("app.B", "ActivityB"),
            result
        )
    }

    @Test
    fun previousStateIsKeptWhenThereAreNoEvents() {

        val previous = ForegroundState("app.A", "ActivityA")

        val result = ForegroundResolver.resolve(
            events = emptyList(),
            previous = previous
        )

        assertEquals(previous, result)
    }
}