package com.lumenarcanus.dialife.live

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log

/**
 * Plays the alarm like an alarm clock: the alarm stream (heard in Do Not
 * Disturb and on silent), looping, with vibration, loud enough to wake.
 * One alarm sounds at a time; it stops on its own after a while because the
 * server rings again if nobody answered.
 */
class Ringer(context: Context) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val vibrator = app.getSystemService(VibratorManager::class.java).defaultVibrator
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var wake: PowerManager.WakeLock? = null
    private var restoreVolume: Int? = null
    private val timeout = Runnable { stopNow() }

    /** Which alarm is sounding, or 0. */
    var ringingId: Long = 0
        private set

    fun start(alarmId: Long) {
        main.post {
            stopNow()
            ringingId = alarmId
            wake = app.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dialife:alarm")
                .apply { acquire(MAX_RING_MS + 10_000) }
            // never let a turned-down alarm volume sleep through a hypo
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val cur = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            val floor = (max * 0.8).toInt()
            if (cur < floor) {
                runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, floor, 0) }
                    .onSuccess { restoreVolume = cur }
            }
            val uri = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = try {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    setDataSource(app, uri)
                    isLooping = true
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                Log.e("DiaLifeAlarm", "cannot play alarm sound", e)
                null
            }
            val pattern = VibrationEffect.createWaveform(longArrayOf(0, 800, 400, 800, 1200), 0)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(pattern, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_ALARM))
            } else {
                // Android 12: alarm usage through the audio attributes, so Do Not Disturb lets it through
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build())
            }
            main.postDelayed(timeout, MAX_RING_MS)
        }
    }

    /** Stops if [alarmId] is the one sounding (0: whatever sounds). */
    fun stop(alarmId: Long = 0) {
        main.post { if (alarmId == 0L || alarmId == ringingId) stopNow() }
    }

    private fun stopNow() {
        main.removeCallbacks(timeout)
        player?.runCatching { stop(); release() }
        player = null
        vibrator.cancel()
        restoreVolume?.let { v -> runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) } }
        restoreVolume = null
        wake?.takeIf { it.isHeld }?.release()
        wake = null
        ringingId = 0
    }

    companion object {
        const val MAX_RING_MS = 3 * 60_000L
    }
}
