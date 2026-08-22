package io.paykit.ui

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.paykit.PayKit
import io.paykit.PayKitConfig
import io.paykit.PaywallContract
import io.paykit.PaywallListener
import io.paykit.PaywallPlacement
import io.paykit.PaywallResult
import io.paykit.R
import io.paykit.analytics.PaywallTracking
import io.paykit.billing.BillingBridge
import io.paykit.design.PaywallTheme
import io.paykit.design.TokenResolver
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

/**
 * Hosts the paywall: reads its placement, hands drawing to the installed [PaywallRenderer], and
 * reports the outcome through both `setResult` and the [PaywallListener] fan-out.
 *
 * Two guarantees the SDK this replaces did not make: back always dismisses, and `onFinished`
 * fires exactly once on every exit path, including a finish this screen did not start.
 */
class PaywallActivity : AppCompatActivity() {

    private val viewModel: PaywallViewModel by viewModels()
    private val finished = AtomicBoolean(false)

    private var placement: PaywallPlacement = PaywallPlacement.OTHER
    private var renderer: PaywallRenderer? = null
    private var root: ViewGroup? = null

    /** Names this presentation to PayKit, so a re-created screen resolves the same listener. */
    private var token: String? = null

    private var listeners: List<PaywallListener> = emptyList()

    // Whether iap_paywall_view was reported for this presentation; the terminal event is gated on
    // it so conversion stays computable from the pair.
    private var viewReported = false

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            // Back is a real dismiss, never a swallowed no-op: the paywall must not trap the user.
            dispatch(PaywallAction.Close)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        placement = PaywallContract.placementOf(intent)
        token = PaywallContract.tokenOf(intent)
        listeners = PayKit.listenersFor(token)
        viewReported = savedInstanceState?.getBoolean(STATE_VIEW_REPORTED) == true
        excludeFromAppResume()

        val config = PayKit.configOrNull()
        if (config == null) {
            // Process death revives this screen before Application.onCreate reinstalls PayKit.
            val code = PaywallViewModel.ERROR_NOT_INSTALLED
            exit(PaywallResult.Error(code, "PayKit is not installed"))
            return
        }

        val installed = PayKit.rendererOrNull() ?: DefaultPaywallRenderer()
        val content = findViewById<ViewGroup>(android.R.id.content)
        root = content
        applyWindowInsets(content)
        installed.onCreate(content, PaywallActions(::dispatch))
        renderer = installed
        viewModel.start(environmentOf(applicationContext, placement, config))
        onBackPressedDispatcher.addCallback(this, backCallback)
        observe()

