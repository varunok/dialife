package com.lumenarcanus.dialife.feature.questions

import com.lumenarcanus.dialife.data.Treatment

/** What a parent does with a question. */
sealed interface QuestionAction {
    /** [treatmentId]: the entry it was for, when one is named. */
    data class Answer(val id: Long, val answer: String, val treatmentId: String?) : QuestionAction

    /** A dose said to be for the meal: saved linked to it. */
    data class Link(val treatment: Treatment) : QuestionAction
}
