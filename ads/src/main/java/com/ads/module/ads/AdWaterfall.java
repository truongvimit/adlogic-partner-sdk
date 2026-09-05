package com.ads.module.ads;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ads.module.admob.Admob;
import com.ads.module.ads.wrapper.ApInterstitialAd;
import com.ads.module.ads.wrapper.ApNativeAd;
import com.ads.module.funtion.AdCallback;
import com.ads.module.helper.AdGate;
import com.ads.module.tracking.AdLoadAttempt;
import com.ads.module.tracking.AdLoadContext;
import com.ads.module.tracking.TrackingAdCallback;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.nativead.NativeAd;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.rewarded.RewardedAd;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.trackkit.AdFormat;

/**
 * Requests one ad unit at a time, highest floor first, and stops at the first fill.
 * <p>
 * The list <em>is</em> the waterfall: index 0 is the high floor, the last entry is the all-price
 * fallback. Pass one id and it behaves exactly like a plain load. Blank and repeated ids are
 * dropped, so a half-filled remote payload cannot open a hole in the order.
 * <p>
 * All state lives on the stack of a single call, which is what lets several placements load at
 * once — the fixed-4 helpers on {@link ERainAd} keep their tier state in instance fields and
 * clobber each other when two screens preload together.
 */
public final class AdWaterfall {

    private static final String TAG = "AdWaterfall";

    /** Per-ad-unit request timeout. Matches the audited REQUEST_AD_TIMEOUT. */
    public static final long DEFAULT_TIER_TIMEOUT_MS = 30_000L;

    private AdWaterfall() {
    }

    private static boolean canContinue(Context context, AdCallback callback, AdLoadAttempt attempt) {
        // A fallback is another vendor request, so it needs current authority too.
        // Keep rewarded's offline behavior; each helper owns its network policy.
        if (AdGate.skipReason(context, true, true, false) == null) return true;
        attempt.onFailed(null);
        callback.onAdFailedToLoad(null);
        return false;
    }

    /**
     * Walks {@code adUnitIds} until one native fills.
     *
     * @param callback {@code onNativeAdLoaded} on the first fill, {@code onAdFailedToLoad} once
     *                 every tier has failed. Clicks are forwarded from whichever tier won.
     */
    public static void loadNative(
            @NonNull Activity activity,
            @Nullable List<String> adUnitIds,
            int layoutRes,
            @NonNull AdCallback callback) {
        loadNative(activity, adUnitIds, layoutRes, DEFAULT_TIER_TIMEOUT_MS, callback);
    }

    public static void loadNative(
            @NonNull Activity activity,
            @Nullable List<String> adUnitIds,
            int layoutRes,
            long tierTimeoutMs,
            @NonNull AdCallback callback) {
        List<String> tiers = usableIds(adUnitIds);
        loadNative(activity, tiers, layoutRes, tierTimeoutMs,
                AdLoadContext.forAdUnit(tiers.isEmpty() ? null : tiers.get(0), AdFormat.NATIVE), callback);
    }

    /** One logical native attempt with immutable placement/format, independent of any cache key. */
    public static void loadNative(
            @NonNull Activity activity,
            @Nullable List<String> adUnitIds,
            int layoutRes,
            long tierTimeoutMs,
            @NonNull AdLoadContext context,
            @NonNull AdCallback callback) {
        List<String> tiers = usableIds(adUnitIds);
        if (tiers.isEmpty()) {
            callback.onAdFailedToLoad(null);
            return;
        }
        loadNativeTier(activity, tiers, layoutRes, tierTimeoutMs, 0, callback,
                new AdLoadAttempt(context), null);
    }

    private static void loadNativeTier(
            Activity activity, List<String> tiers, int layoutRes, long tierTimeoutMs,
            int index, AdCallback callback, AdLoadAttempt attempt, LoadAdError lastError) {
        if (index >= tiers.size()) {
            attempt.onFailed(lastError == null ? null : lastError.getCode());
            callback.onAdFailedToLoad(null);
            return;
        }
        if (AdGate.skipReason(activity, true, true, false) != null) {
            attempt.onFailed(null);
            callback.onAdFailedToLoad(null);
            return;
        }
        final String unit = tiers.get(index);
        final Tier tier = new Tier(tierTimeoutMs, () ->
                loadNativeTier(activity, tiers, layoutRes, tierTimeoutMs, index + 1,
                        callback, attempt, null), attempt, index + 1, unit);
        final AdCallback presentation = TrackingAdCallback.presentationOnly(attempt.getContext(), unit, callback);
        Admob.getInstance().loadNativeAd(activity, unit, new AdCallback() {
            private NativeAd resolvedAd;
            private boolean delivered;

            @Override
            public void onAdRequestStarted(String adUnitId) {
                tier.dispatched();
                attempt.onRequestStarted(adUnitId);
                callback.onAdRequestStarted(adUnitId);
            }

            @Override
            public void onUnifiedNativeAdLoaded(@NonNull NativeAd nativeAd) {
                if (!tier.settle("loaded", null)) {
                    if (nativeAd != resolvedAd) nativeAd.destroy();
                    return;
                }
                resolvedAd = nativeAd;
                delivered = callback.canAcceptLoadedAd();
                if (delivered) attempt.onLoaded(unit);
                else attempt.onFailed(null);
                presentation.onNativeAdLoaded(new ApNativeAd(layoutRes, nativeAd));
            }

            @Override
            public void onAdFailedToLoad(@Nullable LoadAdError error) {
                if (!tier.settle("load_failed", error == null ? null : error.getCode())) return;
                loadNativeTier(activity, tiers, layoutRes, tierTimeoutMs, index + 1,
                        callback, attempt, error);
            }

            @Override
            public void onAdFailedToShow(@Nullable AdError error) {
                if (delivered) presentation.onAdFailedToShow(error);
            }

            @Override
            public void onAdClicked() {
                if (delivered) presentation.onAdClicked();
            }

            @Override
            public void onAdOpened() {
                if (delivered) presentation.onAdOpened();
            }

            @Override
            public void onAdImpression() {
                if (delivered) presentation.onAdImpression();
            }
        });
    }

