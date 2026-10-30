package com.lumenarcanus.dialife.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumenarcanus.dialife.core.data.DoseSettingsRepository
import com.lumenarcanus.dialife.core.ui.unitsOf
import com.lumenarcanus.dialife.data.DoseSettings
import com.lumenarcanus.dialife.live.LiveRepository
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Edits the family's dose settings, starting from the ones the phone has now;
 * nothing is saved until [save]. Who changed them last follows the server.
 */
class DoseSettingsViewModel(private val repository: DoseSettingsRepository, live: LiveRepository, locale: Locale) : ViewModel() {
    private val _uiState = MutableStateFlow(
        live.live.value.state.let { state ->
            val dose = state?.dose ?: DoseSettings()
            val units = unitsOf(state?.displayUnits ?: live.credentials?.displayUnits)
            DoseSettingsUiState(doseSettingsForm(dose, units, locale), units, dose.updatedBy)
        }
    )
    val uiState: StateFlow<DoseSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            live.live.map { it.state?.dose?.updatedBy }.distinctUntilChanged().collect { by ->
                if (by != null) _uiState.update { it.copy(updatedBy = by) }
            }
        }
    }

    fun edit(form: DoseSettingsForm) = _uiState.update { it.copy(form = form) }

    fun save() {
        val s = _uiState.value
        if (s.saving || s.problems.isNotEmpty()) return
        _uiState.update { it.copy(saving = true, saved = null) }
        viewModelScope.launch {
            val ok = repository.save(s.form.toSettings(s.units)).isSuccess
            _uiState.update { it.copy(saving = false, saved = ok) }
        }
    }
}
