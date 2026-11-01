package com.lumenarcanus.dialife.feature.settings

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.InputChip
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.BuildConfig
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.data.LocalPrefs
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.formatAmount
import com.lumenarcanus.dialife.core.ui.formatClock
import com.lumenarcanus.dialife.core.ui.formatGlucose
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.core.ui.unitsOf
import com.lumenarcanus.dialife.data.DoseSettings
import com.lumenarcanus.dialife.data.InsulinType
import com.lumenarcanus.dialife.data.Insulins
import com.lumenarcanus.dialife.data.StateResponse
import com.lumenarcanus.dialife.data.ThemeChoice
import com.lumenarcanus.dialife.feature.alarms.AlarmText
import com.lumenarcanus.dialife.feature.home.BackgroundSetup
import com.lumenarcanus.dialife.feature.meal.ruleName
import java.util.Locale

/** This phone's settings: how it looks, alarms (view only in v1), permissions, connection. */
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    setup: BackgroundSetup,
    onAction: (SettingsAction) -> Unit,
    onFixNotifications: () -> Unit,
    onFixBattery: () -> Unit,
    onFixFullScreen: () -> Unit,
    onDisconnect: () -> Unit,
    onBack: () -> Unit,
    onOpenDose: () -> Unit = {},
    // fixed in screenshot tests, so a new version does not change them
    version: String = BuildConfig.VERSION_NAME
) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val locale = ctx.resources.configuration.locales[0]
    val creds = uiState.creds
    val state = uiState.state
    val prefs = uiState.prefs
    var confirmDisconnect by remember { mutableStateOf(false) }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(stringResource(R.string.set_disconnect_title)) },
            text = { Text(stringResource(R.string.set_disconnect_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    onDisconnect()
                }) { Text(stringResource(R.string.action_disconnect), color = c.low) }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(R.string.tr_cancel)) } }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.nav_back), color = c.textMuted) }
        Text(stringResource(R.string.action_settings), color = c.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)

        Section(stringResource(R.string.set_look)) { LookSection(uiState, onAction) }
        Section(stringResource(R.string.set_insulins)) { InsulinsSection(uiState.prefs, onAction) }
        Section(stringResource(R.string.dose_title)) { DoseSection(state?.dose ?: DoseSettings(), locale, onOpenDose) }
        Section(stringResource(R.string.set_alarms)) { AlarmsSection(state, uiState.testing, locale, onAction) }
        Section(stringResource(R.string.set_permissions)) {
            Permission(stringResource(R.string.set_perm_notifications), !setup.notifications, onFixNotifications)
            Permission(stringResource(R.string.set_perm_battery), !setup.battery, onFixBattery)
            Permission(stringResource(R.string.set_perm_fullscreen), !setup.fullScreen, onFixFullScreen)
        }
        Section(stringResource(R.string.set_connection)) {
            ConnectionSection(uiState, version, onDisconnect = { confirmDisconnect = true })
        }
        Spacer(Modifier.heightIn(min = 12.dp))
    }
}

/** Units, theme, language and the chart's "≈ meter" line: this phone's own. */
@Composable
private fun LookSection(uiState: SettingsUiState, onAction: (SettingsAction) -> Unit) {
    val c = DiaTheme.colors
    val prefs = uiState.prefs
    Label(stringResource(R.string.set_units))
    val serverUnits = uiState.serverUnits
    Choice(
        listOf(
            null to
                stringResource(
                    R.string.set_units_server,
                    stringResource(
                        if (serverUnits == Units.MgDl) {
                            R.string.unit_mgdl
                        } else {
                            R.string.unit_mmol
                        }
                    )
                ),
            Units.MgDl to stringResource(R.string.unit_mgdl),
            Units.Mmol to stringResource(R.string.unit_mmol)
        ),
        prefs.units
    ) { onAction(SettingsAction.ChooseUnits(it)) }
    Label(stringResource(R.string.set_theme))
    Choice(
        listOf(
            ThemeChoice.System to stringResource(R.string.set_system),
            ThemeChoice.Light to stringResource(R.string.set_theme_light),
            ThemeChoice.Dark to stringResource(R.string.set_theme_dark)
        ),
        prefs.theme
    ) { onAction(SettingsAction.ChooseTheme(it)) }
    Label(stringResource(R.string.set_language))
    Choice(
        listOf("" to stringResource(R.string.set_system), "uk" to "Українська", "en" to "English", "es" to "Español"),
        prefs.language
    ) { tag -> onAction(SettingsAction.ChooseLanguage(tag)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.set_meter_line), color = c.text, fontSize = 14.sp)
            Text(stringResource(R.string.set_meter_line_sub), color = c.textMuted, fontSize = 12.sp)
        }
        Switch(checked = prefs.meterLine, onCheckedChange = {
            onAction(SettingsAction.ChooseMeterLine(it))
        })
    }
}

