package io.onboardkit

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import io.onboardkit.ads.AdPolicy
import io.onboardkit.ads.OnboardingAdProvider
import io.onboardkit.ads.PreloadChain
import io.onboardkit.config.OnboardKitConfig
import io.onboardkit.core.OnboardingListener
import io.onboardkit.core.OnboardingOutcome
import io.onboardkit.core.QuestionAnswer
import io.onboardkit.core.SkipReason
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.core.analytics.AnalyticsHub
import io.onboardkit.core.analytics.AnalyticsPlugin
import io.onboardkit.core.events.EventBus
import io.onboardkit.core.events.OnboardingEvent
import io.onboardkit.core.session.OnboardingSession
import io.onboardkit.core.state.OnboardingState
import io.onboardkit.core.state.OnboardingStateStore
import io.onboardkit.core.state.StoredAnswer
import io.onboardkit.flow.FlowDestination
import io.onboardkit.flow.FlowNavigator
import io.onboardkit.flow.StartDecision
import io.onboardkit.paywall.PaywallGate
import io.onboardkit.remote.ObRemote
import io.onboardkit.ui.language.LanguageScreenMode
import io.onboardkit.ui.language.ObLanguageActivity
import io.onboardkit.ui.onboarding.ObOnboardingHostActivity
import io.onboardkit.ui.question.ObQuestionActivity
import io.onboardkit.ui.question.QuestionSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Options for one flow run. */
data class StartOptions(
    val passthrough: Bundle? = null,
    /** Re-runs the flow even if already completed (debug / "replay tutorial"). */
    val forceRestart: Boolean = false,
)

