package com.lumenarcanus.dialife.live

import android.content.ComponentName
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Notifications and timers from before the update still find someone to answer them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OldComponentNamesTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()

    private fun component(name: String) = ComponentName(ctx.packageName, "com.lumenarcanus.dialife.$name")

    @Test
    fun theOldReceiversAreThere() {
        listOf("live.ActionReceiver", "live.EatReminderReceiver").forEach { name ->
            assertEquals(component(name).className, ctx.packageManager.getReceiverInfo(component(name), 0).name)
        }
    }

    @Test
    fun theOldAlarmScreenOpensTheNewOne() {
        val info = ctx.packageManager.getActivityInfo(component("ui.alarm.AlarmActivity"), 0)
        assertEquals("com.lumenarcanus.dialife.feature.alarms.AlarmActivity", info.targetActivity)
    }
}
