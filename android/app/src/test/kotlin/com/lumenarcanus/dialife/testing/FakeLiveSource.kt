package com.lumenarcanus.dialife.testing

import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.data.StateResponse
import com.lumenarcanus.dialife.data.Treatment
import com.lumenarcanus.dialife.live.Live
import com.lumenarcanus.dialife.live.LiveSource
import com.lumenarcanus.dialife.live.PendingOp
import kotlinx.coroutines.flow.MutableStateFlow

/** The live connection with a state set by the test; what is saved or deleted is recorded. */
class FakeLiveSource(state: StateResponse? = null, override val myId: Long = 7) : LiveSource {
    override val live = MutableStateFlow(Live(state = state))
    override val pending = MutableStateFlow<List<PendingOp>>(emptyList())
    override val credentials: Credentials? = Credentials("https://dia.example.com", "t", "Тато", "caregiver", "mg/dl", myId)
    val saved = mutableListOf<Treatment>()
    val deleted = mutableListOf<String>()
    var reconnects = 0

    override fun saveTreatment(t: Treatment) {
        saved += t
    }

    override fun deleteTreatment(id: String) {
        deleted += id
    }

    override fun reconnectNow() {
        reconnects++
    }
}
