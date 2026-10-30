package com.lumenarcanus.dialife.feature.alarms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumenarcanus.dialife.core.ui.unitsOf
import com.lumenarcanus.dialife.live.LiveSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Loads the episodes of the picked period (again on retry). */
class AlarmLogViewModel(live: LiveSource, private val repository: AlarmLogRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(
        AlarmLogUiState(units = unitsOf(live.live.value.state?.displayUnits ?: live.credentials?.displayUnits))
    )
    val uiState: StateFlow<AlarmLogUiState> = _uiState.asStateFlow()
    private var loading: Job? = null

    init {
        load()
    }

    fun pick(days: Int) {
        _uiState.update { it.copy(days = days) }
        load()
    }

    fun retry() = load()

    private fun load() {
        val days = _uiState.value.days
        _uiState.update { it.copy(load = AlarmLogLoad.Loading) }
        loading?.cancel() // a later pick wins
        loading = viewModelScope.launch {
            val load = repository.episodes(days).fold({ AlarmLogLoad.Ready(it) }, { AlarmLogLoad.Failed })
            _uiState.update { it.copy(load = load) }
        }
    }
}
