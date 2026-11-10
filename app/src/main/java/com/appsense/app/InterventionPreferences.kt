package com.appsense.app

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val APPSENSE_PREFERENCES = "appsense_preferences"

/** Settings and per-day intervention metrics shared by the UI and monitor service. */
data class InterventionSettings(
    val interventionEnabled: Boolean = true,
    val firstReminderMinutes: Int = 15,
    val repeatReminderMinutes: Int = 10,
    val breathingEnabled: Boolean = true,
    val gameEnabled: Boolean = true
)

data class InterventionDailyLog(
    val shown: Int = 0,
    val completed: Int = 0,
    val dismissed: Int = 0
)

object InterventionPreferences {
    const val KEY_INTERVENTION_ENABLED = "intervention_enabled"
    const val KEY_FIRST_REMINDER_MINUTES = "first_reminder_minutes"
    const val KEY_REPEAT_REMINDER_MINUTES = "repeat_reminder_minutes"
    const val KEY_BREATHING_ENABLED = "breathing_enabled"
    const val KEY_GAME_ENABLED = "game_enabled"

    fun read(context: Context): InterventionSettings {
        val prefs = context.getSharedPreferences(APPSENSE_PREFERENCES, Context.MODE_PRIVATE)
        return InterventionSettings(
            interventionEnabled = prefs.getBoolean(KEY_INTERVENTION_ENABLED, true),
            firstReminderMinutes = prefs.getInt(KEY_FIRST_REMINDER_MINUTES, 15).coerceIn(1, 120),
            repeatReminderMinutes = prefs.getInt(KEY_REPEAT_REMINDER_MINUTES, 10).coerceIn(1, 120),
            breathingEnabled = prefs.getBoolean(KEY_BREATHING_ENABLED, true),
            gameEnabled = prefs.getBoolean(KEY_GAME_ENABLED, true)
        )
    }

    fun write(context: Context, settings: InterventionSettings) {
        context.getSharedPreferences(APPSENSE_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_INTERVENTION_ENABLED, settings.interventionEnabled)
            .putInt(KEY_FIRST_REMINDER_MINUTES, settings.firstReminderMinutes.coerceIn(1, 120))
            .putInt(KEY_REPEAT_REMINDER_MINUTES, settings.repeatReminderMinutes.coerceIn(1, 120))
            .putBoolean(KEY_BREATHING_ENABLED, settings.breathingEnabled)
            .putBoolean(KEY_GAME_ENABLED, settings.gameEnabled)
            .apply()
    }
}

object InterventionDailyLogStore {
    private fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun today(context: Context): InterventionDailyLog = read(context, todayKey())

    fun recordShown(context: Context) = increment(context, "shown")
    fun recordCompleted(context: Context) = increment(context, "completed")
    fun recordDismissed(context: Context) = increment(context, "dismissed")

    private fun read(context: Context, key: String): InterventionDailyLog {
        val raw = context.getSharedPreferences(APPSENSE_PREFERENCES, Context.MODE_PRIVATE)
            .getString(key, null) ?: return InterventionDailyLog()
        return try {
            val json = JSONObject(raw)
            InterventionDailyLog(
                shown = json.optInt("shown", 0),
                completed = json.optInt("completed", 0),
                dismissed = json.optInt("dismissed", 0)
            )
        } catch (_: Exception) {
            InterventionDailyLog()
        }
    }

    private fun increment(context: Context, field: String) {
        val prefs = context.getSharedPreferences(APPSENSE_PREFERENCES, Context.MODE_PRIVATE)
        val key = todayKey()
        val current = try {
            JSONObject(prefs.getString(key, "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        current.put(field, current.optInt(field, 0) + 1)
        // The yyyy-MM-dd date itself is the preference key, as requested.
        prefs.edit().putString(key, current.toString()).apply()
    }
}
