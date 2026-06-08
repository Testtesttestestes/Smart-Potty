package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "children")
data class Child(
    @PrimaryKey
    val childId: String = UUID.randomUUID().toString(),
    val name: String = "",
    val birthDate: String = "", // Format: YYYY-MM-DD
    val gender: String = "",
    val weight: Float? = null,
    val sleepSchedule: String? = null
)
