package com.ads.module.admob;

import com.ads.module.config.settings.AdBehavior;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RatingBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ProcessLifecycleOwner;

import com.ads.module.R;
import com.ads.module.ads.wrapper.ApInterstitialAd;
import com.ads.module.dialog.PrepareLoadingAdsDialog;
import com.ads.module.engine.BannerEngine;
import com.ads.module.engine.InterstitialEngine;
import com.ads.module.engine.NativeEngine;
import com.ads.module.event.ERainLogEventManager;
import com.ads.module.funtion.AdCallback;
import com.ads.module.funtion.AdType;
import com.ads.module.funtion.AdmobHelper;
import com.ads.module.funtion.RewardCallback;
import com.ads.module.helper.AdGate;
import com.ads.module.helper.AdSkipReason;
import com.ads.module.helper.banner.BannerType;
import com.ads.module.tracking.AdTracking;
import com.ads.module.tracking.TrackingAdCallback;
import com.ads.module.util.SharePreferenceUtils;
import com.facebook.shimmer.ShimmerFrameLayout;
import com.google.ads.mediation.admob.AdMobAdapter;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdLoader;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdValue;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MediaAspectRatio;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.OnPaidEventListener;
import com.google.android.gms.ads.OnUserEarnedRewardListener;
import com.google.android.gms.ads.RequestConfiguration;
import com.google.android.gms.ads.VideoOptions;
import com.google.android.gms.ads.initialization.AdapterStatus;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.gms.ads.nativead.NativeAd;
import com.google.android.gms.ads.nativead.NativeAdOptions;
import com.google.android.gms.ads.nativead.NativeAdView;
import com.google.android.gms.ads.rewarded.RewardItem;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd;
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import io.trackkit.AdFormat;
import io.trackkit.PlacementRegistry;

public class Admob {
    private static final String TAG = "ERainStudio";

    public static final int ERROR_CODE_SHOW_IN_BACKGROUND = InterstitialEngine.ERROR_CODE_SHOW_IN_BACKGROUND;

    public static boolean isShowInBackgroundError(AdError error) {
        return InterstitialEngine.INSTANCE.isShowInBackgroundError(error);
    }
    private static Admob instance;
    private boolean disableAdResumeWhenClickAds = AdBehavior.defaultBool("app_open.presentation.skip_after_ad_click");
    private Context context;

    public static final String BANNER_INLINE_SMALL_STYLE = "BANNER_INLINE_SMALL_STYLE";
    public static final String BANNER_INLINE_LARGE_STYLE = "BANNER_INLINE_LARGE_STYLE";


    public void setMaxClickAdsPerDay(int maxClickAds) {
        InterstitialEngine.INSTANCE.setMaxClickAdsPerDay(maxClickAds);
    }

    public void recordAdClick(Context context, String adUnitId) {
        InterstitialEngine.INSTANCE.recordAdClick(context, adUnitId);
    }

    public static Admob getInstance() {
        if (instance == null) {
            instance = new Admob();
        }
        return instance;
    }

    private Admob() {

    }

    public void setDisableAdResumeWhenClickAds(boolean disableAdResumeWhenClickAds) {
        this.disableAdResumeWhenClickAds = disableAdResumeWhenClickAds;
    }

    public boolean isDisableAdResumeWhenClickAds() {
        return disableAdResumeWhenClickAds;
    }

    /** Application context captured by {@link #init}; null until then. */
    @Nullable
    public Context getAppContext() {
        return context;
    }

    public void init(Context context, List<String> testDeviceList) {
        initInternal(context);
        MobileAds.setRequestConfiguration(new RequestConfiguration.Builder().setTestDeviceIds(testDeviceList).build());
    }

