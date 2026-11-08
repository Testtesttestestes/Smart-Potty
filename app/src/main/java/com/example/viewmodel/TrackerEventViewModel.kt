package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.ChildRepository
import com.example.data.TrackerEvent
import com.example.data.TrackerEventRepository
import com.example.ml.TrackerAnalytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

class TrackerEventViewModel(
    private val repository: TrackerEventRepository,
    private val childRepository: ChildRepository
) : ViewModel() {

    fun getEventsForChild(childId: String): Flow<List<TrackerEvent>> {
        return repository.getEventsForChild(childId)
    }

    /** Analytics recomputed on every data change and once a minute (probabilities depend on time). */
    fun analyticsForChild(childId: String): Flow<TrackerAnalytics.Snapshot> {
        val ticker = flow {
            while (true) {
                emit(System.currentTimeMillis())
                delay(60_000)
            }
        }
        return combine(repository.getEventsForChild(childId), childRepository.getChild(childId), ticker) { events, child, _ ->
            TrackerAnalytics.compute(events, child, System.currentTimeMillis())
        }.flowOn(Dispatchers.Default)
    }

    fun saveEvent(event: TrackerEvent) {
        viewModelScope.launch {
            repository.insertEvent(event)
        }
    }

    fun deleteEvent(id: String) {
        viewModelScope.launch {
            repository.deleteEventById(id)
        }
    }
}

class TrackerEventViewModelFactory(
    private val repository: TrackerEventRepository,
    private val childRepository: ChildRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TrackerEventViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TrackerEventViewModel(repository, childRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
