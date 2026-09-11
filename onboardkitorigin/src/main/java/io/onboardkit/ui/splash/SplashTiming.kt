package io.onboardkit.ui.splash

import io.onboardkit.ads.NextScreenTiming
import io.onboardkit.flow.FlowDestination
import io.onboardkit.flow.StartDecision

internal fun defaultNextScreenTiming(entry: SplashEntry?, decision: StartDecision?): NextScreenTiming {
    val opensFirstOpenFlow = (decision as? StartDecision.Start)
        ?.let { it.destination != FlowDestination.QUESTION_OLD_USER } == true
    return if (entry != null || opensFirstOpenFlow) NextScreenTiming.AFTER_AD else NextScreenTiming.UNDER_AD
}
