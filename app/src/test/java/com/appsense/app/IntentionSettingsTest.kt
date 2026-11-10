package com.appsense.app

import org.junit.Assert.assertEquals
import org.junit.Test

class IntentionSettingsTest {

    @Test
    fun defaultsRemainUnchanged() {
        val result = IntentionSettings().normalized()

        assertEquals(IntentionSettings(), result)
        assertEquals(false, result.enabled)
        assertEquals(IntentionDefaults.BORED, result.bored)
        assertEquals(IntentionDefaults.QUICK, result.quick)
        assertEquals(5, result.pauseSeconds)
    }

    @Test
    fun valuesBelowMinimumAreClamped() {
        val settings = IntentionSettings(
            bored = IntentionSchedule(0, -5),
            quick = IntentionSchedule(-10, 0),
            pauseSeconds = 0
        )

        val result = settings.normalized()

        assertEquals(IntentionSchedule(1, 1), result.bored)
        assertEquals(IntentionSchedule(1, 1), result.quick)
        assertEquals(3, result.pauseSeconds)
    }

    @Test
    fun valuesAboveMaximumAreClamped() {
        val settings = IntentionSettings(
            bored = IntentionSchedule(121, 200),
            quick = IntentionSchedule(150, 999),
            pauseSeconds = 20
        )

        val result = settings.normalized()

        assertEquals(IntentionSchedule(120, 120), result.bored)
        assertEquals(IntentionSchedule(120, 120), result.quick)
        assertEquals(10, result.pauseSeconds)
    }

    @Test
    fun validValuesRemainUnchanged() {
        val settings = IntentionSettings(
            enabled = true,
            bored = IntentionSchedule(10, 5),
            quick = IntentionSchedule(2, 2),
            pauseSeconds = 7
        )

        assertEquals(settings, settings.normalized())
    }

    @Test
    fun boundaryValuesRemainUnchanged() {
        val settings = IntentionSettings(
            enabled = true,
            bored = IntentionSchedule(1, 120),
            quick = IntentionSchedule(120, 1),
            pauseSeconds = 3
        )

        assertEquals(settings, settings.normalized())

        val maximumPause = settings.copy(pauseSeconds = 10)

        assertEquals(maximumPause, maximumPause.normalized())
    }

    @Test
    fun normalizationDoesNotChangeOriginalSettings() {
        val original = IntentionSettings(
            bored = IntentionSchedule(0, 150),
            quick = IntentionSchedule(-1, 200),
            pauseSeconds = 50
        )

        original.normalized()

        assertEquals(0, original.bored.firstMinutes)
        assertEquals(150, original.bored.repeatMinutes)
        assertEquals(-1, original.quick.firstMinutes)
        assertEquals(200, original.quick.repeatMinutes)
        assertEquals(50, original.pauseSeconds)
    }
}