package com.lumenarcanus.dialife.ui.web

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.data.ApiError
import com.lumenarcanus.dialife.data.ApiException
import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.data.DiaLifeApi
import com.lumenarcanus.dialife.data.WebLoginInfo
import com.lumenarcanus.dialife.data.WebLoginLink
import com.lumenarcanus.dialife.data.WebSession
import com.lumenarcanus.dialife.data.parseWebLoginLink
import com.lumenarcanus.dialife.ui.theme.DiaTheme
import kotlinx.coroutines.launch

private enum class Unit_(val minutes: Int, val label: Int) { Min(1, R.string.web_unit_min), Hour(60, R.string.web_unit_hour), Day(24 * 60, R.string.web_unit_day) }

/**
 * "Web login": scan the code a browser shows, see which browser it is, allow
 * it for as long as chosen (or decline), and close any browser already in.
 */
@Composable
fun WebLoginScreen(creds: Credentials, onBack: () -> Unit, api: DiaLifeApi = DiaLifeApi()) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf<WebLoginLink?>(null) }
    var info by remember { mutableStateOf<WebLoginInfo?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var sessions by remember { mutableStateOf<List<WebSession>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    val notCode = stringResource(R.string.web_not_code)
    val expired = stringResource(R.string.web_expired)
    val failed = stringResource(R.string.err_network)
    LaunchedEffect(reload) { api.webSessions(creds).onSuccess { sessions = it } }
    BackHandler(onBack = onBack)

    fun scan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(ctx, options).startScan().addOnSuccessListener { b ->
            val l = parseWebLoginLink(b.rawValue.orEmpty())
            if (l == null) { note = notCode; return@addOnSuccessListener }
            note = null
            scope.launch {
                api.webInspect(creds, l)
                    .onSuccess { link = l; info = it }
                    .onFailure { e -> note = if ((e as? ApiException)?.error is ApiError.Server) expired else failed }
            }
        }
    }
    LaunchedEffect(Unit) { scan() } // opened to scan: go straight to the camera

    Column(
        Modifier.fillMaxSize().background(c.background).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.nav_back), color = c.textMuted) }
        Text(stringResource(R.string.web_title), color = c.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        note?.let { Text(it, color = c.high, fontSize = 14.sp) }
        val l = link
        val i = info
        if (l != null && i != null) {
            Approve(i, onDecide = { minutes ->
                scope.launch {
                    val r = if (minutes == null) api.webReject(creds, l) else api.webApprove(creds, l, minutes)
                    r.onSuccess {
                        note = if (minutes == null) ctx.getString(R.string.web_denied)
                        else ctx.getString(R.string.web_allowed, untilText(ctx, System.currentTimeMillis() + minutes * 60_000L))
                        link = null; info = null; reload++
                    }.onFailure { e -> note = if ((e as? ApiException)?.error is ApiError.Server) expired else failed }
                }
            })
        } else {
            Text(stringResource(R.string.web_scan_hint), color = c.textMuted, fontSize = 14.sp)
            Button(onClick = ::scan, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Text(stringResource(R.string.web_scan), fontSize = 15.sp) }
        }
        Sessions(sessions) { id -> scope.launch { api.webRevoke(creds, id).onSuccess { reload++ } } }
    }
}

