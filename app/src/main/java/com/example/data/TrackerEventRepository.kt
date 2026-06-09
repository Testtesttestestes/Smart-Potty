package com.example.data

import kotlinx.coroutines.flow.Flow

class TrackerEventRepository(private val trackerEventDao: TrackerEventDao) {
    fun getEventsForChild(childId: String): Flow<List<TrackerEvent>> = trackerEventDao.getEventsForChild(childId)
    
    val allEvents: Flow<List<TrackerEvent>> = trackerEventDao.getAllEvents()

    suspend fun insertEvent(event: TrackerEvent) {
        trackerEventDao.insertEvent(event)
    }

    suspend fun deleteEventById(id: String) {
        trackerEventDao.deleteEventById(id)
    }
}