        // Guarded, so a rotation or a restore after process death books one impression, not two.
        if (!viewReported) {
            viewReported = true
            PaywallTracking.paywallView(placement)
            listeners.forEach { it.onShown(placement) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_VIEW_REPORTED, viewReported)
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer?.onDestroy()
        renderer = null
        root = null
        // A finish this screen did not initiate still owes the host exactly one onFinished, and
        // the ViewModel may already hold an outcome the destroyed collector never delivered.
        if (isFinishing && finished.compareAndSet(false, true)) {
            notifyListeners(viewModel.committedResult() ?: PaywallResult.Dismissed)
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect { render(it) } }
                launch { viewModel.effects.collect { handle(it) } }
            }
        }
    }

    private fun render(state: PaywallUiState) {
        if (state is PaywallUiState.Ready) applyTheme(state.theme)
        renderer?.render(state)
    }

    // Edge-to-edge is enforced from API 35, so the Activity owns the insets on every version and
    // pads the content root: any renderer, shipped or supplied, inherits it.
    private fun applyWindowInsets(content: ViewGroup) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
    }

    // The background token is remote, so it has to reach the inset strips and the system icons:
    // fixing either in XML leaves white icons on a white bar, or a white strip under a dark theme.
    private fun applyTheme(theme: PaywallTheme) {
        root?.setBackgroundColor(theme.background)
        val light = ColorUtils.calculateLuminance(theme.background) > LIGHT_BACKGROUND_LUMINANCE
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    private fun dispatch(action: PaywallAction) {
        viewModel.onAction(action, this)
    }

    private fun handle(effect: PaywallEffect) {
        when (effect) {
            is PaywallEffect.Exit -> exit(effect.result)
            is PaywallEffect.OpenLink -> PaywallLinks.open(this, effect.url)
            is PaywallEffect.Message -> toast(messageRes(effect.kind))
        }
    }

    // RESULT_OK on every path: PaywallContract reads a non-OK code as Dismissed, which would
    // flatten continue-with-ads and error into the same outcome for the caller.
    private fun exit(result: PaywallResult) {
        if (!finished.compareAndSet(false, true)) return
        setResult(RESULT_OK, PaywallContract.resultIntent(result))
        notifyListeners(result)
        finish()
    }

    private fun notifyListeners(result: PaywallResult) {
        when (result) {
            is PaywallResult.Purchased ->
                listeners.forEach { it.onPurchased(placement, result.productId) }
            PaywallResult.ContinueWithAds -> listeners.forEach { it.onContinueWithAds(placement) }
            PaywallResult.Dismissed -> listeners.forEach { it.onDismissed(placement) }
            is PaywallResult.Error ->
                listeners.forEach { it.onError(placement, result.code, result.message) }
        }
        listeners.forEach { it.onFinished(placement, result) }
        // A presentation that never reported a view would leave an unpaired result behind, and
        // conversion is computed from that pair alone.
        if (viewReported) PaywallTracking.result(placement, result)
        PayKit.releaseListener(token)
    }

    private fun excludeFromAppResume() {
        // The ads SDK keeps a plain list, so a second call would leak a duplicate entry per launch.
        if (!appResumeExcluded.compareAndSet(false, true)) return
        BillingBridge.excludeFromAppResume(PaywallActivity::class.java)
    }

    private fun toast(@StringRes message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    @StringRes
    private fun messageRes(kind: PaywallMessage): Int = when (kind) {
        PaywallMessage.PURCHASE_FAILED -> R.string.pw_error_generic
        PaywallMessage.RESTORE_DONE -> R.string.pw_restore_done
        PaywallMessage.RESTORE_NOTHING -> R.string.pw_restore_none
        PaywallMessage.RESTORE_FAILED -> R.string.pw_error_generic
    }

    private companion object {
        const val STATE_VIEW_REPORTED = "io.paykit.state.VIEW_REPORTED"
        const val LIGHT_BACKGROUND_LUMINANCE = 0.5

        val appResumeExcluded = AtomicBoolean(false)
    }
}

// A top-level function so the Environment the ViewModel outlives cannot capture the Activity.
private fun environmentOf(
    appContext: Context,
    screen: PaywallPlacement,
    hostConfig: PayKitConfig,
): PaywallViewModel.Environment = object : PaywallViewModel.Environment {

    override val placement: PaywallPlacement = screen
    override val termsUrl: String = hostConfig.termsUrl
    override val privacyUrl: String = hostConfig.privacyUrl

    override suspend fun loadContent(): PaywallViewModel.Content? {
        val document = PayKit.paywallConfig() ?: return null
        return PaywallViewModel.Content(
            packages = document.packages,
            theme = TokenResolver.resolve(appContext, document.tokens),
            headline = stringOf(
                appContext, document.headline, document.headlineKey, R.string.pw_headline,
            ),
            benefits = benefitsOf(appContext, document.benefits, document.benefitKeys),
            ctaLabel = stringOf(
                appContext, document.cta, document.ctaKey, R.string.pw_cta_continue,
            ),
            preselectedId = document.preselectedId,
            exitButtonEnabled = document.exitButtonEnabled,
            // Remote wins, including an explicit 0: that is how a delay is switched off without
            // an app update. The host default only covers a document that omits the field.
            exitButtonDelayMs = document.exitButtonDelayMs ?: hostConfig.exitButtonDelayMs,
            continueWithAdsEnabled = document.continueWithAdsEnabled,
            restoreEnabled = document.restoreEnabled,
        )
    }
}

private val BENEFIT_DEFAULTS = listOf(
    R.string.pw_benefit_1,
    R.string.pw_benefit_2,
    R.string.pw_benefit_3,
    R.string.pw_benefit_4,
)

private fun benefitsOf(
    context: Context,
    literals: List<String>,
    keys: List<String>,
): List<String> {
    if (literals.isEmpty() && keys.isEmpty()) return BENEFIT_DEFAULTS.map(context::getString)
    // Per index, so a document naming keys this app does not define degrades to four different
    // lines rather than to the same one repeated.
    val size = maxOf(literals.size, keys.size)
    return List(size) { index ->
        stringOf(
            context,
            literals.getOrNull(index),
            keys.getOrNull(index),
            BENEFIT_DEFAULTS.getOrElse(index) { R.string.pw_benefit_1 },
        )
    }
}

/**
 * Literal first, then the resource key, then the bundled default. A literal is how copy changes
 * without an app update; the key is what keeps the default localised.
 */
private fun stringOf(
    context: Context,
    literal: String?,
    key: String?,
    @StringRes fallback: Int,
): String {
    if (!literal.isNullOrBlank()) return literal
    if (key.isNullOrBlank()) return context.getString(fallback)
    val id = context.resources.getIdentifier(key, "string", context.packageName)
    return if (id == 0) context.getString(fallback) else context.getString(id)
}
