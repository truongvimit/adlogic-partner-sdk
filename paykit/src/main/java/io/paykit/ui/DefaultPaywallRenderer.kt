package io.paykit.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import io.paykit.PayKit
import io.paykit.R
import io.paykit.databinding.PwActivityPaywallBinding
import io.paykit.databinding.PwItemBenefitBinding
import io.paykit.databinding.PwItemPackageBinding
import io.paykit.design.PaywallTheme
import io.paykit.internal.SingleClick
import io.paykit.internal.onSingleClick
import io.paykit.model.PriceView
import io.paykit.model.TrialInfo
import io.paykit.model.TrialUnit

/**
 * The View paywall the SDK ships: inflates the `pw_` layouts, repaints them with the resolved
 * [PaywallTheme], and forwards every tap to [PaywallActions].
 *
 * It reads [PaywallUiState] and nothing else — no billing, no config, no analytics.
 */
class DefaultPaywallRenderer : PaywallRenderer {

    private var binding: PwActivityPaywallBinding? = null
    private var actions: PaywallActions? = null

    /** One gate across every control that costs something, so no two billing flows can open. */
    private lateinit var gate: SingleClick

    // Selection is idempotent and free, so it gets its own gate: sharing the one above would let
    // a plan tap swallow the Continue tap that follows it a few hundred milliseconds later.
    private lateinit var selectGate: SingleClick

    private val packageRows = mutableListOf<PwItemPackageBinding>()
    private val benefitRows = mutableListOf<PwItemBenefitBinding>()

    override fun onCreate(root: ViewGroup, actions: PaywallActions) {
        this.actions = actions
        gate = PayKit.singleClick()
        selectGate = PayKit.singleClick()
        val view = PwActivityPaywallBinding.inflate(LayoutInflater.from(root.context), root, true)
        binding = view
        view.pwClose.onTap(gate, PaywallAction.Close)
        view.pwCta.onTap(gate, PaywallAction.Continue)
        view.pwContinueWithAds.onTap(gate, PaywallAction.ContinueWithAds)
        view.pwRestore.onTap(gate, PaywallAction.Restore)
    }

    override fun render(state: PaywallUiState) {
        val view = binding ?: return
        when (state) {
            PaywallUiState.Loading -> view.pwProgressOverlay.isVisible = true
            is PaywallUiState.Ready -> renderReady(view, state)
            is PaywallUiState.Purchasing -> view.pwProgressOverlay.isVisible = true
            PaywallUiState.Restoring -> view.pwProgressOverlay.isVisible = true
            // The host finishes the screen on an error; keep the overlay out of the way meanwhile.
            is PaywallUiState.Error -> view.pwProgressOverlay.isVisible = false
        }
    }

    override fun onDestroy() {
        packageRows.clear()
        benefitRows.clear()
        binding = null
        actions = null
    }

    private fun renderReady(view: PwActivityPaywallBinding, state: PaywallUiState.Ready) {
        view.pwProgressOverlay.isVisible = false
        applyTheme(view, state.theme)
        view.pwHeadline.text = state.headline
        view.pwCta.text = state.ctaLabel
        view.pwClose.isVisible = state.closeVisible
        view.pwContinueWithAds.isVisible = state.continueWithAdsVisible
        view.pwRestore.isVisible = state.restoreVisible
        view.pwSecondaryActions.isVisible =
            state.continueWithAdsVisible || state.restoreVisible
        bindBenefits(view, state.benefits, state.theme)
        bindPackages(view, state.packages, state.theme)
        bindFooter(view.pwFooter, state.theme)
    }

    private fun applyTheme(view: PwActivityPaywallBinding, theme: PaywallTheme) {
        val context = view.pwRoot.context
        view.pwRoot.setBackgroundColor(theme.background)
        view.pwHeaderImage.setBackgroundColor(theme.surface)
        view.pwHeadline.setTextColor(theme.textPrimary)
        view.pwCta.background = ctaBackground(context, theme)
        view.pwCta.setTextColor(theme.onAccent)
        view.pwCtaNote.setTextColor(theme.textSecondary)
        view.pwContinueWithAds.setTextColor(theme.textSecondary)
        view.pwRestore.setTextColor(theme.accent)
        view.pwFooter.setTextColor(theme.textSecondary)
        ImageViewCompat.setImageTintList(view.pwClose, ColorStateList.valueOf(theme.textSecondary))
        view.pwProgress.indeterminateTintList = ColorStateList.valueOf(theme.accent)
    }

    private fun bindBenefits(
        view: PwActivityPaywallBinding,
        benefits: List<String>,
        theme: PaywallTheme,
    ) {
        val container = view.pwBenefits
        if (benefitRows.size != benefits.size) {
            container.removeAllViews()
            benefitRows.clear()
            val inflater = LayoutInflater.from(container.context)
            repeat(benefits.size) {
                benefitRows += PwItemBenefitBinding.inflate(inflater, container, true)
            }
        }
        benefits.forEachIndexed { index, text ->
            val row = benefitRows[index]
            row.pwBenefitText.text = text
            row.pwBenefitText.setTextColor(theme.textPrimary)
            ImageViewCompat.setImageTintList(
                row.pwBenefitIcon,
                ColorStateList.valueOf(theme.accent),
            )
        }
    }

    private fun bindPackages(
        view: PwActivityPaywallBinding,
        items: List<PriceView>,
        theme: PaywallTheme,
    ) {
        val container = view.pwPackages
        if (packageRows.size != items.size) {
            container.removeAllViews()
            packageRows.clear()
            val inflater = LayoutInflater.from(container.context)
            repeat(items.size) {
                packageRows += PwItemPackageBinding.inflate(inflater, container, true)
            }
        }
        items.forEachIndexed { index, item -> bindPackage(packageRows[index], item, theme) }
    }

