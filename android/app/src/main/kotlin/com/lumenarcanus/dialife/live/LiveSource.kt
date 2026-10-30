package com.lumenarcanus.dialife.live

import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.data.Treatment
import kotlinx.coroutines.flow.StateFlow

/**
 * The live connection as the screens see it: the state and history as they
 * arrive, entries not sent yet, and changes that reach the server when the
 * phone is online. [LiveRepository] is the one the app uses.
 */
interface LiveSource {
    val live: StateFlow<Live>

    /** Food/insulin changes made here that the server has not confirmed yet. */
    val pending: StateFlow<List<PendingOp>>

    val credentials: Credentials?

    /** This phone's device id: who "I" am in handled_by_id / coming_by_id. */
    val myId: Long

    fun saveTreatment(t: Treatment)

    fun deleteTreatment(id: String)

    /** Skip the back-off: the network came back or someone asked. */
    fun reconnectNow()
}
