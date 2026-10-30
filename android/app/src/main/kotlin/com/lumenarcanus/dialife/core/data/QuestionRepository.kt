package com.lumenarcanus.dialife.core.data

import com.lumenarcanus.dialife.data.ApiError
import com.lumenarcanus.dialife.data.ApiException
import com.lumenarcanus.dialife.data.DiaLifeApi
import com.lumenarcanus.dialife.live.LiveRepository
import com.lumenarcanus.dialife.live.QuestionNotifier
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * "Did you forget to log?" answered or a suspect mark cleared, from the app,
 * the food form or a notification button. A question someone already closed
 * counts as done; once done, its notification goes from this phone.
 */
class QuestionRepository(
    private val live: LiveRepository,
    private val notifier: QuestionNotifier,
    private val api: DiaLifeApi = DiaLifeApi()
) {
    /** [treatmentId]: the entry it was for, when one is named. */
    suspend fun answer(id: Long, answer: String, treatmentId: String? = null): Result<Unit> = post(
        "/api/questions/$id/answer",
        buildJsonObject {
            put("answer", answer)
            treatmentId?.let { put("treatment_id", it) }
        }.toString()
    ).onSuccess { notifier.cancel(id) }

    /** Clears a suspect mark; it goes from the chart here at once. */
    suspend fun dismiss(id: Long): Result<Unit> = post("/api/questions/$id/dismiss", "{}").onSuccess {
        live.updateState { s -> s.copy(questions = s.questions.filter { it.id != id }) }
    }

    private suspend fun post(path: String, body: String): Result<Unit> {
        val c = live.credentials ?: return Result.failure(ApiException(ApiError.Unauthorized))
        return api.post(c, path, body).recoverCatching { e ->
            if ((e as? ApiException)?.error != ApiError.Server(409)) throw e // 409: closed already
        }
    }
}
