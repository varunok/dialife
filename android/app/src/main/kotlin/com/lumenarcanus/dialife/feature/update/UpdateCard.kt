package com.lumenarcanus.dialife.feature.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumenarcanus.dialife.BuildConfig
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.BannerButton
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.data.AppRelease
import com.lumenarcanus.dialife.liveRepository
import kotlinx.coroutines.launch

/** On home: a newer DiaLife on the family's server, with what is new and "Install". */
@Composable
fun UpdateSection() {
    val ctx = LocalContext.current
    val live by ctx.liveRepository.live.collectAsStateWithLifecycle()
    val release = live.state?.app?.takeIf { it.code > BuildConfig.VERSION_CODE } ?: return
    val step by UpdateInstaller.step.collectAsState()
    val scope = rememberCoroutineScope()
    UpdateCard(release, BuildConfig.VERSION_NAME, step) {
        val pm = ctx.packageManager
        if (!pm.canRequestPackageInstalls()) {
            // once: Android's own switch "allow from this source"
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return@UpdateCard
        }
        val creds = ctx.liveRepository.credentials ?: return@UpdateCard
        scope.launch { UpdateInstaller(ctx.applicationContext).install(creds, release) }
    }
}

/** Only draws: the new version against [current], what is new, the download's progress, why it failed. */
@Composable
fun UpdateCard(r: AppRelease, current: String, step: UpdateStep, onInstall: () -> Unit) {
    val c = DiaTheme.colors
    Column(
        Modifier.fillMaxWidth().background(c.inRangeContainer, RoundedCornerShape(18.dp)).border(1.dp, c.inRange, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.update_title, r.name), color = c.inRange, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.update_current, current), color = c.textMuted, fontSize = 13.sp)
        if (r.notes.isNotBlank()) Text(r.notes, color = c.text, fontSize = 14.sp, lineHeight = 20.sp)
        when (step) {
            is UpdateStep.Downloading -> {
                Text(stringResource(R.string.update_downloading, (step.done * 100).toInt()), color = c.text, fontSize = 14.sp)
                LinearProgressIndicator(progress = { step.done }, modifier = Modifier.fillMaxWidth(), color = c.inRange)
            }
            UpdateStep.Installing -> Text(stringResource(R.string.update_installing), color = c.text, fontSize = 14.sp)
            is UpdateStep.Failed -> {
                Text(
                    stringResource(
                        when (step.reason) {
                            UpdateStep.Reason.Network -> R.string.update_failed_network
                            UpdateStep.Reason.Checksum -> R.string.update_failed_checksum
                            UpdateStep.Reason.Installer -> R.string.update_failed_installer
                        }
                    ),
                    color = c.high,
                    fontSize = 14.sp
                )
                BannerButton(stringResource(R.string.update_retry), c.inRange, c.background, onInstall)
            }
            UpdateStep.Idle -> BannerButton(stringResource(R.string.update_install), c.inRange, c.background, onInstall)
        }
    }
}
