package io.onboardkit.ui.onboarding

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.core.os.bundleOf
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.bumptech.glide.Glide
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.showNativeAd
import io.onboardkit.config.ContentStepDefinition
import io.onboardkit.core.ObLog
import io.onboardkit.core.StepId
import io.onboardkit.core.analytics.StepExit
import io.onboardkit.databinding.ObFragmentContentStepBinding
import io.onboardkit.remote.uiconfig.UiStepStyle
import io.onboardkit.ui.pager.LazyStepFragment
import io.onboardkit.ui.widget.ObPrimaryButton

/**
 * Content step (OB1/OB2/OB4 style). Layout resolves in three tiers: app-injected layout →
 * remote UI (only when this step's asset is cached) → SDK default. The ExoPlayer used for
 * remote video is released on unselect and on view destroy.
 */
class ContentStepFragment : LazyStepFragment() {

    private var binding: ObFragmentContentStepBinding? = null
    private var player: ExoPlayer? = null
    private var adBound = false

    private val stepId: StepId
        get() = StepId(requireArguments().getString(ARG_STEP_ID).orEmpty())

    private val position: Int
        get() = requireArguments().getInt(ARG_POSITION)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val custom = definition()?.layoutRes ?: 0
        if (custom != 0) {
            bindCustomLayout(inflater, container, custom)?.let { return it }
        }
        return ObFragmentContentStepBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root
    }

    /**
     * Inflates an app-supplied layout and binds it through the SDK's own view binding.
     *
     * The binding step is what makes [ContentStepDefinition.layoutRes] mean anything. Inflating
     * alone left [binding] null, and every method here opens with `binding ?: return` — so the
     * page rendered the app's layout and then bound no title, no image, no indicator, no ad, and
     * no click listener on the CTA. With swipe locked by default that is a page the user cannot
     * leave.
     *
     * Returns null when the layout does not honour the id contract, so the caller falls back to
     * the SDK layout: a page that looks wrong still beats a page that traps the user.
     */
    private fun bindCustomLayout(
        inflater: LayoutInflater,
        container: ViewGroup?,
        @LayoutRes layoutRes: Int,
    ): View? {
        val view = inflater.inflate(layoutRes, container, false)
        return runCatching { ObFragmentContentStepBinding.bind(view) }
            .onSuccess { binding = it }
            .onFailure {
                ObLog.e(
                    ObLog.Section.SCREEN,
                    "ContentStepDefinition(${stepId.value}).layoutRes does not honour the id " +
                        "contract — falling back to the SDK layout. Required: ob_step_image, " +
                        "ob_step_player, ob_step_card, ob_step_title, ob_step_subtitle, " +
                        "ob_step_indicator, ob_primary_cta, ob_ad_block, ob_native_container. " +
                        "Missing: ${it.message}",
                )
            }
            .map { view }
            .getOrNull()
    }

    override fun onViewReady(view: View) {
        val b = binding ?: return
        val definition = definition() ?: return

        bindStaticContent(b, definition)

        val style = remoteStyleIfReady()
        if (style != null) applyRemoteStyle(b, style)

        b.obStepIndicator.count = totalSteps()
        b.obStepIndicator.selectedIndex = position
        b.obPrimaryCta.state =
            if (position == totalSteps() - 1) ObPrimaryButton.State.LAST
            else ObPrimaryButton.State.NEXT
        style?.let {
            b.obPrimaryCta.overrideLabels(it.buttonNextText, it.buttonLastText, it.buttonTextColor)
            it.sliderColor?.let { color -> b.obStepIndicator.setColors(color) }
        }
        // Completion is reported by the host, which sees every page type and both exit paths
        b.obPrimaryCta.setOnClickListener { requireStepHost().next(StepExit.CTA) }
    }

    private fun bindStaticContent(
        b: ObFragmentContentStepBinding,
        definition: ContentStepDefinition,
    ) {
        val title = definition.title
            ?: definition.titleRes.takeIf { it != 0 }?.let(::getString)
            ?: getString(R.string.ob_sample_title_1)
        val subtitle = definition.subtitle
            ?: definition.subtitleRes.takeIf { it != 0 }?.let(::getString)
            ?: getString(R.string.ob_sample_subtitle_1)
        b.obStepTitle.text = title
        b.obStepSubtitle.text = subtitle
        b.obStepImage.setImageResource(
            if (definition.imageRes != 0) definition.imageRes else defaultSampleImage(),
        )
    }

    // Host apps supply their own art through ContentStepDefinition.imageRes; this only covers a
    // step that declared none.
    private fun defaultSampleImage(): Int = R.drawable.ob_img_onboard_placeholder

    private fun remoteStyleIfReady(): UiStepStyle? {
        val remote = OnboardingSdk.remoteOrNull() ?: return null
        if (!remote.isUiStyleReady(stepId.value)) return null
        return remote.uiConfig.value.styleFor(stepId.value)
    }

    private fun applyRemoteStyle(b: ObFragmentContentStepBinding, style: UiStepStyle) {
        style.title?.let { b.obStepTitle.text = it }
        style.subtitle?.let { b.obStepSubtitle.text = it }
        style.titleColor?.let { b.obStepTitle.setTextColor(it) }
        style.subtitleColor?.let { b.obStepSubtitle.setTextColor(it) }
        if (!style.textBackgroundEnabled) {
            b.obStepCard.background = null
        } else {
            style.textBackgroundColor?.let { color ->
                b.obStepCard.background?.mutate()?.setTint(color)
            }
        }
        val url = style.contentUrl ?: return
        if (style.isImage) {
            Glide.with(this).load(url).centerCrop().into(b.obStepImage)
        } else {
            b.obStepImage.visibility = View.GONE
            b.obStepPlayer.visibility = View.VISIBLE
        }
    }

    override fun onStepFirstSelected() {
        requestNativeAd()
    }

    override fun onStepSelected() {
        startVideoIfAny()
        val config = OnboardingSdk.configOrNull() ?: return
        if (!adBound && config.behavior.reloadAdOnStepReturn) requestNativeAd()
    }

    override fun onStepUnselected(dwellMs: Long) {
        releasePlayer()
    }

    private fun requestNativeAd() {
        val b = binding ?: return
        val activity = activity ?: return
        if (adBound) return
        val placement = AdPlacement.StepNative(stepId)
        activity.showNativeAd(
            placement = placement,
            // nativeUnitFor, not contentStepNative: a page with its own entry in `stepNatives` is
            // what the guard and the preload chain both resolve, so asking for the shared pool
            // here requested a different unit than the one that was warmed.
            unit = OnboardingSdk.configOrNull()?.ads?.nativeUnitFor(placement),
            container = b.obNativeContainer,
            onBound = { adBound = true },
            onUnavailable = { if (!adBound) binding?.obAdBlock?.visibility = View.GONE },
            onAdEngaged = { onStepAdEngaged() },
        )
    }

    private fun startVideoIfAny() {
        val b = binding ?: return
        val style = remoteStyleIfReady() ?: return
        val url = style.contentUrl ?: return
        if (style.isImage || player != null) return
        val remote = OnboardingSdk.remoteOrNull() ?: return
        val exo = ExoPlayer.Builder(requireContext()).build()
        player = exo
        b.obStepPlayer.player = exo
        val source = ProgressiveMediaSource.Factory(remote.assetCache.cacheDataSourceFactory())
            .createMediaSource(androidx.media3.common.MediaItem.fromUri(Uri.parse(url)))
        exo.setMediaSource(source)
        exo.repeatMode = ExoPlayer.REPEAT_MODE_ALL
        exo.volume = 0f
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun releasePlayer() {
        binding?.obStepPlayer?.player = null
        player?.release()
        player = null
    }

    private fun definition(): ContentStepDefinition? =
        OnboardingSdk.configOrNull()?.stepById(stepId) as? ContentStepDefinition

    private fun totalSteps(): Int =
        (activity as? ObOnboardingHostActivity)?.totalSteps?.value ?: 0

    override fun onDestroyView() {
        releasePlayer()
        binding = null
        adBound = false
        super.onDestroyView()
    }

    companion object {
        private const val ARG_STEP_ID = "ob_arg_step_id"
        private const val ARG_POSITION = "ob_arg_position"

        fun newInstance(stepId: StepId, position: Int): ContentStepFragment =
            ContentStepFragment().apply {
                arguments = bundleOf(ARG_STEP_ID to stepId.value, ARG_POSITION to position)
            }
    }
}