private fun untilText(ctx: android.content.Context, ms: Long): String =
    android.text.format.DateUtils.formatDateTime(ctx, ms,
        android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_ABBREV_MONTH or android.text.format.DateUtils.FORMAT_NO_YEAR)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Approve(info: WebLoginInfo, onDecide: (Int?) -> Unit) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    var count by remember { mutableIntStateOf(7) }
    var text by remember { mutableStateOf("7") }
    var unit by remember { mutableStateOf(Unit_.Day) }
    val minutes = count * unit.minutes
    val tooLong = minutes > info.maxMinutes
    fun set(n: Int, u: Unit_ = unit) { count = n.coerceIn(1, 999); text = count.toString(); unit = u }
    Column(
        Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(22.dp)).border(1.dp, c.line, RoundedCornerShape(22.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.web_ask_title), color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.web_ask_who, info.browser, info.os), color = c.textMuted, fontSize = 13.sp)
        Text(stringResource(R.string.web_ask_body), color = c.textMuted, fontSize = 14.sp)
        Text(stringResource(R.string.web_duration), color = c.textMuted, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val less = stringResource(R.string.web_less)
            val more = stringResource(R.string.web_more)
            FilledTonalIconButton(onClick = { set(count - 1) }, modifier = Modifier.size(48.dp).semantics { contentDescription = less }) { Text("−", fontSize = 22.sp, color = c.text) }
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter(Char::isDigit).take(3); text.toIntOrNull()?.let { count = it.coerceAtLeast(1) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, color = c.text),
                modifier = Modifier.width(76.dp),
            )
            FilledTonalIconButton(onClick = { set(count + 1) }, modifier = Modifier.size(48.dp).semantics { contentDescription = more }) { Text("+", fontSize = 22.sp, color = c.text) }
        }
        Row(Modifier.fillMaxWidth().background(c.background, RoundedCornerShape(12.dp)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Unit_.entries.forEach { u ->
                val on = u == unit
                Text(
                    stringResource(u.label), color = if (on) c.text else c.textMuted, fontSize = 13.sp, textAlign = TextAlign.Center,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f).background(if (on) c.surfaceRaised else Color.Transparent, RoundedCornerShape(9.dp))
                        .selectable(selected = on, role = Role.Tab) { unit = u }.heightIn(min = 40.dp).padding(vertical = 10.dp),
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(30 to Unit_.Min, 2 to Unit_.Hour, 1 to Unit_.Day, 7 to Unit_.Day, 30 to Unit_.Day).forEach { (n, u) ->
                val label = when (u) {
                    Unit_.Min -> stringResource(R.string.web_quick_min, n)
                    Unit_.Hour -> stringResource(R.string.web_quick_hour, n)
                    Unit_.Day -> pluralStringResource(R.plurals.web_quick_day, n, n)
                }
                FilterChip(selected = count == n && unit == u, onClick = { set(n, u) }, label = { Text(label) })
            }
        }
        Text(
            if (tooLong) stringResource(R.string.web_too_long) else stringResource(R.string.web_until, untilText(ctx, System.currentTimeMillis() + minutes * 60_000L)),
            color = if (tooLong) c.high else c.text, fontSize = 13.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { onDecide(null) }, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp)) { Text(stringResource(R.string.web_deny), color = c.text) }
            Button(onClick = { onDecide(minutes) }, enabled = !tooLong, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp)) { Text(stringResource(R.string.web_allow)) }
        }
    }
}

@Composable
internal fun Sessions(sessions: List<WebSession>, onRevoke: (Long) -> Unit) {
    val c = DiaTheme.colors
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(22.dp)).border(1.dp, c.line, RoundedCornerShape(22.dp)).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.web_sessions), color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (sessions.isEmpty()) Text(stringResource(R.string.web_none), color = c.textMuted, fontSize = 13.sp)
        val never = stringResource(R.string.web_never)
        sessions.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("${s.browser} · ${s.os}", color = c.text, fontSize = 14.sp)
                    val seen = s.lastSeenAt?.let { android.text.format.DateUtils.getRelativeTimeSpanString(it).toString() } ?: never
                    Text(stringResource(R.string.web_session_line, s.by, untilText(ctx, s.expiresAt), seen), color = c.textMuted, fontSize = 12.sp)
                }
                OutlinedButton(onClick = { onRevoke(s.id) }) { Text(stringResource(R.string.web_logout), color = c.low) }
            }
        }
        if (sessions.isNotEmpty()) Text(stringResource(R.string.web_any_parent), color = c.textFaint, fontSize = 12.sp)
    }
}
