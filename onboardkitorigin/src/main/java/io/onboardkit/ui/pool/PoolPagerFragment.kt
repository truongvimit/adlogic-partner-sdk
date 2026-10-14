package io.onboardkit.ui.pool

import androidx.fragment.app.Fragment
import io.onboardkit.core.StepId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Placeholder; replaced by the pager stage implementation. */
internal class PoolPagerFragment : Fragment(), PoolPagerStage {
    private val index = MutableStateFlow(0)
    override val blocksAppResume: Boolean get() = true
    override val currentIndex: StateFlow<Int> get() = index
    override val totalSteps: StateFlow<Int> get() = index
    override fun next(exitReason: String?) = Unit
    override fun completeAdStep(stepId: StepId, exitReason: String) = Unit
    override fun onWindowTouched() = Unit
}
