package com.lumenarcanus.dialife.feature.settings

import androidx.annotation.StringRes
import com.lumenarcanus.dialife.core.data.LocalPrefs
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.data.InsulinType
import com.lumenarcanus.dialife.data.StateResponse
import com.lumenarcanus.dialife.data.ThemeChoice

/** The settings screen: this phone's connection, what the server keeps for the family, this phone's own choices. */
data class SettingsUiState(
    val creds: Credentials,
    val state: StateResponse? = null,
    val prefs: LocalPrefs = LocalPrefs(),
    // the test sound is playing
    val testing: Boolean = false,
    // a change to the "no sensor" mode is on its way to the server
    val sensorlessBusy: Boolean = false,
    // shown once, then SettingsViewModel.userMessageShown()
    @StringRes val userMessage: Int? = null
) {
    /** The units the server keeps for this phone. */
    val serverUnits: Units
        get() = if (state?.displayUnits?.startsWith("mmol") == true || (state == null && creds.displayUnits.startsWith("mmol"))) {
            Units.Mmol
        } else {
            Units.MgDl
        }
}

sealed interface SettingsAction {
    data class ChooseUnits(val units: Units?) : SettingsAction
    data class ChooseTheme(val theme: ThemeChoice) : SettingsAction
    data class ChooseLanguage(val tag: String) : SettingsAction
    data class ChooseMeterLine(val on: Boolean) : SettingsAction
    data class ChooseInsulin(val type: InsulinType, val name: String) : SettingsAction
    data class AddBasalTime(val minutes: Int) : SettingsAction
    data class RemoveBasalTime(val minutes: Int) : SettingsAction
    data class ChooseBasalReminder(val on: Boolean) : SettingsAction
    data object TestSound : SettingsAction

    // the family's, on every phone (the server keeps them)
    data class SetSensorless(val on: Boolean) : SettingsAction
    data class ChooseSensorlessRemind(val minutes: Int) : SettingsAction
}
