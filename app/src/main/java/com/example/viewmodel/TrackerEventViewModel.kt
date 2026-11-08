package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.ChildRepository
import com.example.data.CoachingSettingsRepository
import com.example.data.TrackerEvent
import com.example.data.TrackerEventRepository
import com.example.ml.Coaching
import com.example.ml.TrackerAnalytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class TrackerEventViewModel(
    private val repository: TrackerEventRepository,
    private val childRepository: ChildRepository,
    private val settingsRepository: CoachingSettingsRepository
) : ViewModel() {

    fun coachingSettings(childId: String): StateFlow<Coaching.Settings> = settingsRepository.settings(childId)

    fun updateCoachingSettings(childId: String, settings: Coaching.Settings) =
        settingsRepository.update(childId, settings)

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
        val data = combine(repository.getEventsForChild(childId), childRepository.getChild(childId)) { events, child ->
            events to child
        }
        // The learned part (signal coefficients, back-test) only changes with the data.
        val learned = data.map { (events, child) ->
            Triple(events, child, TrackerAnalytics.learn(events, child, System.currentTimeMillis()))
        }
        return combine(learned, settingsRepository.settings(childId), ticker) { (events, child, learnedPart), settings, _ ->
            TrackerAnalytics.compute(events, child, System.currentTimeMillis(), learned = learnedPart, settings = settings)
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
    private val childRepository: ChildRepository,
    private val settingsRepository: CoachingSettingsRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TrackerEventViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TrackerEventViewModel(repository, childRepository, settingsRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
