package com.appsense.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class InterventionDailyLogStoreTest {

    @Test
    fun removesOnlyKeysOlderThan30Days() {
        val today = LocalDate.of(2026, 11, 8)

        val keys = setOf(
            "2026-10-08", // Exactly 31 days old
            "2026-10-09", // Exactly 30 days old
            "2026-11-08", // Today
            "2026-11-09", // Future date
            "intervention_enabled",
            "first_reminder_minutes",
            "invalid-date"
        )

        val result = InterventionDailyLogStore.keysOlderThan(
            allKeys = keys,
            today = today
        )

        assertEquals(setOf("2026-10-08"), result)
    }

    @Test
    fun retainsAllKeysWhenNoneAreOlderThan30Days() {
        val today = LocalDate.of(2026, 11, 8)

        val keys = setOf(
            "2026-10-09",
            "2026-11-08",
            "intervention_enabled"
        )

        val result = InterventionDailyLogStore.keysOlderThan(
            allKeys = keys,
            today = today
        )

        assertEquals(emptySet<String>(), result)
    }
}