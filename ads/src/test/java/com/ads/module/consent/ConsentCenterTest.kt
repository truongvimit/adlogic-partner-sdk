package com.ads.module.consent

import android.app.Activity
import android.content.Context
import android.os.Looper
import com.ads.module.helper.AdGate
import com.google.android.ump.ConsentForm
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import io.trackkit.ConsentState
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ConsentCenterTest {
    private lateinit var activity: Activity
    private lateinit var vendor: FakeUmpClient

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        ConsentCenter.reset(activity)
        activity.getSharedPreferences(activity.packageName + "_preferences", Context.MODE_PRIVATE)
            .edit().clear().commit()
        ConsentCenter.configure(ConsentOptions(debug = false))
        vendor = FakeUmpClient()
        ConsentCenter.umpClient = vendor
    }

    @After
    fun tearDown() {
        ConsentCenter.reset(activity)
        ConsentCenter.umpClient = PlatformUmpClient
        activity.finish()
    }

    @Test
    fun `legacy accepted preference does not skip this launch UMP update`() {
        activity.getSharedPreferences("ads_consent", Context.MODE_PRIVATE)
            .edit().putBoolean("consent_accepted", true).commit()

        ConsentCenter.request(activity) {}

        assertEquals(1, vendor.information.updates.size)
    }

    @Test
    fun `legacy no form preference cannot authorize an uninitialized process`() {
        activity.getSharedPreferences("ads_consent", Context.MODE_PRIVATE)
            .edit().putBoolean("consent_not_required", true).commit()

        assertFalse(ConsentCenter.isAlreadyResolved(activity))
        assertFalse(ConsentCenter.canRequestAds())
        assertFalse(ConsentCenter.canPersonalize())
        assertFalse(ConsentCenter.hasAnswered())
    }

    @Test
    fun `debug reset before first request clears vendor stored consent`() {
        vendor.information.obtained()

        ConsentCenter.reset(activity)

        assertEquals(1, vendor.information.resetCount)
        assertFalse(ConsentCenter.canRequestAds())
        assertFalse(ConsentCenter.hasAnswered())
    }

    @Test
    fun `update immediately publishes previous refusal eligibility before its callback`() {
        val completions = mutableListOf<Boolean>()
        vendor.information.onUpdate = { vendor.information.obtained() }

        ConsentCenter.request(activity, onCompleted = completions::add)

        assertTrue(ConsentCenter.canRequestAds())
        assertTrue(ConsentCenter.isAlreadyResolved(activity))
        assertEquals(ConsentState.DENIED, ConsentCenter.state.value)
        assertFalse(ConsentCenter.canPersonalize())
        assertTrue(ConsentCenter.isResolving())
        assertFalse(ConsentCenter.isFormShowing())
        assertTrue(completions.isEmpty())
    }

    @Test
    fun `first update error opens the request gate without granting consent and allows a retry`() {
        val completions = mutableListOf<Boolean>()
        val answers = mutableListOf<Boolean>()
        val prefs = activity.getSharedPreferences(activity.packageName + "_preferences", Context.MODE_PRIVATE)
        val before = prefs.all.toMap()
        ConsentCenter.request(activity, onFormAnswered = answers::add, onCompleted = completions::add)
        assertFalse(ConsentCenter.canRequestAds())

        vendor.information.updates.single().fail()
        vendor.information.updates.single().fail()

        assertEquals(listOf(true), completions)
        assertEquals(ConsentState.UNKNOWN, ConsentCenter.state.value)
        assertFalse(ConsentCenter.canPersonalize())
        assertTrue(ConsentCenter.canRequestAds())
        assertFalse(vendor.information.canRequestAds())
        assertTrue(answers.isEmpty())
        assertEquals(before, prefs.all)
        assertNull(AdGate.skipReason(activity, enabled = true, checkNetwork = false))
        assertEquals("disabled_config", AdGate.skipReason(activity, enabled = false, checkNetwork = false)?.key)
        assertFalse(ConsentCenter.isResolving())
        ConsentCenter.request(activity) {}
        assertFalse(ConsentCenter.canRequestAds())
        assertEquals(2, vendor.information.updates.size)
    }

    @Test
    fun `update error retains UMP previous non personalized authority without resetting it`() {
        val completions = mutableListOf<Boolean>()
        vendor.information.onUpdate = { vendor.information.obtained() }
        ConsentCenter.request(activity, onCompleted = completions::add)

        vendor.information.updates.single().fail()

        assertEquals(listOf(true), completions)
        assertTrue(ConsentCenter.canRequestAds())
        assertFalse(ConsentCenter.canPersonalize())
        assertEquals(ConsentState.DENIED, ConsentCenter.state.value)
        assertEquals(0, vendor.information.resetCount)
    }

    @Test
    fun `first timeout opens the gate at twenty seconds and stale callback cannot settle retry`() {
        val first = mutableListOf<Boolean>()
        val second = mutableListOf<Boolean>()
        ConsentCenter.request(activity, onCompleted = first::add)
        val staleUpdate = vendor.information.updates.single()

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(19_999))
        assertFalse(ConsentCenter.canRequestAds())
        assertTrue(first.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(listOf(true), first)
        assertTrue(ConsentCenter.canRequestAds())
        assertFalse(vendor.information.canRequestAds())
        assertNull(AdGate.skipReason(activity, enabled = true, checkNetwork = false))
        assertFalse(ConsentCenter.canPersonalize())
        assertFalse(ConsentCenter.hasAnswered())
        ConsentCenter.request(activity, onCompleted = second::add)
        assertFalse(ConsentCenter.canRequestAds())
        vendor.information.required()
        staleUpdate.succeed()
        staleUpdate.fail()

        assertTrue(vendor.forms.isEmpty())
        assertTrue(second.isEmpty())
        assertFalse(ConsentCenter.canRequestAds())
        assertTrue(ConsentCenter.isResolving())
        vendor.information.updates.last().succeed()
        assertEquals(1, vendor.forms.size)
    }

    @Test
    fun `timeout retains previous session eligibility and choice`() {
        val completions = mutableListOf<Boolean>()
        vendor.information.onUpdate = { vendor.information.obtained() }
        ConsentCenter.request(activity, onCompleted = completions::add)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))

        assertEquals(listOf(true), completions)
        assertFalse(ConsentCenter.canPersonalize())
        assertEquals(0, vendor.information.resetCount)
    }

    @Test
    fun `form loading shares the twenty second budget and late loaded form is ignored`() {
        val completions = mutableListOf<Boolean>()
        val answers = mutableListOf<Boolean>()
        ConsentCenter.request(activity, onFormAnswered = answers::add, onCompleted = completions::add)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(12))
        vendor.information.required()
        vendor.information.updates.single().succeed()
        val pendingForm = vendor.forms.single()
        assertFalse(ConsentCenter.canRequestAds())

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))

        assertEquals(listOf(true), completions)
        assertTrue(ConsentCenter.canRequestAds())
        assertFalse(ConsentCenter.isFormShowing())
        assertFalse(ConsentCenter.hasAnswered())
        val lateForm = FakeConsentForm()
        pendingForm.onLoaded(lateForm)
        pendingForm.onError(FormError(2, "late failure"))
        assertFalse(lateForm.shown)
        assertEquals(listOf(true), completions)
        assertTrue(answers.isEmpty())
    }

    @Test
    fun `no available form opens the request fallback while consent stays unknown`() {
        val completions = mutableListOf<Boolean>()
        ConsentCenter.request(activity, onCompleted = completions::add)
        vendor.information.required(formAvailable = false)

        vendor.information.updates.single().succeed()

        assertEquals(listOf(true), completions)
        assertTrue(ConsentCenter.canRequestAds())
        assertEquals(ConsentState.UNKNOWN, ConsentCenter.state.value)
        assertTrue(vendor.forms.isEmpty())
        ConsentCenter.request(activity) {}
        assertEquals(2, vendor.information.updates.size)
    }

    @Test
    fun `unknown successful update opens the failure fallback without manufacturing consent`() {
        val completions = mutableListOf<Boolean>()
        ConsentCenter.request(activity, onCompleted = completions::add)

        vendor.information.updates.single().succeed()

        assertEquals(listOf(true), completions)
        assertTrue(ConsentCenter.canRequestAds())
        assertFalse(ConsentCenter.hasAnswered())
        assertFalse(ConsentCenter.canPersonalize())
        ConsentCenter.request(activity) {}
        assertEquals(2, vendor.information.updates.size)
    }

    @Test
    fun `not required status authorizes without loading a form or reporting a form answer`() {
        val completions = mutableListOf<Boolean>()
        val answers = mutableListOf<Boolean>()
        ConsentCenter.request(activity, onFormAnswered = answers::add, onCompleted = completions::add)
        vendor.information.notRequired()

        vendor.information.updates.single().succeed()
        ConsentCenter.request(activity, onCompleted = completions::add)

        assertEquals(listOf(true, true), completions)
        assertTrue(ConsentCenter.canPersonalize())
        assertEquals(1, vendor.information.updates.size)
        assertTrue(vendor.forms.isEmpty())
        assertTrue(answers.isEmpty())
    }

    @Test
    fun `obtained personalization is read from TCF choices`() {
        activity.getSharedPreferences(activity.packageName + "_preferences", Context.MODE_PRIVATE)
            .edit().putString("IABTCF_PurposeConsents", "1".repeat(10))
            .putString("IABTCF_VendorConsents", "1".repeat(755)).commit()
        ConsentCenter.request(activity) {}
        vendor.information.obtained()

        vendor.information.updates.single().succeed()

        assertTrue(ConsentCenter.canRequestAds())
        assertTrue(ConsentCenter.canPersonalize())
        assertEquals(ConsentState.GRANTED, ConsentCenter.state.value)
    }

    @Test
    fun `visible UMP form held over ten minutes remains unresolved and unauthorized until grant`() {
        val completions = mutableListOf<Boolean>()
        val answers = mutableListOf<Boolean>()
        ConsentCenter.request(activity, onFormAnswered = answers::add, onCompleted = completions::add)
        val form = showRequiredForm()

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(610))

        assertTrue(ConsentCenter.isResolving())
        assertTrue(ConsentCenter.isFormShowing())
        assertTrue(completions.isEmpty())
        assertTrue(answers.isEmpty())
        assertFalse(ConsentCenter.canRequestAds())
        assertFalse(ConsentCenter.hasAnswered())
        activity.getSharedPreferences(activity.packageName + "_preferences", Context.MODE_PRIVATE)
            .edit().putString("IABTCF_PurposeConsents", "1".repeat(10))
            .putString("IABTCF_VendorConsents", "1".repeat(755)).commit()
        vendor.information.obtained()
        form.dismiss(null)

        assertEquals(listOf(true), completions)
        assertEquals(listOf(true), answers)
        assertTrue(ConsentCenter.canRequestAds())
        assertTrue(ConsentCenter.canPersonalize())
        assertFalse(ConsentCenter.isResolving())
        assertFalse(ConsentCenter.isFormShowing())
    }

    @Test
    fun `form has no human timeout and refusal remains valid UMP consent`() {
        val completions = mutableListOf<Boolean>()
        val answers = mutableListOf<Boolean>()
        ConsentCenter.request(activity, onFormAnswered = answers::add, onCompleted = completions::add)
        val form = showRequiredForm()

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(3))
        assertTrue(ConsentCenter.isResolving())
        assertTrue(ConsentCenter.isFormShowing())
        assertTrue(completions.isEmpty())
        vendor.information.obtained()
        form.dismiss(null)
        form.dismiss(null)
        ConsentCenter.request(activity, onCompleted = completions::add)

        assertEquals(listOf(true, true), completions)
        assertEquals(listOf(false), answers)
        assertEquals(ConsentState.DENIED, ConsentCenter.state.value)
        assertTrue(ConsentCenter.canRequestAds())
        assertFalse(ConsentCenter.isFormShowing())
        assertEquals(0, vendor.information.resetCount)
        assertEquals(1, vendor.information.updates.size)
    }

    @Test
    fun `form load error opens request fallback without answering the form`() {
        val completions = mutableListOf<Boolean>()
        val answers = mutableListOf<Boolean>()
        ConsentCenter.request(activity, onFormAnswered = answers::add, onCompleted = completions::add)
        vendor.information.required()
        vendor.information.updates.single().succeed()

        vendor.forms.single().onError(FormError(2, "offline"))

        assertEquals(listOf(true), completions)
        assertTrue(ConsentCenter.canRequestAds())
        assertTrue(answers.isEmpty())
        assertFalse(ConsentCenter.isResolving())
        ConsentCenter.request(activity) {}
        assertEquals(2, vendor.information.updates.size)
    }

    @Test
    fun `form dismissal error opens request fallback without recording an answer`() {
        val completions = mutableListOf<Boolean>()
        val answers = mutableListOf<Boolean>()
        ConsentCenter.request(activity, onFormAnswered = answers::add, onCompleted = completions::add)
        val form = showRequiredForm()

        form.dismiss(FormError(2, "window gone"))

        assertEquals(listOf(true), completions)
        assertTrue(ConsentCenter.canRequestAds())
        assertTrue(answers.isEmpty())
        assertFalse(ConsentCenter.hasAnswered())
        assertFalse(ConsentCenter.isFormShowing())
        ConsentCenter.request(activity) {}
        assertEquals(2, vendor.information.updates.size)
    }

    @Test
    fun `detaching owner discards old form callback and lets recreated screen resolve`() {
        val first = mutableListOf<Boolean>()
        val second = mutableListOf<Boolean>()
        ConsentCenter.request(activity, onCompleted = first::add)
        val oldForm = showRequiredForm()
        val replacement = Robolectric.buildActivity(Activity::class.java).setup().get()
        ConsentCenter.detach(replacement)
        assertTrue(ConsentCenter.isFormShowing())

        ConsentCenter.detach(activity)
        ConsentCenter.request(replacement, onCompleted = second::add)
        oldForm.dismiss(null)
        assertTrue(first.isEmpty())
        assertTrue(second.isEmpty())
        assertTrue(ConsentCenter.isResolving())
        vendor.information.obtained()
        vendor.information.updates.last().succeed()

        assertEquals(listOf(true), second)
        assertFalse(ConsentCenter.isResolving())
        replacement.finish()
    }

    @Test
    fun `explicit host consent completes pending UMP once and ignores its stale response`() {
        val completions = mutableListOf<Boolean>()
        val answers = mutableListOf<Boolean>()
        ConsentCenter.request(activity, onFormAnswered = answers::add, onCompleted = completions::add)
        val stale = vendor.information.updates.single()

        ConsentCenter.setHostConsent(canRequestAds = true, personalized = false)
        vendor.information.required()
        stale.succeed()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))

        assertEquals(listOf(true), completions)
        assertTrue(answers.isEmpty())
        assertTrue(vendor.forms.isEmpty())
        assertFalse(ConsentCenter.canPersonalize())
        assertFalse(ConsentCenter.isResolving())
        ConsentCenter.setHostConsent(canRequestAds = false, personalized = true)
        assertFalse(ConsentCenter.canRequestAds())
        assertFalse(ConsentCenter.canPersonalize())
    }

    @Test
    fun `host takeover keeps visible UMP form reported until it actually dismisses`() {
        val completions = mutableListOf<Boolean>()
        val answers = mutableListOf<Boolean>()
        ConsentCenter.request(activity, onFormAnswered = answers::add, onCompleted = completions::add)
        val form = showRequiredForm()

        ConsentCenter.setHostConsent(canRequestAds = true, personalized = false)

        assertEquals(listOf(true), completions)
        assertTrue(ConsentCenter.canRequestAds())
        assertFalse(ConsentCenter.isResolving())
        assertTrue(ConsentCenter.isFormShowing())
        form.dismiss(null)
        assertFalse(ConsentCenter.isFormShowing())
        assertEquals(listOf(true), completions)
        assertTrue(answers.isEmpty())
    }

    @Test
    fun `debug reset does not pretend to dismiss a visible form`() {
        val completions = mutableListOf<Boolean>()
        ConsentCenter.request(activity, onCompleted = completions::add)
        val form = showRequiredForm()

        ConsentCenter.reset(activity)

        assertTrue(ConsentCenter.isFormShowing())
        assertFalse(ConsentCenter.isResolving())
        assertFalse(ConsentCenter.canRequestAds())
        form.dismiss(null)
        assertFalse(ConsentCenter.isFormShowing())
        assertTrue(completions.isEmpty())
    }

    @Test
    fun `stale dismissal cannot hide a newer form after owner recreation`() {
        ConsentCenter.request(activity) {}
        val oldForm = showRequiredForm()
        ConsentCenter.detach(activity)
        val replacement = Robolectric.buildActivity(Activity::class.java).setup().get()
        val completions = mutableListOf<Boolean>()
        ConsentCenter.request(replacement, onCompleted = completions::add)
        val newForm = showRequiredForm()

        oldForm.dismiss(null)

        assertTrue(ConsentCenter.isFormShowing())
        assertTrue(ConsentCenter.isResolving())
        assertTrue(completions.isEmpty())
        vendor.information.obtained()
        newForm.dismiss(null)
        assertEquals(listOf(true), completions)
        assertFalse(ConsentCenter.isFormShowing())
        replacement.finish()
    }

    @Test
    fun `host only consent never invokes UMP and clearing it restores uninitialized authority`() {
        val completions = mutableListOf<Boolean>()
        ConsentCenter.setHostConsent(canRequestAds = true, personalized = true)

        ConsentCenter.request(activity, onCompleted = completions::add)
        assertEquals(listOf(true), completions)
        assertTrue(vendor.information.updates.isEmpty())
        assertTrue(ConsentCenter.canPersonalize())
        ConsentCenter.clearHostConsent()

        assertFalse(ConsentCenter.canRequestAds())
        assertFalse(ConsentCenter.hasAnswered())
        assertFalse(ConsentCenter.canPersonalize())
        assertEquals(0, vendor.information.resetCount)
        ConsentCenter.request(activity) {}
        assertEquals(1, vendor.information.updates.size)
    }

    @Test
    fun `clearing host override restores existing UMP authority and refreshes on next request`() {
        ConsentCenter.request(activity) {}
        vendor.information.obtained()
        vendor.information.updates.single().succeed()
        ConsentCenter.setHostConsent(canRequestAds = false, personalized = false)

        ConsentCenter.clearHostConsent()

        assertTrue(ConsentCenter.canRequestAds())
        assertFalse(ConsentCenter.canPersonalize())
        assertEquals(0, vendor.information.resetCount)
        ConsentCenter.request(activity) {}
        assertEquals(2, vendor.information.updates.size)
    }

    @Test
    fun `fresh update can revoke previous eligibility before the flow completes`() {
        val completions = mutableListOf<Boolean>()
        vendor.information.onUpdate = { vendor.information.notRequired() }
        ConsentCenter.request(activity, onCompleted = completions::add)

        vendor.information.required()
        vendor.information.updates.single().succeed()

        assertTrue(completions.isEmpty())
        assertEquals(1, vendor.forms.size)
        assertFalse(ConsentCenter.canRequestAds())
        assertFalse(ConsentCenter.canPersonalize())
        assertEquals(ConsentState.UNKNOWN, ConsentCenter.state.value)
    }

    @Test
    fun `host denial overrides a failure fallback and clearing host does not revive it`() {
        ConsentCenter.request(activity) {}
        val update = vendor.information.updates.single()
        update.fail()
        assertTrue(ConsentCenter.canRequestAds())

        ConsentCenter.setHostConsent(canRequestAds = false, personalized = false)
        update.fail()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        assertFalse(ConsentCenter.canRequestAds())
        ConsentCenter.clearHostConsent()
        assertFalse(ConsentCenter.canRequestAds())
        assertFalse(ConsentCenter.hasAnswered())
    }

    @Test
    fun `reset clears failure fallback and a fresh launch waits for its own error`() {
        ConsentCenter.request(activity) {}
        vendor.information.updates.single().fail()
        assertTrue(ConsentCenter.canRequestAds())

        ConsentCenter.reset(activity)
        assertFalse(ConsentCenter.canRequestAds())
        ConsentCenter.request(activity) {}
        assertFalse(ConsentCenter.canRequestAds())
        vendor.information.updates.last().fail()
        assertTrue(ConsentCenter.canRequestAds())
        assertFalse(ConsentCenter.canPersonalize())
        assertFalse(ConsentCenter.hasAnswered())
    }

    @Test
    fun `detaching a pending owner cannot open fallback via its timer or late error`() {
        val completions = mutableListOf<Boolean>()
        ConsentCenter.request(activity, onCompleted = completions::add)
        val update = vendor.information.updates.single()

        ConsentCenter.detach(activity)
        update.fail()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))

        assertFalse(ConsentCenter.canRequestAds())
        assertTrue(completions.isEmpty())
    }

    @Test
    fun `error delivered to a finishing activity does not open fallback`() {
        ConsentCenter.request(activity) {}
        activity.finish()

        vendor.information.updates.single().fail()

        assertFalse(ConsentCenter.canRequestAds())
        assertFalse(ConsentCenter.hasAnswered())
    }

    private fun showRequiredForm(): FakeConsentForm {
        vendor.information.required()
        vendor.information.updates.last().succeed()
        val form = FakeConsentForm()
        vendor.forms.last().onLoaded(form)
        assertTrue(form.shown)
        return form
    }

    private class FakeUmpClient : UmpClient {
        val information = FakeConsentInformation()
        val forms = mutableListOf<FormLoad>()
        override fun consentInformation(context: Context): ConsentInformation = information
        override fun loadForm(
            context: Context,
            onLoaded: (ConsentForm) -> Unit,
            onError: (FormError) -> Unit,
        ) {
            forms += FormLoad(onLoaded, onError)
        }
    }

    private data class FormLoad(val onLoaded: (ConsentForm) -> Unit, val onError: (FormError) -> Unit)

    private class FakeConsentForm : ConsentForm {
        var shown = false
        private lateinit var listener: ConsentForm.OnConsentFormDismissedListener
        override fun show(activity: Activity, listener: ConsentForm.OnConsentFormDismissedListener) {
            shown = true
            this.listener = listener
        }
        fun dismiss(error: FormError?) = listener.onConsentFormDismissed(error)
    }

    private class FakeConsentInformation : ConsentInformation {
        val updates = mutableListOf<Update>()
        var onUpdate: (() -> Unit)? = null
        var resetCount = 0
        private var status = ConsentInformation.ConsentStatus.UNKNOWN
        private var allowed = false
        private var formAvailable = false
        override fun canRequestAds(): Boolean = allowed
        override fun getConsentStatus(): Int = status
        override fun getPrivacyOptionsRequirementStatus(): ConsentInformation.PrivacyOptionsRequirementStatus =
            ConsentInformation.PrivacyOptionsRequirementStatus.UNKNOWN
        override fun isConsentFormAvailable(): Boolean = formAvailable
        override fun requestConsentInfoUpdate(
            activity: Activity,
            parameters: ConsentRequestParameters,
            onSuccess: ConsentInformation.OnConsentInfoUpdateSuccessListener,
            onFailure: ConsentInformation.OnConsentInfoUpdateFailureListener,
        ) {
            updates += Update(onSuccess, onFailure)
            onUpdate?.invoke()
        }
        override fun reset() {
            resetCount++
            status = ConsentInformation.ConsentStatus.UNKNOWN
            allowed = false
            formAvailable = false
        }
        fun obtained() {
            status = ConsentInformation.ConsentStatus.OBTAINED
            allowed = true
            formAvailable = true
        }
        fun notRequired() {
            status = ConsentInformation.ConsentStatus.NOT_REQUIRED
            allowed = true
            formAvailable = false
        }
        fun required(formAvailable: Boolean = true) {
            status = ConsentInformation.ConsentStatus.REQUIRED
            allowed = false
            this.formAvailable = formAvailable
        }
    }

    private data class Update(
        val success: ConsentInformation.OnConsentInfoUpdateSuccessListener,
        val failure: ConsentInformation.OnConsentInfoUpdateFailureListener,
    ) {
        fun succeed() = success.onConsentInfoUpdateSuccess()
        fun fail() = failure.onConsentInfoUpdateFailure(FormError(2, "offline"))
    }
}
