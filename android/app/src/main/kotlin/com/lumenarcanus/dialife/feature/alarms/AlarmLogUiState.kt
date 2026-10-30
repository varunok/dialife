package com.lumenarcanus.dialife.feature.alarms

import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.data.LogEpisode

/** The alarm log: the period picked and its episodes. */
data class AlarmLogUiState(val days: Int = 7, val load: AlarmLogLoad = AlarmLogLoad.Loading, val units: Units = Units.MgDl)

sealed interface AlarmLogLoad {
    data object Loading : AlarmLogLoad
    data object Failed : AlarmLogLoad
    data class Ready(val episodes: List<LogEpisode>) : AlarmLogLoad
}
