package com.lumenarcanus.dialife.feature.settings

import com.lumenarcanus.dialife.core.ui.Units

/** The dose settings screen: the form, who changed the settings last, and how saving went (null: not tried). */
data class DoseSettingsUiState(
    val form: DoseSettingsForm,
    val units: Units,
    val updatedBy: String = "",
    val saving: Boolean = false,
    val saved: Boolean? = null
) {
    val problems: List<DoseProblem> get() = form.problems(units)
}
