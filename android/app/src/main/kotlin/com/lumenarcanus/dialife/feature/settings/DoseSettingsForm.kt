package com.lumenarcanus.dialife.feature.settings

import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.formatAmount
import com.lumenarcanus.dialife.core.ui.formatGlucose
import com.lumenarcanus.dialife.data.CorrectionRow
import com.lumenarcanus.dialife.data.DoseSettings
import java.util.Locale
import kotlin.math.round

/** The dose settings as typed: text in this phone's units, checked by [problems] before it becomes [DoseSettings]. */
data class DoseSettingsForm(
    val ratio: String,
    val rule: String,
    val step: Double,
    val fresh: String,
    val maxRapid: String,
    val maxBasal: String,
    val maxCarbs: String,
    // the correction table: the glucometer value from, and the units added
    val rows: List<Pair<String, String>>,
    val isf: String,
    val dia: String,
    val carbMin: String,
    val waitMin: String,
    val waitHighMin: String,
    val waitHighFrom: String,
    val waitLowBelow: String
)

fun doseSettingsForm(dose: DoseSettings, units: Units, locale: Locale): DoseSettingsForm {
    fun f(x: Double) = formatAmount(x, locale)
    return DoseSettingsForm(
        ratio = dose.ratio.toString(),
        rule = dose.rule,
        step = dose.step,
        fresh = dose.freshMin.toString(),
        maxRapid = f(dose.maxRapid),
        maxBasal = f(dose.maxBasal),
        maxCarbs = dose.maxCarbs.toString(),
        rows = dose.table.map { formatGlucose(it.mg, units, locale) to f(it.u) },
        isf = if (dose.isf > 0) formatGlucose(dose.isf, units, locale) else "",
        dia = dose.diaMin.toString(),
        carbMin = dose.carbMin.toString(),
        waitMin = dose.waitMin.toString(),
        waitHighMin = dose.waitHighMin.toString(),
        waitHighFrom = formatGlucose(dose.waitHighFrom, units, locale),
        waitLowBelow = formatGlucose(dose.waitLowBelow, units, locale)
    )
}

/** What keeps the form from being saved; each is told under the form. */
sealed interface DoseProblem {
    data object Ratio : DoseProblem
    data object Fresh : DoseProblem
    data object MaxRapid : DoseProblem
    data object MaxBasal : DoseProblem
    data object MaxCarbs : DoseProblem
    data object Table : DoseProblem
    data object Twice : DoseProblem
    data class TableOverMax(val max: Double) : DoseProblem
    data object Isf : DoseProblem
    data object Dia : DoseProblem
    data object CarbMin : DoseProblem
    data object Wait : DoseProblem
}

internal fun num(text: String) = text.trim().replace(',', '.').toDoubleOrNull()

/** A glucose typed in [units], in mg/dL. */
internal fun mgOf(text: String, units: Units): Int? = num(text)?.let { v ->
    round(if (units == Units.Mmol) v * 18.0 else v).toInt()
}

private fun DoseSettingsForm.table(units: Units) = rows.map { (mg, u) -> mgOf(mg, units) to num(u) }

fun DoseSettingsForm.problems(units: Units): List<DoseProblem> = buildList {
    val table = table(units)
    if (ratio.toIntOrNull()?.let { it in 1..100 } != true) add(DoseProblem.Ratio)
    if (fresh.toIntOrNull()?.let { it in 1..60 } != true) add(DoseProblem.Fresh)
    val mr = num(maxRapid)
    if (mr == null || mr < 0.5 || mr > 100) add(DoseProblem.MaxRapid)
    if (num(maxBasal)?.let { it in 0.5..200.0 } != true) add(DoseProblem.MaxBasal)
    if (maxCarbs.toIntOrNull()?.let { it in 20..500 } != true) add(DoseProblem.MaxCarbs)
    if (table.any { (mg, u) -> mg == null || mg !in 40..600 || u == null || u < 0 || u > 50 }) add(DoseProblem.Table)
    val mgs = table.mapNotNull { it.first }
    if (mgs.size != mgs.toSet().size) add(DoseProblem.Twice)
    if (mr != null && table.any { (_, u) -> u != null && u > mr }) add(DoseProblem.TableOverMax(mr))
    if (isf.isNotBlank() && mgOf(isf, units)?.let { it in 5..400 } != true) add(DoseProblem.Isf)
    if (dia.toIntOrNull()?.let { it in 120..480 } != true) add(DoseProblem.Dia)
    if (carbMin.toIntOrNull()?.let { it in 60..360 } != true) add(DoseProblem.CarbMin)
    val wm = waitMin.toIntOrNull()
    val whm = waitHighMin.toIntOrNull()
    val low = mgOf(waitLowBelow, units)
    val high = mgOf(waitHighFrom, units)
    if (wm == null ||
        wm !in 0..45 ||
        whm == null ||
        whm !in wm..60 ||
        low == null ||
        low !in 60..120 ||
        high == null ||
        high <= low ||
        high > 300
    ) {
        add(DoseProblem.Wait)
    }
}

/** The settings to save; null while a field does not read as a number (the form then has [problems]). */
fun DoseSettingsForm.toSettings(units: Units): DoseSettings? {
    val table = table(units).map { (mg, u) -> CorrectionRow(mg ?: return null, u ?: return null) }.sortedBy { it.mg }
    return DoseSettings(
        ratio = ratio.toIntOrNull() ?: return null,
        rule = rule,
        step = step,
        freshMin = fresh.toIntOrNull() ?: return null,
        maxRapid = num(maxRapid) ?: return null,
        maxBasal = num(maxBasal) ?: return null,
        maxCarbs = maxCarbs.toIntOrNull() ?: return null,
        table = table,
        isf = if (isf.isBlank()) 0 else mgOf(isf, units) ?: return null,
        diaMin = dia.toIntOrNull() ?: return null,
        carbMin = carbMin.toIntOrNull() ?: return null,
        waitMin = waitMin.toIntOrNull() ?: return null,
        waitHighMin = waitHighMin.toIntOrNull() ?: return null,
        waitHighFrom = mgOf(waitHighFrom, units) ?: return null,
        waitLowBelow = mgOf(waitLowBelow, units) ?: return null
    )
}
