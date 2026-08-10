package io.onboardkit.ui.onboarding

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.bumptech.glide.Glide
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.NativeAdRequest
import io.onboardkit.ads.NativeTemplates
import io.onboardkit.config.ContentStepDefinition
import io.onboardkit.core.StepId
import io.onboardkit.core.analytics.AnalyticsEvent
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
        val definition = definition()
        return if (definition?.layoutRes != null && definition.layoutRes != 0) {
            inflater.inflate(definition.layoutRes, container, false)
        } else {
            ObFragmentContentStepBinding.inflate(inflater, container, false)
                .also { binding = it }
                .root
        }
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
        b.obPrimaryCta.setOnClickListener {
            OnboardingSdk.track(AnalyticsEvent.StepCompleted(stepId, dwellMs()))
            requireStepHost().next()
        }
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

    private fun defaultSampleImage(): Int = when (position) {
        0 -> R.drawable.ob_img_onboard_sample_1
        1 -> R.drawable.ob_img_onboard_sample_2
        2 -> R.drawable.ob_img_onboard_sample_3
        else -> R.drawable.ob_img_onboard_sample_4
    }

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
        val config = OnboardingSdk.configOrNull() ?: return
        val provider = OnboardingSdk.provider()
        val placement = AdPlacement.StepNative(stepId)
        val unit = config.ads.contentStepNative
        if (provider == null || unit == null ||
            !OnboardingSdk.policy().canShowNative(activity, placement)
        ) {
            b.obAdBlock.visibility = View.GONE
            return
        }
        val listener = object : AdEventListener {
            override fun onLoaded() {
                activity.runOnUiThread { bindAd() }
            }

            override fun onFailedToLoad() {
                activity.runOnUiThread {
                    if (!adBound) binding?.obAdBlock?.visibility = View.GONE
                }
            }
        }
        if (!bindAd(listener)) {
            val template = NativeTemplates.fromRemote(
                OnboardingSdk.flags().templateContent,
                config.ads.contentStepTemplate,
            )
            provider.preloadNative(
                activity,
                NativeAdRequest(placement, unit, NativeTemplates.layoutFor(template)),
            )
        }
    }

    private fun bindAd(listener: AdEventListener? = null): Boolean {
        val b = binding ?: return false
        val activity = activity ?: return false
        if (adBound) return true
        val bound = OnboardingSdk.provider()?.bindNative(
            activity,
            AdPlacement.StepNative(stepId),
            b.obNativeContainer,
            b.obNativeShimmer.root,
            listener,
        ) == true
        if (bound) adBound = true
        return bound
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
