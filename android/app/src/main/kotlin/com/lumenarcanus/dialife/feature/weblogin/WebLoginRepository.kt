package com.lumenarcanus.dialife.feature.weblogin

import com.lumenarcanus.dialife.data.ApiError
import com.lumenarcanus.dialife.data.ApiException
import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.data.DiaLifeApi
import com.lumenarcanus.dialife.data.WebLoginInfo
import com.lumenarcanus.dialife.data.WebLoginLink
import com.lumenarcanus.dialife.data.WebSession

/** Letting a browser into the web viewer, and the sessions open now. */
interface WebLoginRepository {
    suspend fun inspect(link: WebLoginLink): Result<WebLoginInfo>

    suspend fun approve(link: WebLoginLink, minutes: Int): Result<Unit>

    suspend fun reject(link: WebLoginLink): Result<Unit>

    suspend fun sessions(): Result<List<WebSession>>

    suspend fun revoke(id: Long): Result<Unit>
}

/** Letting a browser into the web viewer, and the sessions open now. */
class DefaultWebLoginRepository(private val creds: () -> Credentials?, private val api: DiaLifeApi = DiaLifeApi()) : WebLoginRepository {
    override suspend fun inspect(link: WebLoginLink): Result<WebLoginInfo> = with { api.webInspect(it, link) }

    override suspend fun approve(link: WebLoginLink, minutes: Int): Result<Unit> = with { api.webApprove(it, link, minutes) }

    override suspend fun reject(link: WebLoginLink): Result<Unit> = with { api.webReject(it, link) }

    override suspend fun sessions(): Result<List<WebSession>> = with { api.webSessions(it) }

    override suspend fun revoke(id: Long): Result<Unit> = with { api.webRevoke(it, id) }

    private inline fun <T> with(call: (Credentials) -> Result<T>): Result<T> =
        creds()?.let(call) ?: Result.failure(ApiException(ApiError.Unauthorized))
}
