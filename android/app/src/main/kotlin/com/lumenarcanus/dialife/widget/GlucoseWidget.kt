package com.lumenarcanus.dialife.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.layout.height
import androidx.glance.layout.fillMaxWidth
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.layout.ContentScale
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.lumenarcanus.dialife.MainActivity
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.data.AlarmView
import com.lumenarcanus.dialife.data.CredentialStore
import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.data.StateResponse
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.lumenarcanus.dialife.live.AlarmText
import com.lumenarcanus.dialife.live.LiveCache
import com.lumenarcanus.dialife.live.Notifications
import com.lumenarcanus.dialife.liveRepository
import com.lumenarcanus.dialife.ui.home.STALE_MIN
import com.lumenarcanus.dialife.ui.home.Units
import com.lumenarcanus.dialife.ui.home.formatDelta
import com.lumenarcanus.dialife.ui.home.formatGlucose
import com.lumenarcanus.dialife.ui.home.unitsOf
import kotlinx.coroutines.flow.first

/** Everything the widget shows, already worded: Glance renders, it does not compute. */
private data class WidgetData(
    val value: String?, // "229" or "12,7"; null: no reading yet / not connected
    val unit: String,
    val arrow: String,
    val delta: String?,
    val time: String?, // "о 10:23"
    val state: String?, // "В діапазоні · 80–250"
    val band: Int, // -1 low, 0 in range, 1 high
    val stale: Boolean,
    val alarm: String?, // a ringing episode: "Низький цукор"
    val description: String,
    val forecast: String? = null, // "за 30 хв ≈ 243"
    val chart: android.graphics.Bitmap? = null, // the last 3 h
)

/**
 * The home screen widget: the number at a glance, red while an alarm rings.
 * Pushed on every reading (LiveService) and every watchdog tick, so "stale"
 * shows up even when nothing arrives; it never polls on its own.
 */
class GlucoseWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(STRIP, WIDE_STRIP, SQUARE, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = context.liveRepository
        val creds = repo.credentials ?: CredentialStore(context).credentials.first()
        // the process may have been started just for the widget: show the last known state meanwhile
        val snapshot = creds?.let { LiveCache(context.filesDir).load(it.server, it.deviceName) }
        val cached = snapshot?.state
        val cachedHistory = snapshot?.history
        provideContent {
            // Glance keeps this session alive and only recomposes on update: read live data here,
            // not once above, or the widget keeps showing what it saw first
            val live by repo.live.collectAsState()
            Content(build(context, live.state ?: cached, creds, live.history ?: cachedHistory))
        }
    }

    /** What the widget picker (home screen and lock screen hub) shows before it is placed. */
    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val unit = context.getString(R.string.unit_mgdl)
        val sample = WidgetData(
            value = "183", unit = unit, arrow = "→", delta = "+4", time = context.getString(R.string.widget_at, "22:47"),
            state = context.getString(R.string.state_in_range, "80–250"), band = 0, stale = false, alarm = null,
            description = context.getString(R.string.widget_description),
        )
        provideContent { Content(sample) }
    }

    private fun build(context: Context, state: StateResponse?, creds: Credentials?, history: com.lumenarcanus.dialife.data.History? = null): WidgetData {
        val units = unitsOf(state?.displayUnits ?: creds?.displayUnits)
        val unit = context.getString(if (units == Units.MgDl) R.string.unit_mgdl else R.string.unit_mmol)
        val bg = state?.bg
        if (creds == null || bg == null) {
            val msg = context.getString(if (creds == null) R.string.widget_not_connected else R.string.home_no_data)
            return WidgetData(null, unit, "", null, null, msg, 0, true, null, msg)
        }
        val locale = context.resources.configuration.locales[0]
        val th = state.thresholds
        val band = when {
            bg.sgv < th.low -> -1
            bg.sgv > th.high -> 1
            else -> 0
        }
        val value = formatGlucose(bg.sgv, units, locale)
        val stale = System.currentTimeMillis() - bg.mills >= STALE_MIN * 60_000L
        val time = context.getString(R.string.widget_at, AlarmText.clock(context, bg.mills))
        val bounds = formatGlucose(th.low, units, locale) + "–" + formatGlucose(th.high, units, locale)
        val stateText = when {
            stale -> context.getString(R.string.status_stale)
            band < 0 -> context.getString(R.string.state_low, formatGlucose(th.low, units, locale))
            band > 0 -> context.getString(R.string.state_high, formatGlucose(th.high, units, locale))
            else -> context.getString(R.string.state_in_range, bounds)
        }
        val ringing: AlarmView? = state.alarms.firstOrNull { it.state == "firing" }
        val alarm = ringing?.let { AlarmText.kind(context, it) }
        val delta = bg.delta?.let { formatDelta(it, units, locale) }
        val forecast = state.ar2?.predicted?.lastOrNull()?.let { context.getString(R.string.forecast_30, formatGlucose(it.sgv, units, locale)) }
        val dark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        return WidgetData(
            value, unit, Notifications.arrowOf(bg.direction), delta, time, stateText, band, stale, alarm,
            description = listOfNotNull(alarm, "$value $unit", delta, time, stateText, forecast).joinToString(", "),
            forecast = forecast,
            chart = Sparkline.render(history, th, System.currentTimeMillis(), dark),
        )
    }

    companion object {
        private val STRIP = DpSize(180.dp, 48.dp)
        private val WIDE_STRIP = DpSize(280.dp, 48.dp)
        private val SQUARE = DpSize(140.dp, 140.dp)
        private val LARGE = DpSize(260.dp, 180.dp) // lock screen hub, a stretched home widget

        /** Publishes the picker previews (Android 15+); the system rate-limits it, so once per app start is plenty. */
        suspend fun publishPreviews(context: Context) {
            if (android.os.Build.VERSION.SDK_INT >= 35) {
                runCatching { androidx.glance.appwidget.GlanceAppWidgetManager(context).setWidgetPreviews(GlucoseWidgetReceiver::class) }
            }
        }

        /** Redraw every placed widget; cheap when none is placed. */
        suspend fun refresh(context: Context) {
            runCatching { GlucoseWidget().updateAll(context) }
        }
    }
}

class GlucoseWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GlucoseWidget()
}

// Same tokens as the app (ui/theme/Color.kt), light and dark.
private val Bg = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF121720))
private val AlarmBg = ColorProvider(day = Color(0xFFFCE4EA), night = Color(0xFF22050D))
private val TextMain = ColorProvider(day = Color(0xFF0F1622), night = Color(0xFFECEFF4))
private val TextMuted = ColorProvider(day = Color(0xFF5B6577), night = Color(0xFF8B95A7))
private val InRange = ColorProvider(day = Color(0xFF0B7D73), night = Color(0xFF4FD1C5))
private val High = ColorProvider(day = Color(0xFFB86E00), night = Color(0xFFF5A524))
private val Low = ColorProvider(day = Color(0xFFD12E52), night = Color(0xFFFF5C7A))

@Composable
private fun Content(d: WidgetData) {
    val accent = when {
        d.stale -> TextMuted
        d.band < 0 -> Low
        d.band > 0 -> High
        else -> InRange
    }
    val large = LocalSize.current.height >= 170.dp && LocalSize.current.width >= 250.dp
    val square = LocalSize.current.height >= 110.dp
    val wide = LocalSize.current.width >= 260.dp // room for a mini chart in the strip
    Column(
        GlanceModifier
            .fillMaxSize()
            .background(if (d.alarm != null) AlarmBg else Bg)
            .cornerRadius(22.dp)
            .padding(horizontal = 14.dp, vertical = if (square) 12.dp else 6.dp)
            .clickable(actionStartActivity<MainActivity>())
            .semantics { contentDescription = d.description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (d.value == null) {
            Text(d.state.orEmpty(), style = TextStyle(color = TextMuted, fontSize = 14.sp))
            return@Column
        }
        if (large) {
            // big enough to read from across the room or half asleep
            Text(d.alarm ?: d.state.orEmpty(), maxLines = 1, style = TextStyle(color = if (d.alarm != null) Low else accent, fontSize = 18.sp, fontWeight = FontWeight.Medium))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
                Text(d.value, style = TextStyle(color = if (d.stale) TextMuted else TextMain, fontSize = 84.sp, fontWeight = FontWeight.Medium))
                Spacer(GlanceModifier.width(8.dp))
                Text(d.arrow, style = TextStyle(color = accent, fontSize = 52.sp, fontWeight = FontWeight.Bold))
                Spacer(GlanceModifier.defaultWeight())
                Column(horizontalAlignment = Alignment.End) {
                    d.delta?.let { Text(it, style = TextStyle(color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Medium)) }
                    d.time?.let { Text(it, style = TextStyle(color = TextMuted, fontSize = 15.sp)) }
                    d.forecast?.let { Text(it, maxLines = 1, style = TextStyle(color = TextMuted, fontSize = 15.sp)) }
                }
            }
            d.chart?.let {
                Spacer(GlanceModifier.height(6.dp))
                Image(ImageProvider(it), null, contentScale = ContentScale.FillBounds, modifier = GlanceModifier.fillMaxWidth().defaultWeight())
            }
        } else if (square) {
            Text(d.alarm ?: d.state.orEmpty(), maxLines = 1, style = TextStyle(color = if (d.alarm != null) Low else accent, fontSize = 13.sp, fontWeight = FontWeight.Medium))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(d.value, style = TextStyle(color = if (d.stale) TextMuted else TextMain, fontSize = 52.sp, fontWeight = FontWeight.Medium))
                Spacer(GlanceModifier.width(6.dp))
                Text(d.arrow, style = TextStyle(color = accent, fontSize = 34.sp, fontWeight = FontWeight.Bold))
            }
            Text(listOfNotNull(d.delta, d.time).joinToString(" · "), maxLines = 1, style = TextStyle(color = TextMuted, fontSize = 13.sp))
            d.forecast?.let { Text(it, maxLines = 1, style = TextStyle(color = TextMuted, fontSize = 12.sp)) }
            d.chart?.let {
                Spacer(GlanceModifier.height(6.dp))
                Image(
                    provider = ImageProvider(it),
                    contentDescription = null, // the description above already says it all
                    contentScale = ContentScale.FillBounds,
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.value, style = TextStyle(color = if (d.stale) TextMuted else TextMain, fontSize = 28.sp, fontWeight = FontWeight.Medium))
                Spacer(GlanceModifier.width(4.dp))
                Text(d.arrow, style = TextStyle(color = accent, fontSize = 22.sp, fontWeight = FontWeight.Bold))
                Spacer(GlanceModifier.width(10.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text(d.alarm ?: d.state.orEmpty(), maxLines = 1, style = TextStyle(color = if (d.alarm != null) Low else accent, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                    Text(listOfNotNull(d.delta, d.time).joinToString(" · "), maxLines = 1, style = TextStyle(color = TextMuted, fontSize = 12.sp))
                }
                if (wide) d.chart?.let {
                    Spacer(GlanceModifier.width(8.dp))
                    Image(ImageProvider(it), null, contentScale = ContentScale.FillBounds, modifier = GlanceModifier.width(110.dp).height(36.dp))
                }
            }
        }
    }
}
