package io.onboardkit.ads;

import com.ads.module.helper.interstitial.InterShowOptions;
import io.onboardkit.ads.erain.ERainAdProvider;

/** Ordinary Java constructor calls exercised by the real-provider Robolectric regressions. */
public final class ERainAdProviderJavaConsumer {
    private ERainAdProviderJavaConsumer() {}

    public static ERainAdProvider legacyDefaults() {
        return new ERainAdProvider();
    }

    public static ERainAdProvider legacyTimeout(long timeoutMs) {
        return new ERainAdProvider(timeoutMs);
    }

    public static ERainAdProvider configured(long timeoutMs, InterShowOptions options) {
        return new ERainAdProvider(timeoutMs, options);
    }

    public static ERainAdProvider configuredDefaults(InterShowOptions options) {
        return new ERainAdProvider(options);
    }
}
