package com.lumenarcanus.dialife.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumenarcanus.dialife.core.ui.unitsOf
import com.lumenarcanus.dialife.live.LiveSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Loads the report for the picked period, and the glucometer comparison once (again on retry). */
class StatsViewModel(live: LiveSource, private val repository: StatsRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(
        StatsUiState(
            units = unitsOf(
                live.live.value.state?.displayUnits ?: live.credentials?.displayUnits
            )
        )
    )
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun pick(days: Int) {
        _uiState.update { it.copy(days = days) }
        loadStats()
    }

    fun retry() = load()

    private fun load() {
        loadStats()
        viewModelScope.launch { repository.meter().onSuccess { m -> _uiState.update { it.copy(meter = m) } } }
    }

    private fun loadStats() {
        val days = _uiState.value.days
        _uiState.update { it.copy(load = StatsLoad.Loading) }
        viewModelScope.launch {
            val load = repository.stats(days).fold({ StatsLoad.Ready(it) }, { StatsLoad.Failed })
            _uiState.update { if (it.days == days) it.copy(load = load) else it } // a later pick wins
        }
    }
}