/** The family's insulins, when the long-acting one is due, and the reminder before it. */
@Composable
private fun InsulinsSection(prefs: LocalPrefs, onAction: (SettingsAction) -> Unit) {
    val c = DiaTheme.colors
    InsulinPicker(InsulinType.Rapid, prefs.nameOf(InsulinType.Rapid)) {
        onAction(SettingsAction.ChooseInsulin(InsulinType.Rapid, it))
    }
    InsulinPicker(InsulinType.Basal, prefs.nameOf(InsulinType.Basal)) {
        onAction(SettingsAction.ChooseInsulin(InsulinType.Basal, it))
    }
    Label(stringResource(R.string.set_basal_times))
    var adding by remember { mutableStateOf(false) }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        prefs.basalTimes.forEach { m ->
            InputChip(
                selected = false,
                onClick = { onAction(SettingsAction.RemoveBasalTime(m)) },
                label = { Text(String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60)) },
                trailingIcon = { Text("✕", color = c.textMuted) }
            )
        }
        AssistChip(onClick = { adding = true }, label = { Text(stringResource(R.string.set_basal_add)) })
    }
    if (adding) {
        BasalTimeDialog(onPick = { m ->
            adding = false
            onAction(SettingsAction.AddBasalTime(m))
        }, onDismiss = { adding = false })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.set_basal_remind), color = c.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = prefs.basalReminder,
            enabled = prefs.basalTimes.isNotEmpty(),
            onCheckedChange = { onAction(SettingsAction.ChooseBasalReminder(it)) }
        )
    }
}

/** The dose calculation at a glance; it is changed on its own screen. */
@Composable
private fun DoseSection(d: DoseSettings, locale: Locale, onOpenDose: () -> Unit) {
    val c = DiaTheme.colors
    Line(stringResource(R.string.dose_ratio_short), stringResource(R.string.meal_ratio, d.ratio))
    Line(
        stringResource(R.string.dose_rounding),
        ruleName(d.rule) + " · " + formatAmount(d.step, locale)
    )
    Line(
        stringResource(R.string.dose_limits_short),
        formatAmount(d.maxRapid, locale) + " " + stringResource(R.string.unit_insulin) + " · " + d.maxCarbs + " " +
            stringResource(R.string.unit_carbs)
    )
    OutlinedButton(onClick = onOpenDose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.dose_open), color = c.text)
    }
}

/** The thresholds (set on the server) and the alarm sound's test. */
@Composable
private fun AlarmsSection(state: StateResponse?, testing: Boolean, locale: Locale, onAction: (SettingsAction) -> Unit) {
    val c = DiaTheme.colors
    val th = state?.thresholds
    if (th != null) {
        val u = unitsOf(state.displayUnits)
        fun g(v: Int) = formatGlucose(v, u, locale)
        Line(stringResource(R.string.set_th_urgent_low), "< " + g(th.urgentLow))
        Line(stringResource(R.string.set_th_low), "< " + g(th.low))
        Line(stringResource(R.string.set_th_high), "> " + g(th.high))
        Line(stringResource(R.string.set_th_urgent_high), "> " + g(th.urgentHigh))
        Text(stringResource(R.string.set_th_hint), color = c.textFaint, fontSize = 13.sp)
    }
    OutlinedButton(onClick = { onAction(SettingsAction.TestSound) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(if (testing) R.string.set_test_stop else R.string.set_test_sound), color = c.text)
    }
}

