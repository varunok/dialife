package com.lumenarcanus.dialife.feature.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.lumenarcanus.dialife.data.AppRelease
import com.lumenarcanus.dialife.data.Credentials
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Where an update is: nothing yet, downloading (0..1), handed to Android, or failed with a reason. */
sealed interface UpdateStep {
    data object Idle : UpdateStep
    data class Downloading(val done: Float) : UpdateStep
    data object Installing : UpdateStep
    data class Failed(val reason: Reason) : UpdateStep

    enum class Reason { Network, Checksum, Installer }
}

/**
 * Fetches the APK from the family's server, checks its SHA-256 and hands it to Android's
 * installer. The first update asks the person to confirm (and to allow installs from DiaLife);
 * from then on, DiaLife having installed itself, Android 12+ may update it without asking.
 */
class UpdateInstaller(private val context: Context, private val http: OkHttpClient = client) {
    suspend fun install(c: Credentials, r: AppRelease) {
        steps.value = UpdateStep.Downloading(0f)
        val file = File(context.cacheDir, "update/dialife.apk")
        val ok = withContext(Dispatchers.IO) {
            try {
                download(c, r, file)
            } catch (e: IOException) {
                steps.value = UpdateStep.Failed(UpdateStep.Reason.Network)
                false
            }
        }
        if (!ok) return
        steps.value = UpdateStep.Installing
        withContext(Dispatchers.IO) {
            try {
                commit(file)
            } catch (e: IOException) {
                steps.value = UpdateStep.Failed(UpdateStep.Reason.Installer)
            } catch (e: SecurityException) {
                steps.value = UpdateStep.Failed(UpdateStep.Reason.Installer)
            }
        }
    }

    /** Downloads to [file]; false (and the step says why) when the file is not the one announced. */
    private fun download(c: Credentials, r: AppRelease, file: File): Boolean {
        file.parentFile?.mkdirs()
        val req = Request.Builder().url(c.server + "/api/app/apk").header("Authorization", "Bearer ${c.token}").build()
        val digest = MessageDigest.getInstance("SHA-256")
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val total = resp.body.contentLength().takeIf { it > 0 } ?: r.size
            resp.body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        steps.value = UpdateStep.Downloading((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        val sum = digest.digest().joinToString("") { "%02x".format(it) }
        if (!sum.equals(r.sha256, ignoreCase = true)) {
            file.delete()
            steps.value = UpdateStep.Failed(UpdateStep.Reason.Checksum)
            return false
        }
        return true
    }

    private fun commit(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("dialife.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = PendingIntent.getBroadcast(
                context,
                id,
                Intent(context, InstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(status.intentSender)
        }
    }

    companion object {
        private val steps = MutableStateFlow<UpdateStep>(UpdateStep.Idle)

        /** One update at a time, for the whole process: the card and the installer's answer share it. */
        val step: StateFlow<UpdateStep> = steps.asStateFlow()

        internal fun failed() {
            steps.value = UpdateStep.Failed(UpdateStep.Reason.Installer)
        }

        internal fun reset() {
            steps.value = UpdateStep.Idle
        }

        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

/** Android's installer answers here: asks the person to confirm, or says it failed. Success restarts the app. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") // getParcelableExtra(name, class) is API 33; minSdk is 31
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateInstaller.reset()
            else -> UpdateInstaller.failed() // cancelled, another key, no space…
        }
    }
}
