package com.lumenarcanus.dialife.feature.weblogin

import com.lumenarcanus.dialife.data.ApiError
import com.lumenarcanus.dialife.data.ApiException
import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.data.DiaLifeApi
import com.lumenarcanus.dialife.data.WebLoginInfo
import com.lumenarcanus.dialife.data.WebLoginLink
import com.lumenarcanus.dialife.data.WebSession

/** Letting a browser into the web viewer, and the sessions open now. */
class WebLoginRepository(private val creds: () -> Credentials?, private val api: DiaLifeApi = DiaLifeApi()) {
    suspend fun inspect(link: WebLoginLink): Result<WebLoginInfo> = with { api.webInspect(it, link) }

    suspend fun approve(link: WebLoginLink, minutes: Int): Result<Unit> = with { api.webApprove(it, link, minutes) }

    suspend fun reject(link: WebLoginLink): Result<Unit> = with { api.webReject(it, link) }

    suspend fun sessions(): Result<List<WebSession>> = with { api.webSessions(it) }

    suspend fun revoke(id: Long): Result<Unit> = with { api.webRevoke(it, id) }

    private inline fun <T> with(call: (Credentials) -> Result<T>): Result<T> =
        creds()?.let(call) ?: Result.failure(ApiException(ApiError.Unauthorized))
}
