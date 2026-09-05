package com.ads.module.admob;

import android.app.Activity;
import android.util.Log;

import com.ads.module.event.ERainLogEventManager;
import com.ads.module.consent.ConsentCenter;
import com.ads.module.funtion.RewardCallback;
import com.ads.module.helper.AdGate;
import com.ads.module.helper.AdSkipReason;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.OnUserEarnedRewardListener;
import com.google.android.gms.ads.rewarded.RewardItem;

import java.util.concurrent.atomic.AtomicBoolean;

import io.trackkit.AdFormat;

/** One rewarded invocation; earning survives presentation terminal without owning another ad. */
final class RewardPresentation extends FullScreenContentCallback implements OnUserEarnedRewardListener {
    private static final String TAG = "RewardPresentation";
    private final Activity activity;
    private final String adUnitId;
    private final RewardCallback callback;
    private final boolean suppressResumeOnClick;
    private final Runnable consume;
    private final Runnable whenPresented;
    private final boolean personalized;
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final AtomicBoolean earned = new AtomicBoolean();
    private volatile boolean invoked;
    private FullscreenPresentationOwner.Lease lease;

    private RewardPresentation(Activity activity, String adUnitId, RewardCallback callback,
                               boolean suppressResumeOnClick, Runnable consume, Runnable whenPresented) {
        this.activity = activity;
        this.adUnitId = adUnitId;
        this.callback = callback;
        this.suppressResumeOnClick = suppressResumeOnClick;
        this.consume = consume;
        this.whenPresented = whenPresented;
        this.personalized = ConsentCenter.canPersonalize();
    }

    static RewardPresentation acquire(Activity activity, AdFormat format, String adUnitId,
                                      RewardCallback callback, boolean suppressResumeOnClick,
                                      Runnable consume, Runnable whenPresented) {
        RewardPresentation presentation = new RewardPresentation(activity, adUnitId, callback,
                suppressResumeOnClick, consume, whenPresented);
        presentation.lease = FullscreenPresentationOwner.getInstance().tryAcquire(format,
                () -> presentation.reject(AdSkipReason.EXPIRED));
        if (presentation.lease == null) {
            presentation.reject(AdSkipReason.PRESENTATION_BUSY);
            return null;
        }
        return presentation;
    }

    /** Setup keeps the lease reserved; only the actual vendor invocation consumes the ad. */
    void show(Runnable setup, Runnable dispatch) {
        if (terminal.get()) return;
        try {
            setup.run();
        } catch (RuntimeException error) {
            reject(AdSkipReason.PREPARATION_FAILED);
            return;
        }
        if (terminal.get()) return;
        AdSkipReason policy = AdGate.skipReason(activity, true, true, false);
        if (policy != null) {
            reject(policy);
            return;
        }
        if (activity.isFinishing() || activity.isDestroyed()) {
            reject(AdSkipReason.INVALID_HOST);
            return;
        }
        if (personalized != ConsentCenter.canPersonalize()) {
            reject(AdSkipReason.NOT_READY);
            return;
        }
        final AdSkipReason admission;
        try {
            admission = callback == null ? null : callback.getAdShowSkipReason();
        } catch (Exception error) {
            reject(AdSkipReason.PREPARATION_FAILED);
            return;
        }
        if (admission != null) {
            reject(admission);
            return;
        }
        if (!lease.start()) {
            reject(AdSkipReason.EXPIRED);
            return;
        }
        invoked = true;
        try {
            consume.run();
            dispatch.run();
        } catch (RuntimeException error) {
            onAdFailedToShowFullScreenContent(new AdError(0,
                    error.getMessage() == null ? "Reward show failed" : error.getMessage(), TAG));
        }
    }

    private void reject(AdSkipReason reason) {
        if (!terminal.compareAndSet(false, true)) return;
        if (lease != null) lease.finish();
        notifySafely(() -> {
            if (callback != null) callback.onAdShowRejected(reason);
        });
    }

    private boolean finish() {
        if (!invoked || !terminal.compareAndSet(false, true)) return false;
        lease.finish();
        return true;
    }

    @Override
    public void onAdDismissedFullScreenContent() {
        if (!finish()) return;
        notifySafely(() -> {
            if (callback != null) callback.onRewardedAdClosed();
        });
    }

    @Override
    public void onAdFailedToShowFullScreenContent(AdError error) {
        if (!finish()) return;
        notifySafely(() -> {
            if (callback != null) callback.onRewardedAdFailedToShow(error.getCode());
        });
    }

    @Override
    public void onAdShowedFullScreenContent() {
        if (!invoked || terminal.get() || !lease.presented()) return;
        // A refill failure cannot release an ad already on screen or suppress presented delivery.
        notifySafely(whenPresented);
        notifySafely(() -> {
            if (callback != null) callback.onAdPresented();
        });
    }

    @Override
    public void onAdClicked() {
        if (!invoked || terminal.get()) return;
        if (suppressResumeOnClick) AppOpenManager.getInstance().disableAdResumeByClickAction();
        ERainLogEventManager.logClickAdsEvent(activity, adUnitId);
        notifySafely(() -> {
            if (callback != null) callback.onAdClicked();
        });
    }

    @Override
    public void onUserEarnedReward(RewardItem item) {
        // Do not consult the current fullscreen lease: A may earn after B has acquired it.
        if (!invoked || !earned.compareAndSet(false, true)) return;
        notifySafely(() -> {
            if (callback != null) callback.onUserEarnedReward(item);
        });
    }

    private static void notifySafely(Runnable action) {
        try {
            action.run();
        } catch (Exception error) {
            Log.w(TAG, "Reward callback failed", error);
        }
    }
}
