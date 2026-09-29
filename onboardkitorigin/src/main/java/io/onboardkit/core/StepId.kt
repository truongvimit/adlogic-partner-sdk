package io.onboardkit.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

/** Stable identifier of a step in the flow. Analytics and checkpoints key off this, never off pager index. */
@Serializable
@JvmInline
value class StepId(val value: String) {
    override fun toString(): String = value

    companion object {
        val OB1 = StepId("ob1")
        val OB2 = StepId("ob2")
        val OB3 = StepId("ob3")
        val OB4 = StepId("ob4")
        val FULL1 = StepId("full1")
        val FULL2 = StepId("full2")
        val OB5 = StepId("ob5")
        val QUESTION = StepId("question")
        val PARTNER_PRIVACY = StepId("partner_privacy")
        val PARTNER_PRIVACY_ALT = StepId("partner_privacy_alt")
        val PARTNER_GOAL = StepId("partner_goal")
        val PARTNER_GOAL_ALT = StepId("partner_goal_alt")
    }
}

enum class StepType { CONTENT, AD_FULL_SCREEN }

/** Why the pager flow is being finished. */
enum class FinishReason { COMPLETED, SKIPPED_BY_USER, EMPTY_FLOW, ABORTED }

/** Why the whole onboarding was skipped without showing. */
enum class SkipReason { ALREADY_COMPLETED, DISABLED_BY_CONFIG, DISABLED_BY_REMOTE }

@Parcelize
data class QuestionAnswer(val optionId: String, val title: String) : Parcelable
