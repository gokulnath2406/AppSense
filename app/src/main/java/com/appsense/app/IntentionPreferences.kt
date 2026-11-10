package com.appsense.app

import android.content.Context

private const val APPSENSE_PREFERENCES = "appsense_preferences"

data class IntentionSettings(
    val enabled: Boolean = false,
    val bored: IntentionSchedule = IntentionDefaults.BORED,
    val quick: IntentionSchedule = IntentionDefaults.QUICK,
    val pauseSeconds: Int = 5
) {
    fun normalized(): IntentionSettings {
        return copy(
            bored = bored.copy(
                firstMinutes = bored.firstMinutes.coerceIn(1, 120),
                repeatMinutes = bored.repeatMinutes.coerceIn(1, 120)
            ),
            quick = quick.copy(
                firstMinutes = quick.firstMinutes.coerceIn(1, 120),
                repeatMinutes = quick.repeatMinutes.coerceIn(1, 120)
            ),
            pauseSeconds = pauseSeconds.coerceIn(3, 10)
        )
    }
}

object IntentionPreferences {

    private const val KEY_ENABLED = "intention_enabled"
    private const val KEY_BORED_FIRST = "intention_bored_first"
    private const val KEY_BORED_REPEAT = "intention_bored_repeat"
    private const val KEY_QUICK_FIRST = "intention_quick_first"
    private const val KEY_QUICK_REPEAT = "intention_quick_repeat"
    private const val KEY_PAUSE_SECONDS = "intention_pause_seconds"

    fun read(context: Context): IntentionSettings {
        return try {
            val prefs = context.getSharedPreferences(
                APPSENSE_PREFERENCES,
                Context.MODE_PRIVATE
            )

            IntentionSettings(
                enabled = prefs.getBoolean(KEY_ENABLED, false),
                bored = IntentionSchedule(
                    firstMinutes = prefs.getInt(KEY_BORED_FIRST, 10),
                    repeatMinutes = prefs.getInt(KEY_BORED_REPEAT, 5)
                ),
                quick = IntentionSchedule(
                    firstMinutes = prefs.getInt(KEY_QUICK_FIRST, 2),
                    repeatMinutes = prefs.getInt(KEY_QUICK_REPEAT, 2)
                ),
                pauseSeconds = prefs.getInt(KEY_PAUSE_SECONDS, 5)
            ).normalized()

        } catch (_: Exception) {
            IntentionSettings()
        }
    }

    fun write(context: Context, settings: IntentionSettings) {
        val normalized = settings.normalized()

        context.getSharedPreferences(
            APPSENSE_PREFERENCES,
            Context.MODE_PRIVATE
        )
            .edit()
            .putBoolean(KEY_ENABLED, normalized.enabled)
            .putInt(KEY_BORED_FIRST, normalized.bored.firstMinutes)
            .putInt(KEY_BORED_REPEAT, normalized.bored.repeatMinutes)
            .putInt(KEY_QUICK_FIRST, normalized.quick.firstMinutes)
            .putInt(KEY_QUICK_REPEAT, normalized.quick.repeatMinutes)
            .putInt(KEY_PAUSE_SECONDS, normalized.pauseSeconds)
            .apply()
    }
}