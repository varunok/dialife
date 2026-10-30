package com.lumenarcanus.dialife.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.BannerButton
import com.lumenarcanus.dialife.core.ui.formatClock
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.feature.alarms.AlarmText

/** No reading for a while: the chain is broken somewhere, and here is where to look. */
@Composable
internal fun StaleBanner(ui: HomeUi, state: HomeState) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val bg = ui.state?.bg ?: return
    Column(
        Modifier.fillMaxWidth().background(c.highContainer, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            pluralStringResource(R.plurals.stale_title, state.ageMin, state.ageMin),
            color = c.high,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(stringResource(R.string.stale_last, formatClock(ctx, bg.mills)), color = c.text, fontSize = 14.sp)
        ui.state.uploader?.let { u ->
            if (u.mills > 0) {
                Text(
                    stringResource(
                        R.string.stale_phone,
                        AlarmText.uploader(ctx, ui.state),
                        formatClock(ctx, u.mills),
                        u.battery?.let { "$it%" } ?: "—"
                    ),
                    color = c.textMuted,
                    fontSize = 14.sp
                )
            }
        }
        Text(stringResource(R.string.stale_check), color = c.textMuted, fontSize = 14.sp)
    }
}

/** This phone cannot reach the server: its own problem, so a different colour from "no data". */
@Composable
internal fun OfflineBanner(ui: HomeUi, onRetry: () -> Unit) {
    val c = DiaTheme.colors
    val blue = if (c.isDark) Color(0xFF7FB2FF) else Color(0xFF1F5FCC)
    val blueBg = if (c.isDark) Color(0xFF0E1C33) else Color(0xFFE3EDFF)
    Column(
        Modifier.fillMaxWidth().background(blueBg, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(stringResource(R.string.conn_lost_title), color = blue, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.offline_body), color = c.text, fontSize = 14.sp)
        Text(stringResource(R.string.offline_check), color = c.textMuted, fontSize = 14.sp)
        BannerButton(stringResource(R.string.offline_retry), blue, c.background, onRetry)
    }
}

@Composable
internal fun SetupCard(setup: BackgroundSetup, onNotifications: () -> Unit, onBattery: () -> Unit, onFullScreen: () -> Unit) {
    val c = DiaTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.highContainer, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(stringResource(R.string.bg_card_title), color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.bg_card_body), color = c.textMuted, fontSize = 14.sp)
        if (setup.notifications) SetupButton(stringResource(R.string.bg_card_notifications), onNotifications)
        if (setup.battery) SetupButton(stringResource(R.string.bg_card_battery), onBattery)
        if (setup.fullScreen) SetupButton(stringResource(R.string.bg_card_fullscreen), onFullScreen)
    }
}

@Composable
internal fun SetupButton(text: String, onClick: () -> Unit) {
    val c = DiaTheme.colors
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = c.high, contentColor = c.background)
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun Empty(loading: Boolean) {
    val c = DiaTheme.colors
    Column(
        Modifier.fillMaxWidth().height(180.dp).padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
    ) {
        Text(
            stringResource(if (loading) R.string.home_loading else R.string.home_no_data),
            color = c.text,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (!loading) Text(stringResource(R.string.home_no_data_hint), color = c.textMuted, fontSize = 15.sp)
    }
}
