package com.appsense.app

import kotlin.math.max
import kotlin.math.min

enum class IntentionKind {
    BORED,
    QUICK
}

data class IntentionSchedule(
    val firstMinutes: Int,
    val repeatMinutes: Int
)

object IntentionDefaults {

    val BORED = IntentionSchedule(10, 5)

    val QUICK = IntentionSchedule(2, 2)
}

data class EffectiveReminderSchedule(
    val firstReminderMs: Long,
    val repeatReminderMs: Long
)

object IntentionScheduler {

    private const val MILLIS_PER_MINUTE = 60_000L

    fun effective(
        globalFirstMs: Long,
        globalRepeatMs: Long,
        override: IntentionSchedule?,
        selectedAtElapsedMs: Long
    ): EffectiveReminderSchedule {

        if (override == null) {
            return EffectiveReminderSchedule(
                globalFirstMs,
                globalRepeatMs
            )
        }

        val selectedTime = max(selectedAtElapsedMs, 0L)

        val firstMinutes = max(override.firstMinutes, 1).toLong()
        val repeatMinutes = max(override.repeatMinutes, 1).toLong()

        val intentionFirstDuration =
            firstMinutes * MILLIS_PER_MINUTE

        val intentionRepeatMs =
            repeatMinutes * MILLIS_PER_MINUTE

        // Saturating addition prevents Long overflow.
        val intentionFirstMs =
            if (selectedTime > Long.MAX_VALUE - intentionFirstDuration) {
                Long.MAX_VALUE
            } else {
                selectedTime + intentionFirstDuration
            }

        return EffectiveReminderSchedule(
            firstReminderMs = min(
                globalFirstMs,
                intentionFirstMs
            ),
            repeatReminderMs = min(
                globalRepeatMs,
                intentionRepeatMs
            )
        )
    }
}