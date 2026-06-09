package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.TrackerEvent
import com.example.data.TrackerEventRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TrackerEventViewModel(private val repository: TrackerEventRepository) : ViewModel() {

    fun getEventsForChild(childId: String): Flow<List<TrackerEvent>> {
        return repository.getEventsForChild(childId)
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

class TrackerEventViewModelFactory(private val repository: TrackerEventRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TrackerEventViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TrackerEventViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
