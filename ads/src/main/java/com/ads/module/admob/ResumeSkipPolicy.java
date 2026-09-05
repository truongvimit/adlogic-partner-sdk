package com.ads.module.admob;

import android.app.Activity;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Optional host eligibility at a resume opportunity. Both queries must be side-effect free:
 * return null to permit, or a stable skip reason; never consume one-shot state or emit events.
 * The shared hook also gates a host's alternate welcome flow. App-open mode, its own unit and
 * placement flag belong only in the second hook, so disabling OPEN does not disable WELCOME.
 */
@FunctionalInterface
public interface ResumeSkipPolicy {
    @Nullable String skipReasonFor(@NonNull Activity activity);

    /** Extra OPEN-only eligibility. Ordinary lambda policies need no second implementation. */
    @Nullable default String appOpenSkipReasonFor(@NonNull Activity activity) {
        return null;
    }
}
