package com.lumenarcanus.dialife.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lumenarcanus.dialife.ui.home.Units

enum class ThemeChoice { System, Light, Dark }

/**
 * This phone's own choices. Kept as Compose state so screens follow a change
 * at once, and readable from anywhere (notification, widget) without suspending.
 */
object Prefs {
    private lateinit var sp: SharedPreferences

    /** null: the units the server keeps for this phone. */
    var units by mutableStateOf<Units?>(null)
        private set
    var theme by mutableStateOf(ThemeChoice.System)
        private set

    /** The family's insulins, by type. */
    var rapidName by mutableStateOf<String?>(null) // null: not chosen yet; texts then say just "rapid"
        private set
    var basalName by mutableStateOf<String?>(null)
        private set

    /** When the long-acting dose is due, minutes after midnight, sorted; empty: no schedule. */
    var basalTimes by mutableStateOf<List<Int>>(emptyList())
        private set

    /** Remind an hour before each basal time (unless it was already entered). */
    var basalReminder by mutableStateOf(false)
        private set

    /** The chart's "≈ meter" line: what the glucometer would most likely show. Off unless chosen. */
    var meterLine by mutableStateOf(false)
        private set

    fun init(context: Context) {
        sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        units = sp.getString("units", null)?.let { runCatching { Units.valueOf(it) }.getOrNull() }
        theme = sp.getString("theme", null)?.let { runCatching { ThemeChoice.valueOf(it) }.getOrNull() } ?: ThemeChoice.System
        rapidName = sp.getString("rapid_name", null)
        basalName = sp.getString("basal_name", null)
        basalTimes = sp.getString("basal_times", null)?.split(',')?.mapNotNull { it.toIntOrNull() }?.sorted() ?: emptyList()
        basalReminder = sp.getBoolean("basal_reminder", false)
        meterLine = sp.getBoolean("meter_line", false)
    }

    /** The family's insulin of this type, or null until chosen in settings. */
    fun nameOf(type: InsulinType): String? = if (type == InsulinType.Basal) basalName else rapidName

    fun chooseInsulin(type: InsulinType, name: String) {
        if (type == InsulinType.Basal) basalName = name else rapidName = name
        sp.edit().putString(if (type == InsulinType.Basal) "basal_name" else "rapid_name", name).apply()
    }

    fun chooseBasalTimes(times: List<Int>) {
        basalTimes = times.distinct().sorted()
        sp.edit().putString("basal_times", basalTimes.joinToString(",")).apply()
    }

    fun chooseBasalReminder(on: Boolean) {
        basalReminder = on
        sp.edit().putBoolean("basal_reminder", on).apply()
    }

    fun chooseMeterLine(on: Boolean) {
        meterLine = on
        sp.edit().putBoolean("meter_line", on).apply()
    }

    fun chooseUnits(u: Units?) {
        units = u
        sp.edit().putString("units", u?.name).apply()
    }

    fun chooseTheme(t: ThemeChoice) {
        theme = t
        sp.edit().putString("theme", t.name).apply()
    }
}
