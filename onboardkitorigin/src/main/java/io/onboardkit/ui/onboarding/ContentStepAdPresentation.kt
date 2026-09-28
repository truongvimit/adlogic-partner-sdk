package io.onboardkit.ui.onboarding

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.transition.ChangeBounds
import android.transition.ChangeImageTransform
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.View
import android.view.animation.PathInterpolator
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.Guideline
import androidx.core.view.children
import io.onboardkit.databinding.ObFragmentContentStepBinding
import kotlin.math.roundToInt

/** Loading, filled and unavailable ads share the same SDK views; only their bounds change. */
internal class ContentStepAdPresentation(private val binding: ObFragmentContentStepBinding) {
    private val root = binding.root
    private var adVisible = true

    private val midpoint = Guideline(root.context).apply { id = View.generateViewId() }
    private val fade = View(root.context).apply {
        id = View.generateViewId()
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x00FFFFFF, Color.WHITE),
        )
    }
    private val withAds: ConstraintSet
    private val withoutAds: ConstraintSet

    init {
        root.addView(midpoint, ConstraintLayout.LayoutParams(0, 0).apply {
            orientation = ConstraintLayout.LayoutParams.HORIZONTAL
            guidePercent = 0.5f
        })
        // Draw the fade above the artwork but below the existing content card.
        root.addView(fade, root.indexOfChild(binding.obStepCard),
            ConstraintLayout.LayoutParams(0, (50 * root.resources.displayMetrics.density).roundToInt()).apply {
                startToStart = ConstraintSet.PARENT_ID
                endToEnd = ConstraintSet.PARENT_ID
                bottomToTop = midpoint.id
            })
        withAds = ConstraintSet().apply {
            clone(root)
            // Media and ad visibility remain owned by the Fragment, including remote video.
            root.children.forEach { setVisibilityMode(it.id, ConstraintSet.VISIBILITY_MODE_IGNORE) }
        }
        withoutAds = ConstraintSet().apply {
            clone(withAds)
            listOf(binding.obStepImage, binding.obStepPlayer).forEach {
                clear(it.id, ConstraintSet.BOTTOM)
                connect(it.id, ConstraintSet.BOTTOM, midpoint.id, ConstraintSet.TOP)
            }
            val cardId = binding.obStepCard.id
            clear(cardId, ConstraintSet.BOTTOM)
            connect(cardId, ConstraintSet.TOP, fade.id, ConstraintSet.TOP)
            connect(cardId, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            setVerticalBias(cardId, 0.5f)
        }
    }

    fun setAdVisible(visible: Boolean, animate: Boolean = true) {
        if (adVisible == visible) return
        adVisible = visible
        if (animate && root.isLaidOut && root.isAttachedToWindow && root.isShown) {
            // Target just the outer views: do not animate/re-layout the native ad's children.
            TransitionManager.beginDelayedTransition(root, TransitionSet().apply {
                ordering = TransitionSet.ORDERING_TOGETHER
                duration = 280L
                interpolator = PathInterpolator(.4f, 0f, .2f, 1f)
                addTransition(ChangeBounds().apply {
                    addTarget(binding.obStepImage)
                    addTarget(binding.obStepPlayer)
                    addTarget(binding.obStepCard)
                })
                addTransition(ChangeImageTransform().addTarget(binding.obStepImage))
                addTransition(Fade().addTarget(fade).addTarget(binding.obAdBlock))
            })
        } else {
            finishTransition()
        }
        (if (visible) withAds else withoutAds).applyTo(root)
        binding.obAdBlock.visibility = if (visible) View.VISIBLE else View.GONE
        fade.visibility = if (visible) View.GONE else View.VISIBLE
    }

    fun finishTransition() = TransitionManager.endTransitions(root)
}
