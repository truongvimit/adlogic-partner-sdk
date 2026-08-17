package com.ads.module.ads;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ads.module.ads.wrapper.ApInterstitialAd;
import com.ads.module.ads.wrapper.ApNativeAd;
import com.ads.module.funtion.AdCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.rewarded.RewardedAd;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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
        if (tiers.isEmpty()) {
            Log.w(TAG, "loadNative: no usable ad unit id");
            callback.onAdFailedToLoad(null);
            return;
        }
        loadNativeTier(activity, tiers, layoutRes, tierTimeoutMs, 0, callback);
    }

    private static void loadNativeTier(
            Activity activity,
            List<String> tiers,
            int layoutRes,
            long tierTimeoutMs,
            int index,
            AdCallback callback) {
        if (index >= tiers.size()) {
            Log.w(TAG, "loadNative: all " + tiers.size() + " tier(s) failed");
            callback.onAdFailedToLoad(null);
            return;
        }
        final Tier tier = new Tier(tierTimeoutMs, () ->
                loadNativeTier(activity, tiers, layoutRes, tierTimeoutMs, index + 1, callback));
        ERainAd.getInstance().loadNativeAdResultCallback(activity, tiers.get(index), layoutRes,
                new AdCallback() {
                    @Override
                    public void onNativeAdLoaded(@NonNull ApNativeAd nativeAd) {
                        // A fill from a tier the waterfall already moved past has nowhere to go
                        if (!tier.settle()) {
                            if (nativeAd.getAdmobNativeAd() != null) {
                                nativeAd.getAdmobNativeAd().destroy();
                            }
                            return;
                        }
                        callback.onNativeAdLoaded(nativeAd);
                    }

                    @Override
                    public void onAdFailedToLoad(@Nullable LoadAdError error) {
                        if (!tier.settle()) return;
                        Log.w(TAG, "loadNative tier " + (index + 1) + "/" + tiers.size()
                                + " failed: " + (error == null ? "null" : error.getMessage()));
                        loadNativeTier(activity, tiers, layoutRes, tierTimeoutMs, index + 1, callback);
                    }

                    @Override
                    public void onAdClicked() {
                        callback.onAdClicked();
                    }

                    @Override
                    public void onAdImpression() {
                        // Only the winning tier's view can render, so no settle() gate needed
                        callback.onAdImpression();
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
        if (tiers.isEmpty()) {
            Log.w(TAG, "loadInterstitial: no usable ad unit id");
            callback.onAdFailedToLoad(null);
            return;
        }
        loadInterstitialTier(context, tiers, tierTimeoutMs, 0, callback);
    }

    private static void loadInterstitialTier(
            Context context,
            List<String> tiers,
            long tierTimeoutMs,
            int index,
            AdCallback callback) {
        if (index >= tiers.size()) {
            Log.w(TAG, "loadInterstitial: all " + tiers.size() + " tier(s) failed");
            callback.onAdFailedToLoad(null);
            return;
        }
        final Tier tier = new Tier(tierTimeoutMs, () ->
                loadInterstitialTier(context, tiers, tierTimeoutMs, index + 1, callback));
        ERainAd.getInstance().getInterstitialAds(context, tiers.get(index), new AdCallback() {
            @Override
            public void onApInterstitialLoad(@Nullable ApInterstitialAd interstitialAd) {
                if (!tier.settle()) return;
                // A null wrapper is how the module reports a purchased or capped user; that is
                // this tier declining, not a fill.
                if (interstitialAd == null) {
                    loadInterstitialTier(context, tiers, tierTimeoutMs, index + 1, callback);
                    return;
                }
                callback.onApInterstitialLoad(interstitialAd);
            }

            @Override
            public void onAdFailedToLoad(@Nullable LoadAdError error) {
                if (!tier.settle()) return;
                Log.w(TAG, "loadInterstitial tier " + (index + 1) + "/" + tiers.size()
                        + " failed: " + (error == null ? "null" : error.getMessage()));
                loadInterstitialTier(context, tiers, tierTimeoutMs, index + 1, callback);
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
        if (tiers.isEmpty()) {
            Log.w(TAG, "loadReward: no usable ad unit id");
            callback.onAdFailedToLoad(null);
            return;
        }
        loadRewardTier(context, tiers, tierTimeoutMs, 0, callback);
    }

    private static void loadRewardTier(
            Context context,
            List<String> tiers,
            long tierTimeoutMs,
            int index,
            AdCallback callback) {
        if (index >= tiers.size()) {
            Log.w(TAG, "loadReward: all " + tiers.size() + " tier(s) failed");
            callback.onAdFailedToLoad(null);
            return;
        }
        final Tier tier = new Tier(tierTimeoutMs, () ->
                loadRewardTier(context, tiers, tierTimeoutMs, index + 1, callback));
        ERainAd.getInstance().initRewardAds(context, tiers.get(index), new AdCallback() {
            @Override
            public void onRewardAdLoaded(RewardedAd rewardedAd) {
                if (!tier.settle()) return;
                // The module answers a purchased user with silence, not null — the tier
                // timeout covers that; a null here is still a decline, not a fill.
                if (rewardedAd == null) {
                    loadRewardTier(context, tiers, tierTimeoutMs, index + 1, callback);
                    return;
                }
                callback.onRewardAdLoaded(rewardedAd);
            }

            @Override
            public void onAdFailedToLoad(@Nullable LoadAdError error) {
                if (!tier.settle()) return;
                Log.w(TAG, "loadReward tier " + (index + 1) + "/" + tiers.size()
                        + " failed: " + (error == null ? "null" : error.getMessage()));
                loadRewardTier(context, tiers, tierTimeoutMs, index + 1, callback);
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

        Tier(long timeoutMs, Runnable advance) {
            this.onTimeout = () -> {
                if (settled.compareAndSet(false, true)) {
                    Log.w(TAG, "tier timed out after " + timeoutMs + "ms");
                    advance.run();
                }
            };
            MAIN.postDelayed(onTimeout, timeoutMs);
        }

        boolean settle() {
            if (!settled.compareAndSet(false, true)) return false;
            MAIN.removeCallbacks(onTimeout);
            return true;
        }
    }
}