/**
 * OnboardKit entry point.
 *
 * ```
 * OnboardingSdk.install(app) {
 *     adProvider = ERainAdProvider()
 *     listener = OnboardingListener { ctx, outcome -> /* navigate to main */ }
 * }
 * OnboardingSdk.configure(config)   // config from onboardKitConfig { … }
 * OnboardingSdk.start(activity)     // from splash
 * ```
 */
object OnboardingSdk {

    private const val TAG = "OnboardKit"

    private var application: Application? = null
    private var config: OnboardKitConfig? = null
    private var stateStore: OnboardingStateStore? = null
    private var remote: ObRemote? = null
    private var adProvider: OnboardingAdProvider? = null
    private var paywallGate: PaywallGate? = null
    private var listener: OnboardingListener? = null

    private val eventBus = EventBus()
    internal val session = OnboardingSession()
    private val sdkScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var adPolicy: AdPolicy
    private lateinit var preloadChain: PreloadChain

    class InstallBuilder internal constructor() {
        var adProvider: OnboardingAdProvider? = null
        var paywallGate: PaywallGate? = null
        var listener: OnboardingListener? = null
        internal val analyticsPlugins = mutableListOf<AnalyticsPlugin>()

        fun analyticsPlugin(plugin: AnalyticsPlugin) {
            analyticsPlugins += plugin
        }
    }

    /** Lightweight, synchronous. Call from [Application.onCreate]. */
    fun install(app: Application, block: InstallBuilder.() -> Unit = {}) {
        if (application != null) {
            Log.w(TAG, "install() called twice — ignoring")
            return
        }
        val builder = InstallBuilder().apply(block)
        application = app
        adProvider = builder.adProvider
        paywallGate = builder.paywallGate
        listener = builder.listener
        builder.analyticsPlugins.forEach(AnalyticsHub::addPlugin)
        stateStore = OnboardingStateStore(app)
        // Async preload (per DataStore guidance): seeds the language so attachBaseContext, which
        // cannot suspend, can wrap the locale for a returning user.
        stateStore?.let { store -> sdkScope.launch { session.seedLanguage(store.current().languageSelected) } }
        remote = ObRemote(app)
        adPolicy = AdPolicy(adProvider, ::configOrNull) { remote?.flags?.value ?: io.onboardkit.remote.RemoteFlags() }
        preloadChain = PreloadChain(adProvider, adPolicy, ::configOrNull) {
            remote?.flags?.value ?: io.onboardkit.remote.RemoteFlags()
        }
        Log.i(TAG, "OnboardKit ${BuildConfig.SDK_VERSION} installed")
    }

    /** Stores the validated config. Build it with [io.onboardkit.config.onboardKitConfig]. */
    fun configure(newConfig: OnboardKitConfig): Result<Unit> {
        if (application == null) {
            return Result.failure(IllegalStateException("Call install() before configure()"))
        }
        config = newConfig
        return Result.success(Unit)
    }

    fun isReady(): Boolean = application != null && config != null

    fun setListener(newListener: OnboardingListener) {
        listener = newListener
    }

    fun addAnalyticsPlugin(plugin: AnalyticsPlugin) = AnalyticsHub.addPlugin(plugin)

    val events: Flow<OnboardingEvent> get() = eventBus.events

    val state: Flow<OnboardingState>
        get() = requireNotNull(stateStore) { "Call install() first" }.state

    suspend fun isCompleted(): Boolean = stateStore?.current()?.isFlowCompleted == true

    suspend fun selectedLanguage(): String? =
        session.selectedLanguage ?: stateStore?.current()?.languageSelected

    suspend fun answers(): List<QuestionAnswer> =
        stateStore?.current()?.questionAnswers
            ?.map { QuestionAnswer(it.optionId, it.title) }
            .orEmpty()

    /** Clears all persisted progress (debug/logout). */
    suspend fun reset() {
        stateStore?.reset()
        session.reset()
    }

    /** Marks onboarding done without running it. */
    suspend fun markCompleted() {
        stateStore?.markFlowCompleted()
    }

    suspend fun shouldStart(): StartDecision {
        val cfg = config ?: return StartDecision.Skip(SkipReason.DISABLED_BY_CONFIG)
        val store = stateStore ?: return StartDecision.Skip(SkipReason.DISABLED_BY_CONFIG)
        return FlowNavigator.decideStart(store.current(), flags(), cfg)
    }

    /**
     * Launches the flow from splash according to [shouldStart]. When the flow is skipped the
     * listener fires immediately with [OnboardingOutcome.Skipped].
     */
    suspend fun start(activity: Activity, options: StartOptions = StartOptions()) {
        val cfg = config
        if (cfg == null) {
            Log.e(TAG, "start() before configure() — delivering Skipped")
            deliverOutcome(
                activity,
                OnboardingOutcome.Skipped(SkipReason.DISABLED_BY_CONFIG, options.passthrough),
            )
            return
        }
        session.reset()
        session.passthrough = options.passthrough
        session.selectedLanguage = stateStore?.current()?.languageSelected

        if (options.forceRestart) {
            stateStore?.reset()
        }

        eventBus.emit(OnboardingEvent.FlowStarted)
        when (val decision = if (options.forceRestart) {
            StartDecision.Start(FlowDestination.LANGUAGE, 0)
        } else {
            shouldStart()
        }) {
            is StartDecision.Skip -> deliverOutcome(
                activity,
                OnboardingOutcome.Skipped(decision.reason, options.passthrough),
            )

            is StartDecision.Start -> when (decision.destination) {
                FlowDestination.LANGUAGE ->
                    ObLanguageActivity.start(activity, LanguageScreenMode.FIRST_OPEN)

                FlowDestination.ONBOARDING ->
                    ObOnboardingHostActivity.start(activity, decision.resumeStepIndex)

                FlowDestination.QUESTION_NEW_USER ->
                    ObQuestionActivity.start(activity, QuestionSource.NEW_USER)

                FlowDestination.QUESTION_OLD_USER ->
                    ObQuestionActivity.start(activity, QuestionSource.OLD_USER)
            }
        }
    }

    /** Re-usable language picker; SETTINGS mode shows no ads and never touches flow state. */
    fun openLanguagePicker(activity: Activity, mode: LanguageScreenMode) {
        ObLanguageActivity.start(activity, mode)
    }

    suspend fun dumpState(): String = buildString {
        appendLine("OnboardKit ${BuildConfig.SDK_VERSION}")
        appendLine("installed=${application != null} configured=${config != null}")
        appendLine("state=${stateStore?.current()}")
        appendLine("flags=${remote?.flags?.value}")
    }

    // ── Internal wiring for SDK screens ──

    internal fun configOrNull(): OnboardKitConfig? = config

    internal fun requireConfig(): OnboardKitConfig =
        requireNotNull(config) { "OnboardKit not configured" }

    internal fun stateStoreOrNull(): OnboardingStateStore? = stateStore

    internal fun remoteOrNull(): ObRemote? = remote

    internal fun flags(): io.onboardkit.remote.RemoteFlags =
        remote?.flags?.value ?: io.onboardkit.remote.RemoteFlags()

    internal fun provider(): OnboardingAdProvider? = adProvider

    internal fun policy(): AdPolicy = adPolicy

    internal fun preload(): PreloadChain = preloadChain

    internal fun paywall(): PaywallGate? = paywallGate

    internal fun scope(): CoroutineScope = sdkScope

    internal fun emitEvent(event: OnboardingEvent) = eventBus.emit(event)

    internal fun track(event: AnalyticsEvent) = AnalyticsHub.track(event)

    /** Session-scoped, non-suspending: the flow always sets this before it is read. */
    internal fun selectedLanguageOrNull(): String? = session.selectedLanguage

    /**
     * Writes are fire-and-forget on the SDK scope: the UI already has the value in [session],
     * so no screen ever waits on disk.
     */
    internal fun persistLanguage(code: String) {
        session.selectedLanguage = code
        val store = stateStore ?: return
        sdkScope.launch { store.setLanguage(code) }
    }

    internal fun persistAnswers(answers: List<QuestionAnswer>) {
        session.answers.clear()
        session.answers.addAll(answers)
        val store = stateStore ?: return
        sdkScope.launch { store.saveAnswers(answers.map { StoredAnswer(it.optionId, it.title) }) }
    }

    /** Single exit point of the whole flow. CAS-guarded: fires the listener exactly once. */
    internal fun completeFlow(context: Context) {
        if (!session.finished.compareAndSet(false, true)) return
        val store = stateStore
        if (store != null) sdkScope.launch { store.markFlowCompleted() }
        adProvider?.releaseAll()
        eventBus.emit(OnboardingEvent.FlowCompleted(session.stepsShown.toList()))
        track(AnalyticsEvent.FlowCompleted(session.stepsShown.size))
        deliverOutcome(
            context,
            OnboardingOutcome.Completed(
                selectedLanguage = selectedLanguageOrNull(),
                answers = session.answers.toList(),
                passthrough = session.passthrough,
                stepsShown = session.stepsShown.toList(),
            ),
        )
    }

    internal fun deliverOutcome(context: Context, outcome: OnboardingOutcome) {
        listener?.onFinished(context, outcome)
            ?: Log.w(TAG, "No OnboardingListener registered — outcome dropped: $outcome")
    }

    /** Process-death guard: relaunch from the app launcher instead of building UI on nothing. */
    internal fun restartFromLauncher(activity: Activity) {
        val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            activity.startActivity(intent)
        }
    }
}
