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
    val eventType: String, // e.g. "pee", "sensation"
    val volumeLevel: Int? = null,
    val isAccident: Boolean = false,
    val sensationLevel: String? = null,
    val predictedProbability: Float? = null
)
