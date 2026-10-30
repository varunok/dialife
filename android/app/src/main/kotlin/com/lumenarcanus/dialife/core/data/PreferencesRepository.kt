package com.lumenarcanus.dialife.core.data

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.snapshotFlow
import androidx.core.os.LocaleListCompat
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.data.InsulinType
import com.lumenarcanus.dialife.data.Prefs
import com.lumenarcanus.dialife.data.ThemeChoice
import com.lumenarcanus.dialife.live.BasalReminder
import kotlinx.coroutines.flow.Flow

/** This phone's own choices, as one value. */
data class LocalPrefs(
    val units: Units? = null,
    val theme: ThemeChoice = ThemeChoice.System,
    // "": the system's; else a language tag ("uk", "en", "es")
    val language: String = "",
    val meterLine: Boolean = false,
    val rapidName: String? = null,
    val basalName: String? = null,
    val basalTimes: List<Int> = emptyList(),
    val basalReminder: Boolean = false
) {
    fun nameOf(type: InsulinType): String? = if (type == InsulinType.Basal) basalName else rapidName
}

/** This phone's own choices: read as they change, written with what follows from them (the basal reminder, the app's language). */
class PreferencesRepository(private val context: Context) {
    val prefs: Flow<LocalPrefs> = snapshotFlow { current() }

    fun current() = LocalPrefs(
        units = Prefs.units,
        theme = Prefs.theme,
        language = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-'),
        meterLine = Prefs.meterLine,
        rapidName = Prefs.rapidName,
        basalName = Prefs.basalName,
        basalTimes = Prefs.basalTimes,
        basalReminder = Prefs.basalReminder
    )

    fun chooseUnits(u: Units?) = Prefs.chooseUnits(u)

    fun chooseTheme(t: ThemeChoice) = Prefs.chooseTheme(t)

    fun chooseLanguage(tag: String) = AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))

    fun chooseMeterLine(on: Boolean) = Prefs.chooseMeterLine(on)

    fun chooseInsulin(type: InsulinType, name: String) = Prefs.chooseInsulin(type, name)

    fun chooseBasalTimes(times: List<Int>) {
        Prefs.chooseBasalTimes(times)
        BasalReminder.schedule(context)
    }

    fun chooseBasalReminder(on: Boolean) {
        Prefs.chooseBasalReminder(on)
        BasalReminder.schedule(context)
    }
}
