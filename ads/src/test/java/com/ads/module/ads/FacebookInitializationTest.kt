package com.ads.module.ads

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.ads.module.config.AdsSdkConfig
import com.ads.module.helper.interstitial.Int02Application
import com.ads.module.helper.interstitial.Int02MobileAdsShadow
import com.facebook.FacebookSdk
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mockStatic
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Int02Application::class, shadows = [Int02MobileAdsShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class FacebookInitializationTest {
    @Test fun `manifest only credentials are not overwritten by the placeholder`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        mockStatic(FacebookSdk::class.java).use { facebook ->
            AdsSdk.getInstance().init(app, AdsSdkConfig(app))
            facebook.verify { FacebookSdk.sdkInitialize(app) }
            facebook.verifyNoMoreInteractions()
        }
    }

    @Test fun `explicit config token is supplied before initialization`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        mockStatic(FacebookSdk::class.java).use { facebook ->
            AdsSdk.getInstance().init(app, AdsSdkConfig(app).apply { facebookClientToken = "test-token" })
            facebook.verify { FacebookSdk.setClientToken("test-token") }
            facebook.verify { FacebookSdk.sdkInitialize(app) }
            facebook.verifyNoMoreInteractions()
        }
    }
}
