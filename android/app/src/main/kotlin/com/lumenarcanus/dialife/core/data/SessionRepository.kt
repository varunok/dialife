package com.lumenarcanus.dialife.core.data

import android.content.Context
import com.lumenarcanus.dialife.data.CredentialStore
import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.data.DiaLifeApi
import com.lumenarcanus.dialife.live.LiveService
import com.lumenarcanus.dialife.widget.GlucoseWidget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An uploader's key: it sends readings, it cannot sign a family phone in. */
class UploaderTokenException : Exception("an uploader key cannot sign a family phone in")

/** Which server and key this phone uses: checked with the server before it is kept, forgotten on sign-out. */
interface SessionRepository {
    val credentials: Flow<Credentials?>

    /** The server stopped accepting the stored key; until the next try to connect. */
    val revoked: StateFlow<Boolean>

    /** Keeps the key once the server knows it; fails with [UploaderTokenException] or the API's error. */
    suspend fun connect(server: String, token: String): Result<Unit>

    /** The server stopped accepting the stored key: forget it. */
    suspend fun revoke()

    suspend fun disconnect()
}

class DefaultSessionRepository(
    private val context: Context,
    private val store: CredentialStore = CredentialStore(context),
    private val api: DiaLifeApi = DiaLifeApi()
) : SessionRepository {
    override val credentials: Flow<Credentials?> = store.credentials

    private val _revoked = MutableStateFlow(false)
    override val revoked: StateFlow<Boolean> = _revoked.asStateFlow()

    override suspend fun connect(server: String, token: String): Result<Unit> {
        _revoked.value = false
        return api.me(server, token).mapCatching { me ->
            if (me.role == "uploader") throw UploaderTokenException()
            store.save(Credentials(server, token, me.name, me.role, me.displayUnits, me.id))
        }
    }

    override suspend fun revoke() {
        _revoked.value = true
        LiveService.stop(context)
        store.clear()
    }

    override suspend fun disconnect() {
        LiveService.stop(context)
        store.clear()
        GlucoseWidget.refresh(context)
    }
}
