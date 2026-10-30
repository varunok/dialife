package com.lumenarcanus.dialife.feature.alarms

import androidx.annotation.StringRes
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.data.AlarmView
import com.lumenarcanus.dialife.data.EpisodeRules
import com.lumenarcanus.dialife.data.StateResponse

/** The live episodes on home. */
data class AlarmsUiState(
    val episodes: List<AlarmView> = emptyList(),
    // who "I" am in handled_by / coming_by
    val myId: Long = 0,
    val units: Units = Units.MgDl,
    // the family's episode rules: check-in timeout, ceiling
    val episode: EpisodeRules? = null,
    // shown once, then AlarmsViewModel.userMessageShown()
    @StringRes val userMessage: Int? = null
)

fun alarmsUiState(state: StateResponse?, myId: Long, units: Units): AlarmsUiState =
    AlarmsUiState(state?.alarms.orEmpty(), myId, units, state?.episode)
