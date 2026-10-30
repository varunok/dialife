package com.lumenarcanus.dialife.core.ui

import com.lumenarcanus.dialife.data.Prefs
import java.util.Locale
import kotlin.math.abs

enum class Units { MgDl, Mmol }

/** The units to show: this phone's own choice (settings), else what the server keeps for it. */
fun unitsOf(displayUnits: String?): Units = Prefs.units ?: if (displayUnits?.startsWith("mmol") == true) Units.Mmol else Units.MgDl

/** mg/dL → the text shown for the chosen units, with the locale's decimal separator. */
fun formatGlucose(mgdl: Int, units: Units, locale: Locale): String = when (units) {
    Units.MgDl -> mgdl.toString()
    Units.Mmol -> String.format(locale, "%.1f", mgdl / 18.0)
}

/** A change in mg/dL with its sign: "+12", "−0.7", "±0". */
fun formatDelta(mgdl: Int, units: Units, locale: Locale): String {
    val sign = when {
        mgdl > 0 -> "+"
        mgdl < 0 -> "−"
        else -> "±"
    }
    return sign + formatGlucose(abs(mgdl), units, locale)
}
