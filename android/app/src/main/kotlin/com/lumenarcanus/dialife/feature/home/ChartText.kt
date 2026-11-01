package com.lumenarcanus.dialife.feature.home

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.data.Question
import com.lumenarcanus.dialife.feature.entry.gapText
import com.lumenarcanus.dialife.feature.questions.QuestionNotifier
import java.util.Date
import java.util.Locale

/** The words the chart draws, read once per composition so the drawing layers need no resources. */
internal class ChartText(
    val locale: Locale,
    val units: Units,
    val clock: (Long) -> String,
    val insulinGlyph: String,
    val carbsGlyph: String,
    val noteGlyph: String,
    val basalGlyph: String,
    val suspectGlyph: String,
    val activityGlyph: String,
    val insulinLabel: String,
    val carbsLabel: String,
    val noteLabel: String,
    val corrLabel: String,
    val unitCarbs: String,
    val unitInsulin: String,
    val pendingLabel: String,
    val meterLabel: String,
    val meterSensor: String,
    val meterEstimate: String,
    val suspectRise: String,
    val suspectFall: String,
    val suspectBolus: String,
    val suspectDouble: String,
    val suspectHint: String,
    val activityLabel: String,
    private val intensities: Map<String, String>,
    private val typedBy: String,
    private val autoBy: String,
    val doubleInsulin: (Question) -> String,
    val gap: (Long) -> String
) {
    fun intensityLabel(i: String?) = intensities[i] ?: intensities.getValue("moderate")

    fun originText(o: Origin): String? = when (o) {
        is Origin.Here -> o.who.takeIf { it.isNotBlank() }?.let { typedBy.format(locale, it) }
        Origin.XDrip -> typedBy.format(locale, "xDrip")
        is Origin.Device -> autoBy.format(locale, o.name)
    }
}

@Composable
internal fun rememberChartText(units: Units): ChartText {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val timeFormat = remember(locale) { DateFormat.getTimeFormat(context) }
    return ChartText(
        locale = locale,
        units = units,
        clock = { t -> timeFormat.format(Date(t)) },
        insulinGlyph = stringResource(R.string.glyph_insulin),
        carbsGlyph = stringResource(R.string.glyph_carbs),
        noteGlyph = stringResource(R.string.glyph_note),
        basalGlyph = stringResource(R.string.glyph_basal),
        suspectGlyph = stringResource(R.string.glyph_suspect),
        activityGlyph = stringResource(R.string.glyph_activity),
        insulinLabel = stringResource(R.string.event_insulin),
        carbsLabel = stringResource(R.string.event_carbs),
        noteLabel = stringResource(R.string.tr_note),
        corrLabel = stringResource(R.string.detail_for_corr),
        unitCarbs = stringResource(R.string.unit_carbs),
        unitInsulin = stringResource(R.string.unit_insulin),
        pendingLabel = stringResource(R.string.tr_pending),
        meterLabel = stringResource(R.string.meter_label),
        meterSensor = stringResource(R.string.meter_sensor),
        meterEstimate = stringResource(R.string.meter_estimate),
        suspectRise = stringResource(R.string.suspect_rise),
        suspectFall = stringResource(R.string.suspect_fall),
        suspectBolus = stringResource(R.string.suspect_bolus),
        suspectDouble = stringResource(R.string.suspect_double),
        suspectHint = stringResource(R.string.suspect_hint),
        activityLabel = stringResource(R.string.event_activity),
        intensities = mapOf(
            "light" to stringResource(R.string.act_light),
            "moderate" to stringResource(R.string.act_moderate),
            "vigorous" to stringResource(R.string.act_vigorous)
        ),
        typedBy = stringResource(R.string.origin_typed),
        autoBy = stringResource(R.string.origin_auto),
        doubleInsulin = { q -> QuestionNotifier.doubleInsulin(context, q) },
        gap = { ms -> gapText(context, ms) }
    )
}
