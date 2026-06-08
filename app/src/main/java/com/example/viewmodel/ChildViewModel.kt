package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.Child
import com.example.data.ChildRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChildViewModel(private val repository: ChildRepository) : ViewModel() {

    val uiState: StateFlow<List<Child>> = repository.allChildren
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun saveChild(child: Child) {
        viewModelScope.launch {
            repository.insert(child)
        }
    }

    fun deleteChild(id: String) {
        viewModelScope.launch {
            repository.deleteById(id)
        }
    }
}

class ChildViewModelFactory(private val repository: ChildRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChildViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ChildViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