    /**
     * Walks {@code adUnitIds} until one interstitial fills.
     *
     * @param callback {@code onApInterstitialLoad} on the first fill, {@code onAdFailedToLoad}
     *                 once every tier has failed.
     */
    public static void loadInterstitial(
            @NonNull Context context,
            @Nullable List<String> adUnitIds,
            @NonNull AdCallback callback) {
        loadInterstitial(context, adUnitIds, DEFAULT_TIER_TIMEOUT_MS, callback);
    }

    public static void loadInterstitial(
            @NonNull Context context,
            @Nullable List<String> adUnitIds,
            long tierTimeoutMs,
            @NonNull AdCallback callback) {
        List<String> tiers = usableIds(adUnitIds);
        loadInterstitial(context, tiers, tierTimeoutMs,
                AdLoadContext.forAdUnit(tiers.isEmpty() ? null : tiers.get(0), AdFormat.INTERSTITIAL), callback);
    }

    /** One logical interstitial attempt, including all dispatched fallback tiers. */
    public static void loadInterstitial(
            @NonNull Context context, @Nullable List<String> adUnitIds, long tierTimeoutMs,
            @NonNull AdLoadContext loadContext, @NonNull AdCallback callback) {
        List<String> tiers = usableIds(adUnitIds);
        loadInterstitialTier(context, tiers, tierTimeoutMs, 0, callback,
                new AdLoadAttempt(loadContext), null);
    }

    private static void loadInterstitialTier(
            Context context, List<String> tiers, long tierTimeoutMs, int index,
            AdCallback callback, AdLoadAttempt attempt, LoadAdError lastError) {
        if (index >= tiers.size()) {
            attempt.onFailed(lastError == null ? null : lastError.getCode());
            callback.onAdFailedToLoad(null);
            return;
        }
        if (!canContinue(context, callback, attempt)) return;
        final String unit = tiers.get(index);
        final Tier tier = new Tier(tierTimeoutMs, () ->
                loadInterstitialTier(context, tiers, tierTimeoutMs, index + 1, callback, attempt, null),
                attempt, index + 1, unit);
        final AdCallback presentation = TrackingAdCallback.presentationOnly(attempt.getContext(), unit, callback);
        Admob.getInstance().getInterstitialAds(context, unit, new AdCallback() {
            @Override
            public void onAdRequestStarted(String adUnitId) {
                tier.dispatched();
                attempt.onRequestStarted(adUnitId);
                callback.onAdRequestStarted(adUnitId);
            }

            @Override
            public void onInterstitialLoad(@Nullable InterstitialAd interstitialAd) {
                if (!tier.settle(interstitialAd == null ? "load_failed" : "loaded", null)) return;
                // Premium/click-cap declines can return null without a vendor dispatch.
                if (interstitialAd == null) {
                    loadInterstitialTier(context, tiers, tierTimeoutMs, index + 1, callback, attempt, null);
                    return;
                }
                if (callback.canAcceptLoadedAd()) attempt.onLoaded(unit);
                else attempt.onFailed(null);
                presentation.onApInterstitialLoad(new ApInterstitialAd(interstitialAd));
            }

            @Override
            public void onAdFailedToLoad(@Nullable LoadAdError error) {
                if (!tier.settle("load_failed", error == null ? null : error.getCode())) return;
                loadInterstitialTier(context, tiers, tierTimeoutMs, index + 1, callback, attempt, error);
            }
        });
    }

    /**
     * Walks {@code adUnitIds} until one rewarded ad fills.
     *
     * @param callback {@code onRewardAdLoaded} on the first fill, {@code onAdFailedToLoad}
     *                 once every tier has failed.
     */
    public static void loadReward(
            @NonNull Context context,
            @Nullable List<String> adUnitIds,
            @NonNull AdCallback callback) {
        loadReward(context, adUnitIds, DEFAULT_TIER_TIMEOUT_MS, callback);
    }

