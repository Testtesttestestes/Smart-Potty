package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "tracker_events")
data class TrackerEvent(
    @PrimaryKey
    val eventId: String = UUID.randomUUID().toString(),
    val childId: String,
    val timestamp: Long,
    val eventType: String, // see EventTypes: "pee", "sensation", "drink", "sleep", "wake"
    val volumeLevel: Int? = null, // Deprecated / legacy level (keeps volume level)
    val voidedVolumeMl: Int? = null, // Store voided volume in ml/grams
    val estimatedBladderCapacityMl: Int? = null, // Store estimated capacity of the bladder in ml
    val isAccident: Boolean = false,
    val sensationLevel: String? = null,
    val predictedProbability: Float? = null,
    val holdingBehavior: Boolean = false,
    val holdingLevel: Int? = null, // 0 = Нет, 1 = Легкий (ерзает), 2 = Явный (сжимает ножки), 3 = Критический (терпит из последних сил)
    val isStrictCommand: Boolean = false,
    val commandProbability: Float? = null,
    val commandResult: String? = null, // "success", "accident"
    val fluidIntakeMl: Int? = null, // For "drink" events: amount drunk in ml
    val modelVersion: Int? = null // Version of the prediction model that produced predictedProbability / commandProbability
)

object EventTypes {
    const val PEE = "pee"
    const val SENSATION = "sensation"
    const val DRINK = "drink"
    const val SLEEP = "sleep"
    const val WAKE = "wake"
}
