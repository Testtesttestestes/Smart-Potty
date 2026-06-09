package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackerEventDao {
    @Query("SELECT * FROM tracker_events WHERE childId = :childId ORDER BY timestamp DESC")
    fun getEventsForChild(childId: String): Flow<List<TrackerEvent>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: TrackerEvent)

    @Query("DELETE FROM tracker_events WHERE eventId = :id")
    suspend fun deleteEventById(id: String)
    
    @Query("SELECT * FROM tracker_events ORDER BY timestamp DESC")
    fun getAllEvents(): Flow<List<TrackerEvent>>
}
