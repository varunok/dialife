package com.lumenarcanus.dialife.feature.alarms

/** What can be done with an alarm from a button: take it, quiet it, ask for help, come, close it. */
interface AlarmActions {
    /** [action]: one of ActionReceiver's (handle, quiet, ok, help, coming, resolve). */
    suspend fun act(action: String, alarmId: Long): Result<Unit>
}

/** The alarm sound; -1 is the settings' test. */
interface AlarmSound {
    /** [warning]: a forecast, sounding briefly; else an alarm, until answered or the server rings again. */
    fun start(alarmId: Long, warning: Boolean = false)

    /** Stops if [alarmId] is the one sounding (0: whatever sounds). */
    fun stop(alarmId: Long = 0)
}