/** Which server, this phone, the xDrip+ phone, the version, and disconnecting. */
@Composable
private fun ConnectionSection(uiState: SettingsUiState, version: String, onDisconnect: () -> Unit) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val creds = uiState.creds
    val state = uiState.state
    Line(stringResource(R.string.set_server), creds.server.removePrefix("https://"))
    Line(
        stringResource(R.string.set_this_phone),
        creds.deviceName + " · " + when (creds.role) {
            "caregiver" -> stringResource(R.string.role_caregiver)
            "uploader" -> stringResource(R.string.role_uploader)
            else -> creds.role
        }
    )
    state?.uploader?.let { u ->
        val seen = if (u.mills > 0) formatClock(ctx, u.mills) else "—"
        Line(
            AlarmText.uploader(ctx, state),
            listOfNotNull(
                u.battery?.let {
                    "$it%"
                },
                stringResource(R.string.set_seen, seen)
            ).joinToString(" · ")
        )
    }
    Line(stringResource(R.string.set_version), version)
    OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.action_disconnect), color = c.low)
    }
}

/** "Rapid: NovoRapid ▾" — the family's insulin from the list, or any name typed in. */
@Composable
private fun InsulinPicker(type: InsulinType, chosen: String?, onChoose: (String) -> Unit) {
    val c = DiaTheme.colors
    var open by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(if (type == InsulinType.Basal) R.string.ins_basal else R.string.ins_rapid),
            color = c.textMuted,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        Box {
            TextButton(onClick = {
                open = true
            }) { Text((chosen ?: stringResource(R.string.set_insulin_choose)) + "  ▾", color = c.text) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Insulins.of(type).forEach { name ->
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        open = false
                        onChoose(name)
                    })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.set_insulin_other)) }, onClick = {
                    open = false
                    typing = true
                })
            }
        }
    }
    if (typing) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { typing = false },
            title = { Text(stringResource(R.string.set_insulin_other)) },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true)
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    typing = false
                    onChoose(name.trim())
                }) { Text(stringResource(R.string.tr_ok)) }
            },
            dismissButton = { TextButton(onClick = { typing = false }) { Text(stringResource(R.string.tr_cancel)) } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BasalTimeDialog(onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val state = rememberTimePickerState(21, 0, DateFormat.is24HourFormat(ctx))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_basal_add)) },
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text(stringResource(R.string.tr_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tr_cancel)) } }
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val c = DiaTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(22.dp))
            .border(1.dp, c.line, RoundedCornerShape(22.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = DiaTheme.colors.textMuted, fontSize = 13.sp)
}

@Composable
private fun Line(label: String, value: String) {
    val c = DiaTheme.colors
    // a long value ("до найближчого, рівно половина — вниз · 1") goes under its label:
    // side by side it squeezes the label into a column of syllables
    if (value.length > 18) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = c.textMuted, fontSize = 14.sp)
            Text(value, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.textMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    }
}

@Composable
private fun <T> Choice(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    val c = DiaTheme.colors
    BoxWithConstraints {
        // all in one row while every label fits its share whole (no word split, at most
        // two lines); else two to a row when they pair up; else one under another
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val style = LocalTextStyle.current.merge(
            TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        )
        val fitsIn = { per: Int ->
            val slot = with(density) { ((maxWidth - 6.dp - 4.dp * (per - 1)) / per - 8.dp).roundToPx() }.coerceAtLeast(1)
            options.all { (_, label) ->
                label.split(' ').all { measurer.measure(it, style).size.width <= slot } &&
                    measurer.measure(label, style, constraints = Constraints(maxWidth = slot)).lineCount <= 2
            }
        }
        val per = when {
            fitsIn(options.size) -> options.size
            options.size % 2 == 0 && fitsIn(2) -> 2 // three would leave one alone
            else -> 1
        }
        val item = @Composable { value: T, label: String, m: Modifier ->
            val on = value == selected
            Text(
                label,
                color = if (on) c.text else c.textMuted,
                fontSize = 13.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = m
                    .background(if (on) c.surfaceRaised else Color.Transparent, RoundedCornerShape(9.dp))
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onPick(value) })
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 4.dp, vertical = 12.dp)
            )
        }
        val box = Modifier.fillMaxWidth().background(c.background, RoundedCornerShape(12.dp)).padding(3.dp)
        Column(box, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            options.chunked(per).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { (value, label) -> item(value, label, Modifier.weight(1f)) }
                    repeat(per - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun Permission(label: String, ok: Boolean, onFix: () -> Unit) {
    val c = DiaTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (ok) {
            Text(stringResource(R.string.set_perm_ok), color = c.inRange, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        } else {
            TextButton(onClick = onFix) { Text(stringResource(R.string.set_perm_fix), color = c.high) }
        }
    }
}
