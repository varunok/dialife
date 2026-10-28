package com.lumenarcanus.dialife.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import com.lumenarcanus.dialife.BuildConfig
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.ui.treatment.formatAmount
import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.data.Prefs
import com.lumenarcanus.dialife.data.StateResponse
import com.lumenarcanus.dialife.data.ThemeChoice
import com.lumenarcanus.dialife.live.AlarmText
import com.lumenarcanus.dialife.ui.home.BackgroundSetup
import com.lumenarcanus.dialife.ui.home.Units
import com.lumenarcanus.dialife.ui.home.formatGlucose
import com.lumenarcanus.dialife.ui.home.unitsOf
import com.lumenarcanus.dialife.ui.theme.DiaTheme
import kotlinx.coroutines.delay

/** This phone's settings: how it looks, alarms (view only in v1), permissions, connection. */
@Composable
fun SettingsScreen(
    creds: Credentials,
    state: StateResponse?,
    setup: BackgroundSetup,
    onFixNotifications: () -> Unit,
    onFixBattery: () -> Unit,
    onFixFullScreen: () -> Unit,
    onTestSound: (Boolean) -> Unit, // true: start, false: stop
    onDisconnect: () -> Unit,
    onBack: () -> Unit,
    onOpenDose: () -> Unit = {},
) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val locale = ctx.resources.configuration.locales[0]
    var confirmDisconnect by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    LaunchedEffect(testing) {
        if (testing) {
            onTestSound(true)
            delay(5_000)
            onTestSound(false)
            testing = false
        }
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(stringResource(R.string.set_disconnect_title)) },
            text = { Text(stringResource(R.string.set_disconnect_body)) },
            confirmButton = { TextButton(onClick = { confirmDisconnect = false; onDisconnect() }) { Text(stringResource(R.string.action_disconnect), color = c.low) } },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(R.string.tr_cancel)) } },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.nav_back), color = c.textMuted) }
        Text(stringResource(R.string.action_settings), color = c.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)

        Section(stringResource(R.string.set_look)) {
            Label(stringResource(R.string.set_units))
            val serverUnits = if (state?.displayUnits?.startsWith("mmol") == true || (state == null && creds.displayUnits.startsWith("mmol"))) Units.Mmol else Units.MgDl
            Choice(
                listOf(
                    null to stringResource(R.string.set_units_server, stringResource(if (serverUnits == Units.MgDl) R.string.unit_mgdl else R.string.unit_mmol)),
                    Units.MgDl to stringResource(R.string.unit_mgdl),
                    Units.Mmol to stringResource(R.string.unit_mmol),
                ),
                Prefs.units,
            ) { Prefs.chooseUnits(it) }
            Label(stringResource(R.string.set_theme))
            Choice(
                listOf(
                    ThemeChoice.System to stringResource(R.string.set_system),
                    ThemeChoice.Light to stringResource(R.string.set_theme_light),
                    ThemeChoice.Dark to stringResource(R.string.set_theme_dark),
                ),
                Prefs.theme,
            ) { Prefs.chooseTheme(it) }
            Label(stringResource(R.string.set_language))
            val current = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-').ifBlank { "" }
            Choice(
                listOf("" to stringResource(R.string.set_system), "uk" to "Українська", "en" to "English", "es" to "Español"),
                current,
            ) { tag -> AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.set_meter_line), color = c.text, fontSize = 14.sp)
                    Text(stringResource(R.string.set_meter_line_sub), color = c.textMuted, fontSize = 12.sp)
                }
                androidx.compose.material3.Switch(checked = Prefs.meterLine, onCheckedChange = { Prefs.chooseMeterLine(it) })
            }
        }

        Section(stringResource(R.string.set_insulins)) {
            InsulinPicker(com.lumenarcanus.dialife.data.InsulinType.Rapid)
            InsulinPicker(com.lumenarcanus.dialife.data.InsulinType.Basal)
            Label(stringResource(R.string.set_basal_times))
            var adding by remember { mutableStateOf(false) }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Prefs.basalTimes.forEach { m ->
                    androidx.compose.material3.InputChip(
                        selected = false,
                        onClick = { Prefs.chooseBasalTimes(Prefs.basalTimes - m); com.lumenarcanus.dialife.live.BasalReminder.schedule(ctx) },
                        label = { Text(String.format(java.util.Locale.ROOT, "%02d:%02d", m / 60, m % 60)) },
                        trailingIcon = { Text("✕", color = c.textMuted) },
                    )
                }
                androidx.compose.material3.AssistChip(onClick = { adding = true }, label = { Text(stringResource(R.string.set_basal_add)) })
            }
            if (adding) {
                BasalTimeDialog(onPick = { m ->
                    adding = false
                    Prefs.chooseBasalTimes(Prefs.basalTimes + m)
                    com.lumenarcanus.dialife.live.BasalReminder.schedule(ctx)
                }, onDismiss = { adding = false })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.set_basal_remind), color = c.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(
                    checked = Prefs.basalReminder,
                    enabled = Prefs.basalTimes.isNotEmpty(),
                    onCheckedChange = { Prefs.chooseBasalReminder(it); com.lumenarcanus.dialife.live.BasalReminder.schedule(ctx) },
                )
            }
        }

        Section(stringResource(R.string.dose_title)) {
            val d = state?.dose ?: com.lumenarcanus.dialife.data.DoseSettings()
            Line(stringResource(R.string.dose_ratio_short), stringResource(R.string.meal_ratio, d.ratio))
            Line(stringResource(R.string.dose_rounding), com.lumenarcanus.dialife.ui.meal.ruleName(d.rule) + " · " + formatAmount(d.step, locale))
            Line(stringResource(R.string.dose_limits_short), formatAmount(d.maxRapid, locale) + " " + stringResource(R.string.unit_insulin) + " · " + d.maxCarbs + " " + stringResource(R.string.unit_carbs))
            OutlinedButton(onClick = onOpenDose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.dose_open), color = c.text)
            }
        }

        Section(stringResource(R.string.set_alarms)) {
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
            OutlinedButton(onClick = { testing = !testing }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(if (testing) R.string.set_test_stop else R.string.set_test_sound), color = c.text)
            }
        }

        Section(stringResource(R.string.set_permissions)) {
            Permission(stringResource(R.string.set_perm_notifications), !setup.notifications, onFixNotifications)
            Permission(stringResource(R.string.set_perm_battery), !setup.battery, onFixBattery)
            Permission(stringResource(R.string.set_perm_fullscreen), !setup.fullScreen, onFixFullScreen)
        }

        Section(stringResource(R.string.set_connection)) {
            Line(stringResource(R.string.set_server), creds.server.removePrefix("https://"))
            Line(stringResource(R.string.set_this_phone), creds.deviceName + " · " + creds.role)
            state?.uploader?.let { u ->
                val seen = if (u.mills > 0) AlarmText.clock(ctx, u.mills) else "—"
                Line(AlarmText.uploader(ctx, state), listOfNotNull(u.battery?.let { "$it%" }, stringResource(R.string.set_seen, seen)).joinToString(" · "))
            }
            Line(stringResource(R.string.set_version), BuildConfig.VERSION_NAME)
            OutlinedButton(onClick = { confirmDisconnect = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_disconnect), color = c.low)
            }
        }
        Spacer(Modifier.heightIn(min = 12.dp))
    }
}