    private fun bindPackage(row: PwItemPackageBinding, item: PriceView, theme: PaywallTheme) {
        val context = row.pwPackageRoot.context
        row.pwPackageRoot.background = packageBackground(context, theme, item.selected)
        row.pwPackageRoot.onTap(selectGate, PaywallAction.Select(item.packageId))

        row.pwPackageCheck.isInvisible = !item.selected
        ImageViewCompat.setImageTintList(row.pwPackageCheck, ColorStateList.valueOf(theme.accent))

        row.pwPackageTitle.text = item.title
        row.pwPackageTitle.setTextColor(theme.textPrimary)

        val badge = item.badge
        row.pwPackageBadge.isVisible = !badge.isNullOrBlank()
        row.pwPackageBadge.text = badge.orEmpty()
        row.pwPackageBadge.background = accentPill(context, theme)
        row.pwPackageBadge.setTextColor(theme.onAccent)

        // The trial wins the subtitle slot over the billing period: it is the line that converts.
        val subtitle = item.trial?.let { trialLabel(context, it) } ?: item.subtitle
        row.pwPackageSubtitle.isVisible = !subtitle.isNullOrBlank()
        row.pwPackageSubtitle.text = subtitle.orEmpty()
        row.pwPackageSubtitle.setTextColor(theme.textSecondary)

        row.pwPackagePrice.text = item.price
        row.pwPackagePrice.setTextColor(theme.textPrimary)

        val oldPrice = item.oldPrice
        row.pwPackageOldPrice.isVisible = !oldPrice.isNullOrBlank()
        row.pwPackageOldPrice.text = oldPrice.orEmpty()
        row.pwPackageOldPrice.setTextColor(theme.textSecondary)
        row.pwPackageOldPrice.paintFlags =
            row.pwPackageOldPrice.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
    }

    private fun bindFooter(footer: TextView, theme: PaywallTheme) {
        val context = footer.context
        val terms = context.getString(R.string.pw_terms)
        val privacy = context.getString(R.string.pw_privacy)
        val sentence = context.getString(R.string.pw_footer_legal, terms, privacy)
        val spannable = SpannableString(sentence)
        linkify(spannable, sentence, terms, theme.accent, PaywallAction.Terms)
        linkify(spannable, sentence, privacy, theme.accent, PaywallAction.Privacy)
        footer.text = spannable
        footer.movementMethod = LinkMovementMethod.getInstance()
        footer.highlightColor = Color.TRANSPARENT
    }

    private fun linkify(
        target: SpannableString,
        sentence: String,
        label: String,
        color: Int,
        action: PaywallAction,
    ) {
        // A translation that dropped the placeholder must degrade to plain text, not crash.
        val start = sentence.indexOf(label)
        if (start < 0) return
        val end = start + label.length
        val span = object : ClickableSpan() {
            override fun onClick(widget: View) {
                if (gate.accept()) actions?.on(action)
            }
        }
        target.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        target.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun trialLabel(context: Context, trial: TrialInfo): String {
        val plural = when (trial.unit) {
            TrialUnit.DAY -> R.plurals.pw_trial_days
            TrialUnit.WEEK -> R.plurals.pw_trial_weeks
            TrialUnit.MONTH -> R.plurals.pw_trial_months
            TrialUnit.YEAR -> R.plurals.pw_trial_years
        }
        return context.resources.getQuantityString(plural, trial.count, trial.count)
    }

    // The XML shapes carry the radii; only the colours are remote, so they are overridden here.
    private fun ctaBackground(context: Context, theme: PaywallTheme): Drawable {
        val shape = shapeOf(context, R.drawable.pw_bg_cta) ?: return ColorDrawable(theme.accent)
        val gradient = theme.ctaGradient
        if (gradient != null && gradient.size >= 2) {
            shape.orientation = GradientDrawable.Orientation.LEFT_RIGHT
            shape.colors = gradient
        } else {
            shape.setColor(theme.accent)
        }
        return shape
    }

    private fun accentPill(context: Context, theme: PaywallTheme): Drawable {
        val shape = shapeOf(context, R.drawable.pw_bg_cta) ?: return ColorDrawable(theme.accent)
        shape.setColor(theme.accent)
        return shape
    }

    private fun packageBackground(
        context: Context,
        theme: PaywallTheme,
        selected: Boolean,
    ): Drawable {
        val id = if (selected) {
            R.drawable.pw_bg_package_selected
        } else {
            R.drawable.pw_bg_package_unselected
        }
        val shape = shapeOf(context, id) ?: return ColorDrawable(theme.surface)
        val strokeDimen = if (selected) R.dimen.pw_stroke_selected else R.dimen.pw_stroke_unselected
        val strokeColor = if (selected) {
            theme.accent
        } else {
            ContextCompat.getColor(context, R.color.pw_stroke)
        }
        shape.setColor(if (selected) theme.background else theme.surface)
        shape.setStroke(context.resources.getDimensionPixelSize(strokeDimen), strokeColor)
        return shape
    }

    // mutate() per row: a shared Drawable would have every row fighting over one set of bounds.
    private fun shapeOf(context: Context, @DrawableRes id: Int): GradientDrawable? =
        ContextCompat.getDrawable(context, id)?.mutate() as? GradientDrawable

    private fun View.onTap(gate: SingleClick, action: PaywallAction) {
        onSingleClick(gate) { actions?.on(action) }
    }
}
