package com.example.data

import androidx.room.ColumnInfo
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
    val modelVersion: Int? = null, // Version of the prediction model that produced predictedProbability / commandProbability
    val initiator: String? = null, // Who started the potty visit: see Initiators
    val holdTargetMin: Int? = null, // For "command" events: target hold time from the adaptive staircase
    val latencyMs: Long? = null, // Pee: from sitting down to the start of the stream (needs a seat sensor)
    val flowMaxMlPerSec: Float? = null, // Pee: peak flow from the weight curve
    val flowPattern: String? = null, // Pee: see FlowPatterns
    @ColumnInfo(defaultValue = "0")
    val volumeEstimated: Boolean = false, // Volume is a model estimate (accidents), not a measurement
    val source: String? = null // "manual", "potty", "wetness_sensor"
)

object EventTypes {
    const val PEE = "pee"
    const val SENSATION = "sensation"
    const val DRINK = "drink"
    const val SLEEP = "sleep"
    const val WAKE = "wake"
    /** Small leak (laughing, coughing...) — the bladder is NOT emptied. */
    const val LEAK = "leak"
    /** Child sat down on the potty (seat sensor). */
    const val SIT = "sit"
    /** App told the child "пора на горшок" (safety prompt). */
    const val PROMPT = "prompt"
    /** App told the child "потерпи N минут" (hold training). */
    const val COMMAND = "command"
}

object Initiators {
    const val CHILD = "child"
    const val APP = "app"
    const val PARENT = "parent"
}

object FlowPatterns {
    const val BELL = "bell" // smooth single stream — relaxed
    const val INTERRUPTED = "interrupted" // stream stops and restarts — tense / shy
    const val UNKNOWN = "unknown" // too few samples
}