    public static void loadReward(
            @NonNull Context context,
            @Nullable List<String> adUnitIds,
            long tierTimeoutMs,
            @NonNull AdCallback callback) {
        List<String> tiers = usableIds(adUnitIds);
        loadReward(context, tiers, tierTimeoutMs,
                AdLoadContext.forAdUnit(tiers.isEmpty() ? null : tiers.get(0), AdFormat.REWARDED), callback);
    }

    /** One logical rewarded attempt, including all dispatched fallback tiers. */
    public static void loadReward(
            @NonNull Context context, @Nullable List<String> adUnitIds, long tierTimeoutMs,
            @NonNull AdLoadContext loadContext, @NonNull AdCallback callback) {
        List<String> tiers = usableIds(adUnitIds);
        loadRewardTier(context, tiers, tierTimeoutMs, 0, callback, new AdLoadAttempt(loadContext), null);
    }

    private static void loadRewardTier(
            Context context, List<String> tiers, long tierTimeoutMs, int index,
            AdCallback callback, AdLoadAttempt attempt, LoadAdError lastError) {
        if (index >= tiers.size()) {
            attempt.onFailed(lastError == null ? null : lastError.getCode());
            callback.onAdFailedToLoad(null);
            return;
        }
        if (!canContinue(context, callback, attempt)) return;
        final String unit = tiers.get(index);
        final Tier tier = new Tier(tierTimeoutMs, () ->
                loadRewardTier(context, tiers, tierTimeoutMs, index + 1, callback, attempt, null),
                attempt, index + 1, unit);
        final AdCallback presentation = TrackingAdCallback.presentationOnly(attempt.getContext(), unit, callback);
        Admob.getInstance().initRewardAds(context, unit, new AdCallback() {
            @Override
            public void onAdRequestStarted(String adUnitId) {
                tier.dispatched();
                attempt.onRequestStarted(adUnitId);
                callback.onAdRequestStarted(adUnitId);
            }

            @Override
            public void onRewardAdLoaded(RewardedAd rewardedAd) {
                if (!tier.settle(rewardedAd == null ? "load_failed" : "loaded", null)) return;
                if (rewardedAd == null) {
                    loadRewardTier(context, tiers, tierTimeoutMs, index + 1, callback, attempt, null);
                    return;
                }
                if (callback.canAcceptLoadedAd()) attempt.onLoaded(unit);
                else attempt.onFailed(null);
                presentation.onRewardAdLoaded(rewardedAd);
            }

            @Override
            public void onAdFailedToLoad(@Nullable LoadAdError error) {
                if (!tier.settle("load_failed", error == null ? null : error.getCode())) return;
                loadRewardTier(context, tiers, tierTimeoutMs, index + 1, callback, attempt, error);
            }
        });
    }

    /** The ids actually worth requesting: declared order, minus blanks and repeats. */
    @NonNull
    public static List<String> usableIds(@Nullable List<String> adUnitIds) {
        if (adUnitIds == null) return new ArrayList<>();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String id : adUnitIds) {
            if (id != null && !id.trim().isEmpty()) unique.add(id);
        }
        return new ArrayList<>(unique);
    }

    /**
     * One step of the waterfall. The vendor callback and the timeout race; the first one decides
     * and the other is ignored, so a floor that never answers cannot stall the tiers below it.
     */
    private static final class Tier {

        private static final Handler MAIN = new Handler(Looper.getMainLooper());

        private final AtomicBoolean settled = new AtomicBoolean(false);
        private final Runnable onTimeout;
        private final AdLoadAttempt attempt;
        private final int index;
        private final String unit;
        private boolean dispatched;
        private long dispatchedAt;

        Tier(long timeoutMs, Runnable advance, AdLoadAttempt attempt, int index, String unit) {
            this.attempt = attempt;
            this.index = index;
            this.unit = unit;
            this.onTimeout = () -> {
                if (settled.compareAndSet(false, true)) {
                    report("timeout", null);
                    Log.w(TAG, "tier timed out after " + timeoutMs + "ms");
                    advance.run();
                }
            };
            MAIN.postDelayed(onTimeout, timeoutMs);
        }

        void dispatched() {
            if (dispatched) return;
            dispatched = true;
            dispatchedAt = SystemClock.elapsedRealtime();
        }

        boolean settle(String outcome, Integer errorCode) {
            if (!settled.compareAndSet(false, true)) return false;
            MAIN.removeCallbacks(onTimeout);
            report(outcome, errorCode);
            return true;
        }

        private void report(String outcome, Integer errorCode) {
            if (attempt != null && dispatched && outcome != null) {
                attempt.onTierResult(index, unit, outcome, errorCode,
                        SystemClock.elapsedRealtime() - dispatchedAt);
            }
        }
    }
}
