package com.lumenarcanus.dialife.feature.alarms

/** What can be done with an alarm from a button: take it, quiet it, ask for help, come, close it. */
interface AlarmActions {
    /** [action]: one of ActionReceiver's (handle, quiet, ok, help, coming, resolve). */
    suspend fun act(action: String, alarmId: Long): Result<Unit>
}

/** The alarm sound; -1 is the settings' test. */
interface AlarmSound {
    fun start(alarmId: Long)

    /** Stops if [alarmId] is the one sounding (0: whatever sounds). */
    fun stop(alarmId: Long = 0)
}
