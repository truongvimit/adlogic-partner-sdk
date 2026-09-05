package com.ads.module.admob;

import android.app.Application;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Looper;

import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.test.core.app.ApplicationProvider;

import com.ads.module.consent.ConsentCenter;
import com.ads.module.helper.Entitlement;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowNetworkInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.robolectric.Shadows.shadowOf;

/** Java consumers call the explicit resume descriptors; only vendor loading is replaced. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class AppOpenResumeApiJavaTest {
    private Application app;
    private AppOpenManager manager;
    private final List<String> requestedUnits = new ArrayList<>();

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        manager = new AppOpenManager((context, unitId, request, callback) -> requestedUnits.add(unitId));
        ConsentCenter.setHostConsent(true, false);
        Entitlement.install(context -> false);

        ConnectivityManager connectivity = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true));
        NetworkCapabilities capabilities = new NetworkCapabilities();
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        shadowOf(connectivity).setNetworkCapabilities(connectivity.getActiveNetwork(), capabilities);
        shadowOf(Looper.getMainLooper()).idle();
    }

    @After
    public void tearDown() {
        manager.disableAppResume();
        app.unregisterActivityLifecycleCallbacks(manager);
        ProcessLifecycleOwner.get().getLifecycle().removeObserver(manager);
    }

    @Test
    public void explicitResumeMethodsRespectDisableAndBlankUnitFromJava() {
        manager.disableAppResume();
        manager.init(app, "java-resume-unit");
        manager.fetchResumeAd();
        manager.showResumeAdIfAvailable();
        assertFalse(manager.isResumeAdAvailable());
        assertEquals(Collections.emptyList(), requestedUnits);

        // A valid enabled request proves consent/network/entitlement did not mask the first gate.
        manager.enableAppResume();
        manager.fetchResumeAd();
        assertEquals(Collections.singletonList("java-resume-unit"), requestedUnits);
        assertFalse(manager.isResumeAdAvailable());

        manager.setAppResumeAdId(" ");
        manager.fetchResumeAd();
        manager.showResumeAdIfAvailable();
        assertFalse(manager.isResumeAdAvailable());
        assertEquals(Collections.singletonList("java-resume-unit"), requestedUnits);
    }
}
