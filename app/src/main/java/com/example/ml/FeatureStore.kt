package com.example.ml

import com.example.data.TrackerEvent
import java.util.Calendar
import java.util.Date

data class EventFeatures(
    val minutesAfterLastPee: Int,
    val timeOfDay: Int,
    val sleepState: Boolean,
    val fluidIntake: Int
)

object FeatureStore {
    fun generateFeatures(
        allEvents: List<TrackerEvent>,
        targetTimestamp: Long,
        sleepState: Boolean = false,
        fluidIntake: Int = 0
    ): EventFeatures {
        val date = Date(targetTimestamp)
        val calendar = Calendar.getInstance()
        calendar.time = date
        val timeOfDay = calendar.get(Calendar.HOUR_OF_DAY)

        val lastPee = allEvents.filter { it.eventType == "pee" && it.timestamp < targetTimestamp }.maxByOrNull { it.timestamp }
        val minutesAfterLastPee = if (lastPee != null) {
            ((targetTimestamp - lastPee.timestamp) / (1000 * 60)).toInt()
        } else {
            0
        }

        return EventFeatures(
            minutesAfterLastPee = minutesAfterLastPee,
            timeOfDay = timeOfDay,
            sleepState = sleepState,
            fluidIntake = fluidIntake
        )
    }
}
