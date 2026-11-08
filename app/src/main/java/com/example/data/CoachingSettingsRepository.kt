package com.example.data

import android.content.Context
import com.example.ml.Coaching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Per-child coaching settings (prompt mode, staircase step) in SharedPreferences. */
class CoachingSettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("coaching", Context.MODE_PRIVATE)
    private val flows = mutableMapOf<String, MutableStateFlow<Coaching.Settings>>()

    private fun read(childId: String): Coaching.Settings {
        val defaults = Coaching.Settings()
        val mode = prefs.getString("$childId.mode", null)
            ?.let { runCatching { Coaching.Mode.valueOf(it) }.getOrNull() } ?: defaults.mode
        return defaults.copy(
            mode = mode,
            stepMin = prefs.getInt("$childId.stepMin", defaults.stepMin),
            startHoldMin = prefs.getInt("$childId.startHoldMin", defaults.startHoldMin)
        )
    }

    @Synchronized
    fun settings(childId: String): StateFlow<Coaching.Settings> =
        flows.getOrPut(childId) { MutableStateFlow(read(childId)) }

    @Synchronized
    fun update(childId: String, settings: Coaching.Settings) {
        prefs.edit()
            .putString("$childId.mode", settings.mode.name)
            .putInt("$childId.stepMin", settings.stepMin)
            .putInt("$childId.startHoldMin", settings.startHoldMin)
            .apply()
        flows.getOrPut(childId) { MutableStateFlow(settings) }.value = settings
    }
}
