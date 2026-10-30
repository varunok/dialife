package com.lumenarcanus.dialife.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.theme.DiaLifeTheme
import com.lumenarcanus.dialife.data.DoseSettings
import com.lumenarcanus.dialife.feature.entry.TreatmentKind
import com.lumenarcanus.dialife.feature.entry.TreatmentSheet
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The entry sheet for each kind, new; nothing on it depends on the clock
 * (no picked time, no meal or dose nearby), so the pictures stay the same.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "uk-w412dp-h1400dp-xxhdpi")
class EntrySheetShots {
    @get:Rule val compose = createComposeRule()

    private fun sheet(
        name: String,
        kind: TreatmentKind,
        units: Units = Units.MgDl,
        dose: DoseSettings = DoseSettings(),
        act: () -> Unit = {
        }
    ) {
        compose.setContent {
            DiaLifeTheme(dark = true) { TreatmentSheet(kind, null, onSave = {}, onDismiss = {}, units = units, dose = dose) }
        }
        compose.waitForIdle()
        act()
        compose.waitForIdle()
        captureScreenRoboImage("src/test/screenshots/entry_$name.png")
    }

    // a dose over the family's limit: the alert and its own confirm
    @Test
    fun insulinOverTheLimit() = sheet("insulin_over_limit_dark_uk", TreatmentKind.Insulin, dose = DoseSettings(maxRapid = 3.0)) {
        compose.onNodeWithText("4 од").performClick()
    }

    @Test
    fun activity() = sheet("activity_dark_uk", TreatmentKind.Activity)

    @Test
    fun meterMmol() = sheet("meter_dark_uk", TreatmentKind.Meter, units = Units.Mmol)

    @Test
    fun note() = sheet("note_dark_uk", TreatmentKind.Note)
}