/** "Rapid: NovoRapid ▾" — the family's insulin from the list, or any name typed in. */
@Composable
private fun InsulinPicker(type: com.lumenarcanus.dialife.data.InsulinType) {
    val c = DiaTheme.colors
    var open by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(if (type == com.lumenarcanus.dialife.data.InsulinType.Basal) R.string.ins_basal else R.string.ins_rapid),
            color = c.textMuted, fontSize = 14.sp, modifier = Modifier.weight(1f),
        )
        androidx.compose.foundation.layout.Box {
            TextButton(onClick = { open = true }) { Text((Prefs.nameOf(type) ?: stringResource(R.string.set_insulin_choose)) + "  ▾", color = c.text) }
            androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                com.lumenarcanus.dialife.data.Insulins.of(type).forEach { name ->
                    androidx.compose.material3.DropdownMenuItem(text = { Text(name) }, onClick = { open = false; Prefs.chooseInsulin(type, name) })
                }
                androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.set_insulin_other)) }, onClick = { open = false; typing = true })
            }
        }
    }
    if (typing) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { typing = false },
            title = { Text(stringResource(R.string.set_insulin_other)) },
            text = { androidx.compose.material3.OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { typing = false; Prefs.chooseInsulin(type, name.trim()) }) { Text(stringResource(R.string.tr_ok)) }
            },
            dismissButton = { TextButton(onClick = { typing = false }) { Text(stringResource(R.string.tr_cancel)) } },
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun BasalTimeDialog(onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val state = androidx.compose.material3.rememberTimePickerState(21, 0, android.text.format.DateFormat.is24HourFormat(ctx))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_basal_add)) },
        text = { androidx.compose.material3.TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text(stringResource(R.string.tr_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tr_cancel)) } },
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
        verticalArrangement = Arrangement.spacedBy(10.dp),
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.textMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    }
}

@Composable
private fun <T> Choice(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    val c = DiaTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.background, RoundedCornerShape(12.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Text(
                label,
                color = if (on) c.text else c.textMuted,
                fontSize = 13.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier
                    .weight(1f)
                    .background(if (on) c.surfaceRaised else Color.Transparent, RoundedCornerShape(9.dp))
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onPick(value) })
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 4.dp, vertical = 12.dp),
            )
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