    private void initInternal(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            String processName = Application.getProcessName();
            String packageName = context.getPackageName();
            if (!packageName.equals(processName)) {
                WebView.setDataDirectorySuffix(processName);
            }
        }
        MobileAds.initialize(context, initializationStatus -> {
            Map<String, AdapterStatus> statusMap = initializationStatus.getAdapterStatusMap();
            for (String adapterClass : statusMap.keySet()) {
                AdapterStatus status = statusMap.get(adapterClass);
                if (status != null) {
                    Log.d(TAG, String.format("Adapter name: %s, Description: %s, Latency: %d",
                            adapterClass, status.getDescription(), status.getLatency()));
                }
            }
        });
        // Application context, always: this field lives on a process-wide singleton, so storing an
        // Activity here — which the two-arg init() lets a partner pass — would leak it forever.
        this.context = context.getApplicationContext();
    }

    private String getProcessName(Context context) {
        if (context == null) return null;
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningAppProcessInfo processInfo : manager.getRunningAppProcesses()) {
            if (processInfo.pid == android.os.Process.myPid()) {
                return processInfo.processName;
            }
        }
        return null;
    }

    public void setOpenActivityAfterShowInterAds(boolean openActivityAfterShowInterAds) {
        InterstitialEngine.INSTANCE.setOpenNextUnderAdDefault(openActivityAfterShowInterAds);
    }

    public boolean isOpenActivityAfterShowInterAds() {
        return InterstitialEngine.INSTANCE.getOpenNextUnderAdDefault();
    }

    @SuppressLint("VisibleForTests")
    public AdRequest getAdRequest() {
        return new AdRequest.Builder().build();
    }

    public void forceShowInterstitial(Context context, InterstitialAd mInterstitialAd, final AdCallback callback, boolean openNextUnderAd) {
        InterstitialEngine.INSTANCE.show(context, new ApInterstitialAd(mInterstitialAd), callback, openNextUnderAd);
    }

    public void loadBanner(final Activity mActivity, String id) {
        loadBanner(mActivity, id, null);
    }

    public void loadBanner(final Activity mActivity, String id, AdCallback callback) {
        BannerEngine.INSTANCE.load(mActivity, id, mActivity.findViewById(R.id.banner_container),
                mActivity.findViewById(R.id.shimmer_container_banner), BannerType.Normal.INSTANCE, callback);
    }

    public void loadInlineBanner(final Activity activity, String id, String inlineStyle) {
        loadInlineBanner(activity, id, inlineStyle, null);
    }

    public void loadInlineBanner(final Activity activity, String id, String inlineStyle, final AdCallback callback) {
        BannerEngine.INSTANCE.load(activity, id, activity.findViewById(R.id.banner_container),
                activity.findViewById(R.id.shimmer_container_banner), new BannerType.Inline(inlineStyle), callback);
    }

    public void loadCollapsibleBanner(final Activity mActivity, String id, String gravity, final AdCallback callback) {
        BannerEngine.INSTANCE.load(mActivity, id, mActivity.findViewById(R.id.banner_container),
                mActivity.findViewById(R.id.shimmer_container_banner), new BannerType.Collapsible(gravity), callback);
    }

    public void loadBannerFragment(final Activity mActivity, String id, final View rootView, final AdCallback callback) {
        BannerEngine.INSTANCE.load(mActivity, id, rootView.findViewById(R.id.banner_container),
                rootView.findViewById(R.id.shimmer_container_banner), BannerType.Normal.INSTANCE, callback);
    }

    public void loadInlineBannerFragment(final Activity activity, String id, final View rootView, String inlineStyle, final AdCallback callback) {
        BannerEngine.INSTANCE.load(activity, id, rootView.findViewById(R.id.banner_container),
                rootView.findViewById(R.id.shimmer_container_banner), new BannerType.Inline(inlineStyle), callback);
    }

    public void loadCollapsibleBannerFragment(final Activity mActivity, String id, final View rootView, String gravity, final AdCallback callback) {
        BannerEngine.INSTANCE.load(mActivity, id, rootView.findViewById(R.id.banner_container),
                rootView.findViewById(R.id.shimmer_container_banner), new BannerType.Collapsible(gravity), callback);
    }

    public void loadLargeAnchoredBanner(final Activity mActivity, String id, final AdCallback callback) {
        BannerEngine.INSTANCE.load(mActivity, id, mActivity.findViewById(R.id.banner_container),
                mActivity.findViewById(R.id.shimmer_container_banner), BannerType.LargeAnchored.INSTANCE, callback);
    }

    public void loadLargeAnchoredBannerFragment(final Activity mActivity, String id, final View rootView, final AdCallback callback) {
        BannerEngine.INSTANCE.load(mActivity, id, rootView.findViewById(R.id.banner_container),
                rootView.findViewById(R.id.shimmer_container_banner), BannerType.LargeAnchored.INSTANCE, callback);
    }

    public void loadInlineBanner(final Activity activity, String id, int maxHeightDp, final AdCallback callback) {
        BannerEngine.INSTANCE.load(activity, id, activity.findViewById(R.id.banner_container),
                activity.findViewById(R.id.shimmer_container_banner), new BannerType.InlineMaxHeight(maxHeightDp), callback);
    }

    public void loadInlineBannerFragment(final Activity activity, String id, final View rootView, int maxHeightDp, final AdCallback callback) {
        BannerEngine.INSTANCE.load(activity, id, rootView.findViewById(R.id.banner_container),
                rootView.findViewById(R.id.shimmer_container_banner), new BannerType.InlineMaxHeight(maxHeightDp), callback);
    }

    public void loadFixedSizeBanner(final Activity mActivity, String id, AdSize adSize, final AdCallback callback) {
        BannerEngine.INSTANCE.loadFixedSize(mActivity, id, mActivity.findViewById(R.id.banner_container),
                mActivity.findViewById(R.id.shimmer_container_banner), adSize, callback);
    }

    public void loadFixedSizeBannerFragment(final Activity mActivity, String id, final View rootView, AdSize adSize, final AdCallback callback) {
        BannerEngine.INSTANCE.loadFixedSize(mActivity, id, rootView.findViewById(R.id.banner_container),
                rootView.findViewById(R.id.shimmer_container_banner), adSize, callback);
    }

    public void populateUnifiedNativeAdView(NativeAd nativeAd, NativeAdView adView) {
        NativeEngine.INSTANCE.populate(nativeAd, adView);
    }


    /**
     * Loads a rewarded ad and reports it through {@code callback}; premium users return without
     * a request. Nothing is cached here — the caller owns the fill.
     */
    public void initRewardAds(Context context, String id, AdCallback callback) {
        if (AdGate.areRequestsHeld() || !AdBehavior.bool("global.ads_enabled") || AdGate.isPurchased(context)) {
            return;
        }
        RewardedAd.load(context, id, getAdRequest(), new RewardedAdLoadCallback() {
            @Override
            public void onAdLoaded(@NonNull RewardedAd rewardedAd) {
                rewardedAd.setOnPaidEventListener(adValue -> {
                    ERainLogEventManager.logPaidAdImpression(context,
                            adValue,
                            rewardedAd.getAdUnitId(),
                            rewardedAd.getResponseInfo().getMediationAdapterClassName()
                            , AdType.REWARDED);
                    ERainLogEventManager.logPaidAdjustWithToken(adValue, rewardedAd.getAdUnitId());
                });
                callback.onRewardAdLoaded(rewardedAd);
            }

            @Override
            public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                callback.onAdFailedToLoad(loadAdError);
            }
        });
    }

    /** Shows only the supplied ad. Loading the next one is the caller's decision. */
    public void showRewardAds(final Activity context, RewardedAd rewardedAd,
                              final RewardCallback adCallback) {
        if (!AdBehavior.bool("global.ads_enabled") || AdGate.isPurchased(context)) {
            adCallback.onUserEarnedReward(null);
            return;
        }
        if (rewardedAd == null) {
            adCallback.onRewardedAdFailedToShow(0);
            return;
        } else {
            final AtomicBoolean settled = new AtomicBoolean(false);
            final String shownUnitId = rewardedAd.getAdUnitId();
            final TrackingAdCallback presentationTracking = new TrackingAdCallback(
                    PlacementRegistry.placementOf(shownUnitId), AdFormat.REWARDED, shownUnitId, null);
            rewardedAd.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override
                public void onAdDismissedFullScreenContent() {
                    if (!settled.compareAndSet(false, true)) return;
                    AppOpenManager.getInstance().setInterstitialShowing(false);
                    if (adCallback != null) adCallback.onRewardedAdClosed();
                }

                @Override
                public void onAdFailedToShowFullScreenContent(@NonNull AdError adError) {
                    if (!settled.compareAndSet(false, true)) return;
                    AppOpenManager.getInstance().setInterstitialShowing(false);
                    presentationTracking.onAdFailedToShow(adError);
                    if (adCallback != null)
                        adCallback.onRewardedAdFailedToShow(adError.getCode());
                }

                @Override
                public void onAdShowedFullScreenContent() {
                    if (settled.get()) return;

                    AppOpenManager.getInstance().setInterstitialShowing(true);
                    presentationTracking.onAdImpression();
                    if (adCallback != null) adCallback.onRewardedAdShown();
                }

                @Override
                public void onAdImpression() {
                    if (settled.get()) return;
                    if (adCallback != null) adCallback.onAdImpression();
                }

                public void onAdClicked() {
                    if (settled.get()) return;
                    if (AdBehavior.bool("app_open.presentation.skip_after_ad_click", disableAdResumeWhenClickAds))
                        AppOpenManager.getInstance().disableAdResumeByClickAction();
                    if (adCallback != null) {
                        adCallback.onAdClicked();
                    }
                    ERainLogEventManager.logClickAdsEvent(context, rewardedAd.getAdUnitId());
                }
            });
            rewardedAd.show(context, new OnUserEarnedRewardListener() {
                @Override
                public void onUserEarnedReward(@NonNull RewardItem rewardItem) {
                    if (adCallback != null) {
                        adCallback.onUserEarnedReward(rewardItem);

                    }
                }
            });
        }
    }


}
