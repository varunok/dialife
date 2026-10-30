package com.lumenarcanus.dialife.core.data

import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.data.DiaLifeApi
import com.lumenarcanus.dialife.data.InsulinType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * "… is giving insulin" on the other phones while the insulin form is open
 * here. Best effort: the server forgets it in 10 minutes anyway, and the
 * other phones get it with the live state.
 */
class InsulinIntentRepository(
    private val creds: () -> Credentials?,
    private val scope: CoroutineScope,
    private val api: DiaLifeApi = DiaLifeApi()
) {
    /** The insulin form opened here, or its type changed; null: it closed. */
    fun announce(type: InsulinType?) {
        val c = creds() ?: return
        scope.launch { if (type == null) api.insulinIntentEnd(c) else api.insulinIntent(c, type.wire) }
    }
}
