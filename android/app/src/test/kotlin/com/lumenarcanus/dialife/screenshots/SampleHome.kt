package com.lumenarcanus.dialife.screenshots

import androidx.compose.runtime.Composable
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.unitsOf
import com.lumenarcanus.dialife.feature.alarms.alarmsUiState
import com.lumenarcanus.dialife.feature.home.HomeScreen
import com.lumenarcanus.dialife.feature.home.HomeUi
import com.lumenarcanus.dialife.feature.questions.QuestionCards
import com.lumenarcanus.dialife.feature.questions.WatchCards
import com.lumenarcanus.dialife.feature.questions.questionsUiState
import com.lumenarcanus.dialife.feature.sensorless.SensorlessCards
import com.lumenarcanus.dialife.feature.sensorless.sensorlessUiState

/** Home as the tests draw it: the feature sections filled from the sample, without their view models. */
@Composable
fun SampleHome(ui: HomeUi, deviceName: String) {
    val units = ui.state?.displayUnits?.let(::unitsOf) ?: Units.MgDl
    val questions = questionsUiState(ui.state, ui.history?.treatments.orEmpty(), ui.myId, units, ui.now)
    val alarms = alarmsUiState(ui.state, ui.myId, units)
    HomeScreen(
        ui,
        deviceName = deviceName,
        questionsSection = { onLog -> QuestionCards(questions, onAction = {}, onLog = onLog) },
        watchSection = { WatchCards(questions) },
        sensorlessSection = { onMeasure ->
            SensorlessCards(sensorlessUiState(ui.state?.sensorless, units, ui.now), onClose = {}, onMeasure = onMeasure)
        }
    )
}
