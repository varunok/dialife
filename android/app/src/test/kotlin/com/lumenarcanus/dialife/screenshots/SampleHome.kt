package com.lumenarcanus.dialife.screenshots

import androidx.compose.runtime.Composable
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.unitsOf
import com.lumenarcanus.dialife.feature.questions.QuestionCards
import com.lumenarcanus.dialife.feature.questions.WatchCards
import com.lumenarcanus.dialife.feature.questions.questionsUiState
import com.lumenarcanus.dialife.ui.home.HomeScreen
import com.lumenarcanus.dialife.ui.home.HomeUi

/** Home as the tests draw it: the feature sections filled from the sample, without their view models. */
@Composable
fun SampleHome(ui: HomeUi, deviceName: String) {
    val units = ui.state?.displayUnits?.let(::unitsOf) ?: Units.MgDl
    val questions = questionsUiState(ui.state, ui.history?.treatments.orEmpty(), ui.myId, units, ui.now)
    HomeScreen(
        ui,
        deviceName = deviceName,
        questionsSection = { onLog -> QuestionCards(questions, onAction = {}, onLog = onLog) },
        watchSection = { WatchCards(questions) }
    )
}
