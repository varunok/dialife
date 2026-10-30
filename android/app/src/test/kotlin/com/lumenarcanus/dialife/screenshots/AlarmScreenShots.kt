package com.lumenarcanus.dialife.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.theme.DiaLifeTheme
import com.lumenarcanus.dialife.data.AlarmView
import com.lumenarcanus.dialife.data.Caregiver
import com.lumenarcanus.dialife.feature.alarms.AlarmScreen
import com.lumenarcanus.dialife.feature.home.sampleHomeUi
import java.util.TimeZone
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The full-screen alarm as it rings; the help request shows a live chart, so it is not pictured here. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "uk-w412dp-h915dp-xxhdpi")
class AlarmScreenShots {
    @get:Rule val compose = createComposeRule()

    private val now = 1_761_400_000_000L

    @Before
    fun fixedZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT+3"))
    }

    private fun shot(name: String, alarm: AlarmView) {
        val st = sampleHomeUi(now).state!!.let {
            it.copy(bg = it.bg!!.copy(sgv = alarm.sgv ?: 0), caregivers = listOf(Caregiver(8, "Тато"), Caregiver(9, "Мама")))
        }
        compose.setContent {
            DiaLifeTheme(dark = true) {
                AlarmScreen(alarm, help = false, state = st, current = alarm.sgv, units = Units.MgDl, onAct = {
                    Result.success(Unit)
                }, onDone = {})
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    // an urgent low cannot be quieted: one button
    @Test
    fun urgentLow() = shot(
        "alarm_urgent_low_dark_uk",
        AlarmView(
            id = 1,
            kind = "low",
            level = "urgent",
            state = "firing",
            startedAt =
            now - 4 * 60_000L,
            sgv = 52
        )
    )

    @Test
    fun high() = shot(
        "alarm_high_dark_uk",
        AlarmView(
            id = 2,
            kind = "high",
            level = "warn",
            state = "firing",
            startedAt =
            now - 20 * 60_000L,
            sgv = 262
        )
    )
}
