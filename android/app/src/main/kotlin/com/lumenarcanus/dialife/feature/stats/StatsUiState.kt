package com.lumenarcanus.dialife.feature.stats

import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.data.MeterReport
import com.lumenarcanus.dialife.data.Stats

/** The statistics screen: the period, its report, and sensor vs glucometer (all checks ever, whatever the period). */
data class StatsUiState(
    val days: Int = 14,
    val load: StatsLoad = StatsLoad.Loading,
    val meter: MeterReport? = null,
    val units: Units = Units.MgDl
)

sealed interface StatsLoad {
    data object Loading : StatsLoad
    data object Failed : StatsLoad
    data class Ready(val stats: Stats) : StatsLoad
}
