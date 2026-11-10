package com.appsense.app

import org.junit.Assert.assertEquals
import org.junit.Test

class IntentionSchedulerTest {

    @Test
    fun boredSchedule_returnsExpectedValues() {

        val result = IntentionScheduler.effective(
            900_000L,
            600_000L,
            IntentionDefaults.BORED,
            11_000L
        )

        assertEquals(
            EffectiveReminderSchedule(611_000L, 300_000L),
            result
        )
    }

    @Test
    fun quickSchedule_returnsExpectedValues() {

        val result = IntentionScheduler.effective(
            900_000L,
            600_000L,
            IntentionDefaults.QUICK,
            11_000L
        )

        assertEquals(
            EffectiveReminderSchedule(131_000L, 120_000L),
            result
        )
    }

    @Test
    fun intentionNeverLoosensGlobalSchedule() {

        val result = IntentionScheduler.effective(
            300_000L,
            180_000L,
            IntentionDefaults.BORED,
            11_000L
        )

        assertEquals(
            EffectiveReminderSchedule(300_000L, 180_000L),
            result
        )
    }

    @Test
    fun nullOverridePreservesGlobalSchedule() {

        val result = IntentionScheduler.effective(
            900_000L,
            600_000L,
            null,
            11_000L
        )

        assertEquals(
            EffectiveReminderSchedule(900_000L, 600_000L),
            result
        )
    }

    @Test
    fun nullOverridePreservesOtherGlobalValues() {

        val testCases = listOf(
            100L to 200L,
            0L to 0L,
            -100L to -200L,
            Long.MAX_VALUE to Long.MIN_VALUE,
            123_456L to 789_012L
        )

        testCases.forEach { (first, repeat) ->

            val result = IntentionScheduler.effective(
                first,
                repeat,
                null,
                50_000L
            )

            assertEquals(
                EffectiveReminderSchedule(first, repeat),
                result
            )
        }
    }

    @Test
    fun negativeSelectedTimeBehavesLikeZero() {

        val result = IntentionScheduler.effective(
            Long.MAX_VALUE,
            Long.MAX_VALUE,
            IntentionDefaults.BORED,
            -500L
        )

        assertEquals(
            EffectiveReminderSchedule(600_000L, 300_000L),
            result
        )
    }

    @Test
    fun zeroMinutesBehaveLikeOneMinute() {

        val result = IntentionScheduler.effective(
            Long.MAX_VALUE,
            Long.MAX_VALUE,
            IntentionSchedule(0, 0),
            10_000L
        )

        assertEquals(
            EffectiveReminderSchedule(70_000L, 60_000L),
            result
        )
    }

    @Test
    fun veryLargeValuesDoNotOverflow() {

        val result = IntentionScheduler.effective(
            Long.MAX_VALUE,
            Long.MAX_VALUE,
            IntentionSchedule(Int.MAX_VALUE, Int.MAX_VALUE),
            Long.MAX_VALUE
        )

        assertEquals(
            EffectiveReminderSchedule(
                Long.MAX_VALUE,
                Int.MAX_VALUE.toLong() * 60_000L
            ),
            result
        )
    }

    @Test
    fun largeSelectedTimeUsesSaturatingAddition() {

        val result = IntentionScheduler.effective(
            Long.MAX_VALUE,
            Long.MAX_VALUE,
            IntentionSchedule(10, 5),
            Long.MAX_VALUE - 100L
        )

        assertEquals(
            EffectiveReminderSchedule(
                Long.MAX_VALUE,
                300_000L
            ),
            result
        )
    }

    @Test
    fun customScheduleUsesMinimumOfGlobalAndIntention() {

        val result = IntentionScheduler.effective(
            800_000L,
            500_000L,
            IntentionSchedule(3, 4),
            20_000L
        )

        assertEquals(
            EffectiveReminderSchedule(200_000L, 240_000L),
            result
        )
    }
}