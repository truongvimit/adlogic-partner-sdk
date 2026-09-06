package io.retentionkit

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.ads.module.billing.BillingEntitlement
import io.retentionkit.core.*
import io.retentionkit.integration.BillingRetentionBridge
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BillingBridgeTest {
    @After fun after() { RetentionRuntime.uninstallForTests() }
    @Test fun unknownIsNotFreeAndOnlyVerifiedStateUpdatesCoreWhileAttached() {
        val source = MutableStateFlow(BillingEntitlement.UNKNOWN)
        val bridge = BillingRetentionBridge(source)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val runtime = (RetentionRuntime.install(app, RetentionOptions(modules = listOf(bridge),
            store = SharedPreferencesRetentionStore(app, "billing_bridge_${UUID.randomUUID()}"),
            initialUserState = RetentionUserState(entitlement = RetentionEntitlement.NON_SUBSCRIBER))) as RetentionInstallResult.Installed).runtime
        assertEquals(RetentionEntitlement.UNKNOWN, runtime.userState.entitlement)
        source.value = BillingEntitlement.VERIFIED_NON_PREMIUM
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(RetentionEntitlement.NON_SUBSCRIBER, runtime.userState.entitlement)
        source.value = BillingEntitlement.VERIFIED_PREMIUM
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(RetentionEntitlement.SUBSCRIBER, runtime.userState.entitlement)
        bridge.shutdown()
        source.value = BillingEntitlement.UNKNOWN
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(RetentionEntitlement.SUBSCRIBER, runtime.userState.entitlement)
    }

    @Test fun lateAdapterInstallationReadsVerifiedEngineSnapshotImmediately() {
        val source = MutableStateFlow(BillingEntitlement.VERIFIED_PREMIUM)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val runtime = (RetentionRuntime.install(app, RetentionOptions(modules = listOf(BillingRetentionBridge(source)),
            store = SharedPreferencesRetentionStore(app, "billing_late_${UUID.randomUUID()}"))) as RetentionInstallResult.Installed).runtime
        assertEquals(RetentionEntitlement.SUBSCRIBER, runtime.userState.entitlement)
    }
}
