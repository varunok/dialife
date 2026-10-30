package com.lumenarcanus.dialife.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.feature.entry.InsulinSigns
import com.lumenarcanus.dialife.feature.entry.KindSign
import com.lumenarcanus.dialife.feature.entry.TreatmentKind
import com.lumenarcanus.dialife.feature.entry.kindLook

/**
 * The chart alone, sideways and edge to edge: on a small phone the card is too
 * small to read a night. Turns to landscape (either side) and back on close.
 */
@Composable
internal fun FullScreenChart(onClose: () -> Unit, content: @Composable () -> Unit) {
    val c = DiaTheme.colors
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    androidx.compose.runtime.DisposableEffect(activity) {
        val window = activity?.window
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        bars?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            bars?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }
    androidx.activity.compose.BackHandler(onBack = onClose)
    Box(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .displayCutoutPadding()
            .padding(8.dp)
    ) { content() }
}

@Composable
internal fun Header(
    deviceName: String,
    status: Int,
    onOpenStats: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWebLogin: () -> Unit
) {
    val c = DiaTheme.colors
    val live = status == R.string.status_live
    val offline = status == R.string.status_offline
    val blue = if (c.isDark) Color(0xFF7FB2FF) else Color(0xFF1F5FCC)
    val blueBg = if (c.isDark) Color(0xFF0E1C33) else Color(0xFFE3EDFF)
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, color = c.text)
        Spacer(Modifier.size(10.dp))
        Row(
            Modifier
                .background(
                    if (live) {
                        c.inRangeContainer
                    } else if (offline) {
                        blueBg
                    } else {
                        c.highContainer
                    },
                    CircleShape
                )
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                Modifier.size(6.dp).background(
                    if (live) {
                        c.inRange
                    } else if (offline) {
                        blue
                    } else {
                        c.high
                    },
                    CircleShape
                )
            )
            Text(
                stringResource(status),
                color = if (live) {
                    c.onInRangeContainer
                } else if (offline) {
                    blue
                } else {
                    c.high
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.weight(1f))
        Box {
            TextButton(onClick = { menu = true }) {
                Text(deviceName.ifBlank { stringResource(R.string.action_settings) }, color = c.textMuted, fontSize = 13.sp)
            }
            // A6 brings the real settings screen; until then: who this phone is, and a way out
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (deviceName.isNotBlank()) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.connected_as, deviceName)) }, onClick = {}, enabled = false)
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.stats_title)) }, onClick = {
                    menu = false
                    onOpenStats()
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.log_title)) }, onClick = {
                    menu = false
                    onOpenLog()
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.web_title)) }, onClick = {
                    menu = false
                    onOpenWebLogin()
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_settings)) }, onClick = {
                    menu = false
                    onOpenSettings()
                })
            }
        }
    }
}

@Composable
internal fun Chip(text: String, background: Color, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.background(background, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

@Composable
internal fun Dot() {
    Box(Modifier.size(3.dp).background(DiaTheme.colors.textFaint, CircleShape))
}

/** What a button needs besides its label when the sign sits beside it: padding, sign, gap. */
internal val ACTION_INNER = 8.dp + 22.dp + 6.dp

@Composable
internal fun ActionButton(kind: TreatmentKind, modifier: Modifier, small: Boolean = false, stacked: Boolean = false, onClick: () -> Unit) {
    val look = kindLook(kind)
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(
            min = if (small) {
                46.dp
            } else if (stacked) {
                66.dp
            } else {
                56.dp
            }
        ),
        shape = RoundedCornerShape(if (small) 15.dp else 18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = look.fill, contentColor = look.ink),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = if (stacked) 6.dp else 0.dp)
    ) {
        val sign = 20.dp
        val signs = @Composable {
            if (kind == TreatmentKind.Insulin) {
                InsulinSigns(sign, look.fill)
            } else {
                KindSign(look.glyph, look.ink, sign)
            }
        }
        // the label stays whole: past what the layout allows (a very large font), it shrinks rather than being cut
        val label = @Composable { m: Modifier ->
            androidx.compose.foundation.text.BasicText(
                stringResource(look.name),
                modifier = m,
                style = androidx.compose.material3.LocalTextStyle.current.merge(
                    androidx.compose.ui.text.TextStyle(color = look.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                ),
                maxLines = 1,
                softWrap = false,
                autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(
                    minFontSize = 9.sp,
                    maxFontSize = 14.sp,
                    stepSize = 0.5.sp
                )
            )
        }
        if (stacked) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                signs()
                label(Modifier)
            }
        } else {
            signs()
            Spacer(Modifier.width(6.dp))
            label(Modifier.weight(1f, fill = false))
        }
    }
}
