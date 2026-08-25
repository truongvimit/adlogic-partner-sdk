package com.ads.module.billing;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ads.module.funtion.BillingListener;
import com.ads.module.funtion.PurchaseCallback;
import com.ads.module.funtion.PurchaseListener;
import com.ads.module.funtion.UpdatePurchaseListener;
import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class AppPurchase {
    private static final String TAG = "PurchaseEG";

    public static final String PRODUCT_ID_TEST = "android.test.purchased";

    private static final long RECONNECT_BASE_MS = 1000L;
    private static final long RECONNECT_MAX_MS = 30000L;
    private static final int ACKNOWLEDGE_MAX_ATTEMPTS = 3;
    private static final String PENDING_MESSAGE = "Purchase is pending approval";

    @SuppressLint("StaticFieldLeak")
    private static volatile AppPurchase instance;

    private String price = "1.49$";
    private String oldPrice = "2.99$";

    private ArrayList<QueryProductDetailsParams.Product> listSubscriptionId;
    private ArrayList<QueryProductDetailsParams.Product> listINAPId;
    /**
     * Product.zza() is an obfuscated internal with no public getter, so the ids are kept here.
     */
    private final ArrayList<String> inAppIds = new ArrayList<>();
    private final ArrayList<String> subsIds = new ArrayList<>();
    private final CopyOnWriteArrayList<PurchaseItem> purchaseItems = new CopyOnWriteArrayList<>();

    private PurchaseListener purchaseListener;
    private UpdatePurchaseListener updatePurchaseListener;
    private BillingListener billingListener;
    private final CopyOnWriteArrayList<PurchaseCallback> purchaseCallbacks = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<BillingListener> verifyCompletionListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<BillingListener> persistentVerifyListeners = new CopyOnWriteArrayList<>();
    private PurchaseVerifier purchaseVerifier;

    private volatile Boolean isInitBillingFinish = false;
    /**
     * Makes the real init callback and its timeout mutually exclusive, and fires exactly once.
     */
    private final AtomicBoolean initCallbackDelivered = new AtomicBoolean(false);

    private BillingClient billingClient;
    private List<ProductDetails> skuListINAPFromStore;
    private List<ProductDetails> skuListSubsFromStore;
    final private Map<String, ProductDetails> skuDetailsINAPMap = new ConcurrentHashMap<>();
    final private Map<String, ProductDetails> skuDetailsSubsMap = new ConcurrentHashMap<>();
    private volatile boolean isAvailable;
    private boolean isConsumePurchase = false;
    private String idPurchaseCurrent = "";
    private String offerTokenCurrent = "";
    private int typeIap;
    private volatile boolean verifyFinish = false;
    /**
     * Once Play has answered, its answer wins over the cached entitlement — that is what stops a
     * refunded user staying premium forever.
     */
    private volatile boolean verifiedThisProcess = false;

    private boolean isUpdateInapps = false;
    private boolean isUpdateSubs = false;

    private volatile boolean isPurchase = false;
    private volatile String idPurchased = "";
    // copy-on-write: verifyPurchased clears these on a Billing callback thread while callers read them
    private final List<PurchaseResult> ownerIdSubs = new CopyOnWriteArrayList<>();
    private final List<String> ownerIdInapps = new CopyOnWriteArrayList<>();
    private final List<PurchaseResult> ownedInAppPurchases = new CopyOnWriteArrayList<>();

    @SuppressLint("StaticFieldLeak")
    private Context appContext;
    private String obfuscatedAccountId;
    private String obfuscatedProfileId;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable reconnectRunnable;
    private long reconnectDelayMs = RECONNECT_BASE_MS;
    private Handler handlerTimeout;
    private Runnable rdTimeout;

    public void setPurchaseListener(PurchaseListener purchaseListener) {
        this.purchaseListener = purchaseListener;
    }

    /**
     * Registers an additional listener; unlike {@link #setPurchaseListener} it never displaces one
     * a host app already installed.
     */
    public void addPurchaseCallback(PurchaseCallback callback) {
        if (callback != null && !purchaseCallbacks.contains(callback)) {
            purchaseCallbacks.add(callback);
        }
    }

    public void removePurchaseCallback(PurchaseCallback callback) {
        purchaseCallbacks.remove(callback);
    }

    /**
     * Server-side receipt check applied to purchases arriving from a billing flow, not to those
     * restored by {@link #verifyPurchased(boolean)}. Unset means every purchase is treated as verified.
     */
    public void setPurchaseVerifier(PurchaseVerifier purchaseVerifier) {
        this.purchaseVerifier = purchaseVerifier;
    }

    public void setUpdatePurchaseListener(UpdatePurchaseListener listener) {
        this.updatePurchaseListener = listener;
    }

    /**
     * Replaces the single init listener; fires immediately with 0 when verification already ran.
     */
    public void setBillingListener(BillingListener billingListener) {
        this.billingListener = billingListener;
        if (isAvailable && verifyFinish) {
            isInitBillingFinish = true;
            initCallbackDelivered.set(true);
            billingListener.onInitBillingFinished(0);
        }
    }

    public boolean isAvailable() {
        return isAvailable;
    }

    public Boolean getInitBillingFinish() {
        return isInitBillingFinish;
    }

    boolean isVerifyFinish() {
        return verifyFinish;
    }

    public void setEventConsumePurchaseTest(View view) {
        view.setOnClickListener(view1 -> {
            if (BillingKit.isDevMode()) {
                Log.d(TAG, "setEventConsumePurchaseTest: success");
                AppPurchase.getInstance().consumePurchase(PRODUCT_ID_TEST);
            }
        });
    }

    /**
     * As {@link #setBillingListener(BillingListener)}, but gives up after {@code timeout} ms so a
     * splash screen is never held by an unreachable Play service.
     */
    public void setBillingListener(BillingListener billingListener, int timeout) {
        Log.d(TAG, "setBillingListener: timeout " + timeout);
        this.billingListener = billingListener;
        if (isAvailable && verifyFinish) {
            Log.d(TAG, "setBillingListener: finish");
            isInitBillingFinish = true;
            initCallbackDelivered.set(true);
            billingListener.onInitBillingFinished(0);
            return;
        }
        isInitBillingFinish = false;
        initCallbackDelivered.set(false);
        if (handlerTimeout != null && rdTimeout != null) {
            // a timeout left behind by an earlier listener would consume the one-shot latch
            handlerTimeout.removeCallbacks(rdTimeout);
        }
        handlerTimeout = new Handler(Looper.getMainLooper());
        rdTimeout = () -> {
            Log.d(TAG, "setBillingListener: timeout run ");
            BillingListener current = this.billingListener;
            if (current != null && initCallbackDelivered.compareAndSet(false, true)) {
                isInitBillingFinish = true;
                current.onInitBillingFinished(BillingClient.BillingResponseCode.ERROR);
            }
        };
        handlerTimeout.postDelayed(rdTimeout, timeout);
    }

    /**
     * @deprecated write-only, nothing in the SDK reads it. Read prices from
     * {@link #getPrice(String, int, String)} instead.
     */
    @Deprecated
    public void setPrice(String price) {
        this.price = price;
    }

    public void setConsumePurchase(boolean consumePurchase) {
        isConsumePurchase = consumePurchase;
    }

    /**
     * @deprecated write-only, nothing in the SDK reads it. Use
     * {@link #getOldPriceFormatted(String, int, int)} instead.
     */
    @Deprecated
    public void setOldPrice(String oldPrice) {
        this.oldPrice = oldPrice;
    }

    /**
     * Obfuscated account id sent with every billing flow; Play uses it as a fraud signal.
     */
    public void setObfuscatedAccountId(String obfuscatedAccountId) {
        this.obfuscatedAccountId = obfuscatedAccountId;
    }

    public void setObfuscatedProfileId(String obfuscatedProfileId) {
        this.obfuscatedProfileId = obfuscatedProfileId;
    }

    PurchasesUpdatedListener purchasesUpdatedListener = new PurchasesUpdatedListener() {
        @Override
        public void onPurchasesUpdated(@NonNull BillingResult billingResult, List<Purchase> list) {
            int code = billingResult.getResponseCode();
            Log.e(TAG, "onPurchasesUpdated code: " + code);
            switch (code) {
                case BillingClient.BillingResponseCode.OK:
                    if (list != null) {
                        for (Purchase purchase : list) {
                            handlePurchase(purchase);
                        }
                    }
                    break;

                case BillingClient.BillingResponseCode.USER_CANCELED:
                    Log.d(TAG, "onPurchasesUpdated:USER_CANCELED ");
                    fanOutUserCancelBilling();
                    break;

                case BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED:
                    // Play already granted this one, so re-sync instead of leaving the caller waiting.
                    verifyPurchased(false);
                    fanOutAlreadyOwned(firstProductId(list, idPurchaseCurrent));
                    break;

                default:
                    // hosts toast the message, and getDebugMessage() is untranslated Play internals
                    Log.e(TAG, "onPurchasesUpdated: " + billingResult.getDebugMessage());
                    fanOutPurchaseError(code, mapLaunchResponse(billingResult).result.getMessage());
                    BillingTracking.trackPurchaseFail(idPurchaseCurrent, code);
                    break;
            }
        }
    };

    BillingClientStateListener purchaseClientStateListener =
            new BillingClientStateListener() {

                @Override
                public void onBillingServiceDisconnected() {
                    isAvailable = false;
                    scheduleReconnect();
                }

                @Override
                public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
                    int code = billingResult.getResponseCode();
                    Log.d(TAG, "onBillingSetupFinished: " + code);

                    isInitBillingFinish = true;

                    if (code != BillingClient.BillingResponseCode.OK) {
                        scheduleReconnect();
                        return;
                    }

                    reconnectDelayMs = RECONNECT_BASE_MS;
                    isAvailable = true;

                    verifyPurchased(true);
                    queryProductDetails();
                }
            };

    private void scheduleReconnect() {
        final BillingClient client = billingClient;
        if (client == null) {
            return;
        }
        long delay = reconnectDelayMs;
        reconnectDelayMs = Math.min(reconnectDelayMs * 2, RECONNECT_MAX_MS);
        if (reconnectRunnable != null) {
            mainHandler.removeCallbacks(reconnectRunnable);
        }
        reconnectRunnable = () -> {
            BillingClient current = billingClient;
            if (current != null && !current.isReady()) {
                current.startConnection(purchaseClientStateListener);
            }
        };
        Log.d(TAG, "scheduleReconnect: retry in " + delay + "ms");
        mainHandler.postDelayed(reconnectRunnable, delay);
    }

    public static AppPurchase getInstance() {
        AppPurchase local = instance;
        if (local == null) {
            synchronized (AppPurchase.class) {
                local = instance;
                if (local == null) {
                    local = new AppPurchase();
                    instance = local;
                    // First touch of the engine plugs the premium signal into :ads when present,
                    // so ad gating works from the same moment it did before the split.
                    EntitlementWiring.installIntoAdsIfPresent();
                }
            }
        }
        return local;
    }

    /**
     * @return an unmodifiable snapshot taken at call time; re-call after
     * {@link BillingListener#onInitBillingFinished} rather than caching the reference
     */
    public List<PurchaseResult> getOwnerIdSubs() {
        return Collections.unmodifiableList(new ArrayList<>(ownerIdSubs));
    }

    /**
     * @return an unmodifiable snapshot taken at call time; re-call after
     * {@link BillingListener#onInitBillingFinished} rather than caching the reference
     * @deprecated product ids only. Use {@link #getOwnedInAppPurchases()} for the full receipts;
     * this stays because changing the return type would break source compatibility.
     */
    @Deprecated
    public List<String> getOwnerIdInapps() {
        return Collections.unmodifiableList(new ArrayList<>(ownerIdInapps));
    }

    /**
     * @return an unmodifiable snapshot taken at call time; re-call after
     * {@link BillingListener#onInitBillingFinished} rather than caching the reference
     */
    public List<PurchaseResult> getOwnedInAppPurchases() {
        return Collections.unmodifiableList(new ArrayList<>(ownedInAppPurchases));
    }

    private AppPurchase() {

    }

    /**
     * @deprecated use {@link #initBilling(Application, List)}, which carries the product type and
     * the subscription offer coordinates per product.
     */
    @Deprecated
    public void initBilling(final Application application, List<
            String> listINAPId, List<String> listSubsId) {
        List<PurchaseItem> items = new ArrayList<>();
        if (listINAPId != null) {
            for (String id : listINAPId) {
                items.add(new PurchaseItem(id, TYPE_IAP.PURCHASE));
            }
        }
        if (listSubsId != null) {
            for (String id : listSubsId) {
                items.add(new PurchaseItem(id, TYPE_IAP.SUBSCRIPTION));
            }
        }
        initBilling(application, items);
    }

    private static boolean isDebuggable(Application application) {
        return (application.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    public void initBilling(final Application application, List<PurchaseItem> items) {
        if (billingClient != null) {
            // a second init would otherwise leak the first client and its connection
            endConnection();
        }
        appContext = application.getApplicationContext();

        purchaseItems.clear();
        List<String> inAppIdList = new ArrayList<>();
        List<String> subsIdList = new ArrayList<>();
        if (items != null) {
            for (PurchaseItem item : items) {
                if (item == null || item.getItemId() == null) {
                    continue;
                }
                purchaseItems.add(item);
                if (item.getType() == TYPE_IAP.SUBSCRIPTION) {
                    subsIdList.add(item.getItemId());
                } else {
                    inAppIdList.add(item.getItemId());
                }
            }
        }

        if (BillingKit.isDevMode()) {
            String warning = "initBilling: dev mode is on - purchases are simulated "
                    + "and premium is granted without Play.";
            // Only an error where it is actually dangerous: a non-debuggable build reached this
            // with the flag still on.
            if (isDebuggable(application)) {
                Log.i(TAG, warning);
            } else {
                Log.e(TAG, warning + " Call BillingKit.setDevMode(false) - or "
                        + "ERainAdConfig.variantDev(false) when :ads is present - for release.");
            }
            if (!inAppIdList.contains(PRODUCT_ID_TEST)) {
                inAppIdList.add(PRODUCT_ID_TEST);
                purchaseItems.add(new PurchaseItem(PRODUCT_ID_TEST, TYPE_IAP.PURCHASE));
            }
        }

        this.listSubscriptionId = listIdToListProduct(subsIdList, BillingClient.ProductType.SUBS, subsIds);
        this.listINAPId = listIdToListProduct(inAppIdList, BillingClient.ProductType.INAPP, inAppIds);

        billingClient = BillingClient.newBuilder(application)
                .setListener(purchasesUpdatedListener)
                .enableAutoServiceReconnection()
                .enablePendingPurchases(PendingPurchasesParams.newBuilder()
                        .enableOneTimeProducts()
                        .enablePrepaidPlans()
                        .build())
                .build();

        reconnectDelayMs = RECONNECT_BASE_MS;
        billingClient.startConnection(purchaseClientStateListener);
    }

    /**
     * Releases the Play connection and cancels any pending reconnect; a later
     * {@link #initBilling(Application, List)} builds a fresh client.
     */
    public void endConnection() {
        if (reconnectRunnable != null) {
            mainHandler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
        BillingClient client = billingClient;
        billingClient = null;
        isAvailable = false;
        if (client != null) {
            client.endConnection();
        }
    }

    /**
     * Re-queries the Play catalog, for when the set of products changed after init.
     */
    public void refreshProductDetails() {
        if (billingClient == null || !billingClient.isReady()) {
            Log.e(TAG, "refreshProductDetails: BillingClient is not ready");
            return;
        }
        queryProductDetails();
    }

    private void queryProductDetails() {
        if (listINAPId != null && !listINAPId.isEmpty()) {
            billingClient.queryProductDetailsAsync(
                    QueryProductDetailsParams.newBuilder().setProductList(listINAPId).build(),
                    (billingResult, result) -> {
                        List<ProductDetails> list = result.getProductDetailsList();
                        if (list != null && !list.isEmpty()) {
                            skuListINAPFromStore = list;
                            addSkuINAPToMap(list);
                        } else {
                            Log.e(TAG, "queryProductDetails INAPP empty: code="
                                    + billingResult.getResponseCode() + " msg=" + billingResult.getDebugMessage());
                        }
                    }
            );
        }

        if (listSubscriptionId != null && !listSubscriptionId.isEmpty()) {
            billingClient.queryProductDetailsAsync(
                    QueryProductDetailsParams.newBuilder().setProductList(listSubscriptionId).build(),
                    (billingResult, result) -> {
                        List<ProductDetails> list = result.getProductDetailsList();
                        if (list != null && !list.isEmpty()) {
                            skuListSubsFromStore = list;
                            addSkuSubsToMap(list);
                        } else {
                            Log.e(TAG, "queryProductDetails SUBS empty: code="
                                    + billingResult.getResponseCode() + " msg=" + billingResult.getDebugMessage());
                        }
                    }
            );
        }
    }

    private void addSkuSubsToMap(List<ProductDetails> skuList) {
        for (ProductDetails skuDetails : skuList) {
            skuDetailsSubsMap.put(skuDetails.getProductId(), skuDetails);
        }
    }

    private void addSkuINAPToMap(List<ProductDetails> skuList) {
        for (ProductDetails skuDetails : skuList) {
            skuDetailsINAPMap.put(skuDetails.getProductId(), skuDetails);
        }
    }

    /**
     * Forces the entitlement, for a host that grants premium from its own backend.
     * <p>
     * Goes through the same plumbing a real purchase does: writing only the field left the cached
     * entitlement stale across cold starts, and left every listener — including the facade that
     * publishes {@code Billing.isPremium} — reporting the previous value for the rest of the
     * session, so the ad layer went ad-free while the paywall kept selling.
     */
    public void setPurchase(boolean purchase) {
        isPurchase = purchase;
        PurchasePrefs.write(appContext, purchase, "manual");
        notifyVerifyCompletion(BillingClient.BillingResponseCode.OK);
    }

    public boolean isPurchased() {
        return isPurchased(appContext);
    }

    /**
     * @param context used to read the cached entitlement, so a cold start does not report
     *                not-premium while Play is still answering
     */
    public boolean isPurchased(Context context) {
        if (verifiedThisProcess || context == null) {
            return isPurchase;
        }
        return isPurchase || PurchasePrefs.readCached(context);
    }

    public String getIdPurchased() {
        return idPurchased;
    }

    private static void addOrUpdateOwnerIdSub(List<PurchaseResult> target, PurchaseResult purchaseResult, String id) {
        boolean isExistId = false;
        for (PurchaseResult p : target) {
            if (p.getProductId().contains(id)) {
                isExistId = true;
                target.remove(p);
                target.add(purchaseResult);
                break;
            }
        }
        if (!isExistId) {
            target.add(purchaseResult);
        }
    }

    public void verifyPurchased(boolean isCallback) {
        if (billingClient == null || !billingClient.isReady()) {
            Log.e(TAG, "BillingClient is not ready");
            // awaiting callers would otherwise sit until their own timeout expires
            notifyVerifyCompletion(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED);
            return;
        }

        verifyFinish = false;

        final List<String> productIdsINAP = new ArrayList<>(inAppIds);
        final List<String> productIdsSUBS = new ArrayList<>(subsIds);
        final VerifySweep sweep =
                new VerifySweep(isCallback, !productIdsINAP.isEmpty(), !productIdsSUBS.isEmpty());

        if (!sweep.issuedAnyQuery()) {
            finishVerify(sweep);
            return;
        }

        if (sweep.queryInap) {
            billingClient.queryPurchasesAsync(
                    QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
                    (billingResult, list) -> {
                        sweep.record(billingResult.getResponseCode());
                        if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && list != null) {
                            collectInapp(sweep, list, productIdsINAP);
                        }
                        sweep.inapDone = true;
                        finishVerify(sweep);
                    }
            );
        }

        if (sweep.querySubs) {
            billingClient.queryPurchasesAsync(
                    QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
                    (billingResult, list) -> {
                        sweep.record(billingResult.getResponseCode());
                        if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && list != null) {
                            collectSubs(sweep, list, productIdsSUBS);
                        }
                        sweep.subsDone = true;
                        finishVerify(sweep);
                    }
            );
        }
    }

    private void collectInapp(VerifySweep sweep, List<Purchase> list, List<String> productIds) {
        for (Purchase purchase : list) {
            if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) {
                continue;
            }
            boolean matched = false;
            boolean consumable = false;
            boolean entitlement = false;
            for (String productId : productIds) {
                if (!purchase.getProducts().contains(productId)) {
                    continue;
                }
                matched = true;
                if (!sweep.inappIds.contains(productId)) {
                    sweep.inappIds.add(productId);
                }
                if (resolveType(productId, TYPE_IAP.PURCHASE) == TYPE_IAP.CONSUMABLE) {
                    consumable = true;
                } else {
                    // consumables are spent goods, never an entitlement
                    entitlement = true;
                    sweep.purchased = true;
                }
            }
            if (!matched) {
                continue;
            }
            sweep.inappPurchases.add(PurchaseResult.from(purchase));
            if (consumable && !entitlement) {
                // acknowledging a consumable leaves it owned forever, and Play then blocks every re-buy
                consumeWithRetry(purchase, 0);
            } else if (!purchase.isAcknowledged()) {
                acknowledgeWithRetry(purchase, 0);
            }
        }
    }

    private void collectSubs(VerifySweep sweep, List<Purchase> list, List<String> productIds) {
        for (Purchase purchase : list) {
            if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) {
                continue;
            }
            boolean matched = false;
            for (String productId : productIds) {
                if (!purchase.getProducts().contains(productId)) {
                    continue;
                }
                matched = true;
                addOrUpdateOwnerIdSub(sweep.subs, PurchaseResult.from(purchase), productId);
                sweep.purchased = true;
            }
            if (matched && !purchase.isAcknowledged()) {
                acknowledgeWithRetry(purchase, 0);
            }
        }
    }

    private void finishVerify(VerifySweep sweep) {
        if (!sweep.isComplete() || !sweep.finished.compareAndSet(false, true)) {
            return;
        }
        verifyFinish = true;

        if (sweep.isTrustworthy()) {
            publishOwnership(sweep);
            verifiedThisProcess = true;
            PurchasePrefs.write(appContext, isPurchase, "play");
        } else {
            // a half-failed sweep knows nothing, so the previous entitlement and its cache stand
            Log.e(TAG, "verifyPurchased: keeping previous entitlement, worst code " + sweep.worstCode);
        }

        notifyVerifyCompletion(sweep.worstCode);

        if (billingListener != null && sweep.isCallback && initCallbackDelivered.compareAndSet(false, true)) {
            isInitBillingFinish = true;
            billingListener.onInitBillingFinished(sweep.worstCode);
            if (handlerTimeout != null && rdTimeout != null) {
                handlerTimeout.removeCallbacks(rdTimeout);
            }
        }
    }

    private void publishOwnership(VerifySweep sweep) {
        ownerIdInapps.clear();
        ownerIdInapps.addAll(sweep.inappIds);
        ownedInAppPurchases.clear();
        ownedInAppPurchases.addAll(sweep.inappPurchases);
        ownerIdSubs.clear();
        ownerIdSubs.addAll(sweep.subs);
        isPurchase = sweep.purchased;
    }

    /**
     * One run of the purchase queries. Ownership is collected here rather than into the live fields
     * so a query that failed cannot publish "owns nothing" over a good entitlement.
     */
    private static final class VerifySweep {
        final boolean isCallback;
        final boolean queryInap;
        final boolean querySubs;
        final AtomicBoolean finished = new AtomicBoolean(false);
        final List<String> inappIds = new CopyOnWriteArrayList<>();
        final List<PurchaseResult> inappPurchases = new CopyOnWriteArrayList<>();
        final List<PurchaseResult> subs = new CopyOnWriteArrayList<>();
        volatile boolean inapDone;
        volatile boolean subsDone;
        volatile boolean purchased;
        volatile int worstCode = BillingClient.BillingResponseCode.OK;

        VerifySweep(boolean isCallback, boolean queryInap, boolean querySubs) {
            this.isCallback = isCallback;
            this.queryInap = queryInap;
            this.querySubs = querySubs;
            this.inapDone = !queryInap;
            this.subsDone = !querySubs;
        }

        boolean issuedAnyQuery() {
            return queryInap || querySubs;
        }

        boolean isComplete() {
            return inapDone && subsDone;
        }

        /**
         * @return true only when Play actually answered every query that went out
         */
        boolean isTrustworthy() {
            return issuedAnyQuery() && worstCode == BillingClient.BillingResponseCode.OK;
        }

        void record(int responseCode) {
            if (responseCode != BillingClient.BillingResponseCode.OK
                    && worstCode == BillingClient.BillingResponseCode.OK) {
                worstCode = responseCode;
            }
        }
    }

    /**
     * One-shot hook used by the Kotlin facade to await a restore without displacing the host's
     * {@link BillingListener}.
     */
    void awaitNextVerify(BillingListener listener) {
        if (listener != null) {
            verifyCompletionListeners.add(listener);
        }
    }

    void cancelAwaitNextVerify(BillingListener listener) {
        verifyCompletionListeners.remove(listener);
    }

    /**
     * Unlike {@link #awaitNextVerify}, stays registered and fires on every verification, so a
     * facade can republish its premium state each time Play answers.
     */
    void addVerifyCompletionListener(BillingListener listener) {
        if (listener != null && !persistentVerifyListeners.contains(listener)) {
            persistentVerifyListeners.add(listener);
        }
    }

    private void notifyVerifyCompletion(int responseCode) {
        for (BillingListener listener : verifyCompletionListeners) {
            if (verifyCompletionListeners.remove(listener)) {
                listener.onInitBillingFinished(responseCode);
            }
        }
        for (BillingListener listener : persistentVerifyListeners) {
            listener.onInitBillingFinished(responseCode);
        }
    }

    public void updatePurchaseStatus() {
        if (billingClient == null || !billingClient.isReady()) {
            Log.e(TAG, "updatePurchaseStatus: BillingClient is not ready");
            return;
        }

        isUpdateInapps = false;
        isUpdateSubs = false;
        ownerIdInapps.clear();
        ownerIdSubs.clear();
        ownedInAppPurchases.clear();
        isPurchase = false;

        if (listINAPId != null) {
            billingClient.queryPurchasesAsync(
                    QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
                    (billingResult, list) -> {
                        if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && list != null) {
                            for (Purchase purchase : list) {
                                if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) {
                                    continue;
                                }
                                boolean matched = false;
                                for (String id : inAppIds) {
                                    if (!purchase.getProducts().contains(id)) {
                                        continue;
                                    }
                                    matched = true;
                                    if (!ownerIdInapps.contains(id)) {
                                        ownerIdInapps.add(id);
                                    }
                                    if (resolveType(id, TYPE_IAP.PURCHASE) != TYPE_IAP.CONSUMABLE) {
                                        isPurchase = true;
                                    }
                                }
                                if (matched) {
                                    ownedInAppPurchases.add(PurchaseResult.from(purchase));
                                }
                            }
                        }
                        isUpdateInapps = true;
                        if (isUpdateSubs) {
                            if (updatePurchaseListener != null) {
                                updatePurchaseListener.onUpdateFinished();
                            }
                        }
                    }
            );
        }

        if (listSubscriptionId != null) {
            billingClient.queryPurchasesAsync(
                    QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
                    (billingResult, list) -> {
                        if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && list != null) {
                            for (Purchase purchase : list) {
                                if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) {
                                    continue;
                                }
                                for (String id : subsIds) {
                                    if (purchase.getProducts().contains(id)) {
                                        addOrUpdateOwnerIdSub(ownerIdSubs, PurchaseResult.from(purchase), id);
                                        isPurchase = true;
                                    }
                                }
                            }
                        }
                        isUpdateSubs = true;
                        if (isUpdateInapps) {
                            if (updatePurchaseListener != null) {
                                updatePurchaseListener.onUpdateFinished();
                            }
                        }
                    }
            );
        }
    }

    /**
     * Launches the one-time purchase flow.
     *
     * @return why the flow did or did not start; the caller no longer has to string-match
     */
    public LaunchResult purchaseProduct(Activity activity, String productId) {
        return dispatch(purchaseInternal(activity, productId)).result;
    }

    /**
     * Launches the subscription flow.
     *
     * @param offerToken specific offer to buy, or null to let the SDK pick one
     */
    public LaunchResult subscribeProduct(Activity activity, String productId, @Nullable String offerToken) {
        return dispatch(subscribeInternal(activity, productId, offerToken, false)).result;
    }

    /**
     * @deprecated use {@link #purchaseProduct(Activity, String)}; the returned string cannot tell
     * success from "billing not ready" or from dev mode.
     */
    @Deprecated
    public String purchase(Activity activity, String productId) {
        return dispatch(purchaseInternal(activity, productId)).legacyMessage;
    }

    /**
     * @deprecated use {@link #subscribeProduct(Activity, String, String)} with a null offer token.
     */
    @Deprecated
    public String subscribe(Activity activity, String SubsId) {
        return dispatch(subscribeInternal(activity, SubsId, null, false)).legacyMessage;
    }

    /**
     * Subscribe with specific offer token
     *
     * @param activity   current activity
     * @param SubsId     subscription product ID
     * @param offerToken specific offer token to use
     * @return status message
     * @deprecated use {@link #subscribeProduct(Activity, String, String)}.
     */
    @Deprecated
    public String subscribe(Activity activity, String SubsId, String offerToken) {
        return dispatch(subscribeInternal(activity, SubsId, offerToken, true)).legacyMessage;
    }

    private LaunchOutcome purchaseInternal(Activity activity, String productId) {
        if (skuListINAPFromStore == null || billingClient == null) {
            return new LaunchOutcome(LaunchResult.BILLING_NOT_READY, "", "Billing error init",
                    BillingClient.BillingResponseCode.SERVICE_DISCONNECTED);
        }
        ProductDetails productDetails = skuDetailsINAPMap.get(productId);
        if (BillingKit.isDevMode()) {
            new PurchaseDevBottomSheet(TYPE_IAP.PURCHASE, productDetails, activity, purchaseListener).show();
            return new LaunchOutcome(LaunchResult.DEV_MODE, "", null);
        }

        if (productDetails == null) {
            return new LaunchOutcome(LaunchResult.PRODUCT_NOT_FOUND, "Product ID invalid", null,
                    BillingClient.BillingResponseCode.ITEM_UNAVAILABLE);
        }

        idPurchaseCurrent = productId;
        typeIap = TYPE_IAP.PURCHASE;

        List<BillingFlowParams.ProductDetailsParams> productDetailsParamsList =
                Collections.singletonList(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                                .setProductDetails(productDetails)
                                .build()
                );

        BillingResult billingResult =
                billingClient.launchBillingFlow(activity, billingFlowParams(productDetailsParamsList));

        if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
            offerTokenCurrent = "";
        }
        return mapLaunchResponse(billingResult);
    }

    private LaunchOutcome subscribeInternal(Activity activity, String subsId, String offerToken,
                                            boolean offerTokenRequired) {
        if (BillingKit.isDevMode()) {
            dispatch(purchaseInternal(activity, PRODUCT_ID_TEST));
            return new LaunchOutcome(LaunchResult.DEV_MODE, "Billing test", null);
        }
        if (skuListSubsFromStore == null || billingClient == null) {
            return new LaunchOutcome(LaunchResult.BILLING_NOT_READY, "", "Billing error init",
                    BillingClient.BillingResponseCode.SERVICE_DISCONNECTED);
        }

        ProductDetails productDetails = skuDetailsSubsMap.get(subsId);
        if (productDetails == null) {
            return new LaunchOutcome(LaunchResult.PRODUCT_NOT_FOUND, "Product ID invalid", null,
                    BillingClient.BillingResponseCode.ITEM_UNAVAILABLE);
        }

        if (offerTokenRequired) {
            if (offerToken == null || offerToken.isEmpty()) {
                return new LaunchOutcome(LaunchResult.OFFER_TOKEN_REQUIRED, "Offer token is required", null,
                        BillingClient.BillingResponseCode.DEVELOPER_ERROR);
            }
        } else if (offerToken == null || offerToken.isEmpty()) {
            List<ProductDetails.SubscriptionOfferDetails> subsDetail = productDetails.getSubscriptionOfferDetails();
            if (subsDetail == null || subsDetail.isEmpty()) {
                return new LaunchOutcome(LaunchResult.NO_OFFER, "No available offers for this subscription", null,
                        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE);
            }
            offerToken = getOfferToken(subsDetail);
        }

        idPurchaseCurrent = subsId;
        typeIap = TYPE_IAP.SUBSCRIPTION;

        List<BillingFlowParams.ProductDetailsParams> productDetailsParamsList =
                Collections.singletonList(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                                .setProductDetails(productDetails)
                                .setOfferToken(offerToken)
                                .build()
                );

        BillingResult billingResult =
                billingClient.launchBillingFlow(activity, billingFlowParams(productDetailsParamsList));
        offerTokenCurrent = offerToken;

        return mapLaunchResponse(billingResult);
    }

    private BillingFlowParams billingFlowParams(List<BillingFlowParams.ProductDetailsParams> params) {
        BillingFlowParams.Builder builder = BillingFlowParams.newBuilder().setProductDetailsParamsList(params);
        if (obfuscatedAccountId != null && !obfuscatedAccountId.isEmpty()) {
            builder.setObfuscatedAccountId(obfuscatedAccountId);
        }
        if (obfuscatedProfileId != null && !obfuscatedProfileId.isEmpty()) {
            builder.setObfuscatedProfileId(obfuscatedProfileId);
        }
        return builder.build();
    }

    /**
     * The legacy strings are reproduced verbatim so existing partners that match on them keep
     * behaving the same.
     */
    private LaunchOutcome mapLaunchResponse(BillingResult billingResult) {
        int code = billingResult.getResponseCode();
        switch (code) {
            case BillingClient.BillingResponseCode.OK:
                return new LaunchOutcome(LaunchResult.LAUNCHED, "Subscribed Successfully", null, code);

            case BillingClient.BillingResponseCode.BILLING_UNAVAILABLE:
                return new LaunchOutcome(LaunchResult.ERROR, "Billing not supported for type of request",
                        "Billing not supported for type of request", code);

            case BillingClient.BillingResponseCode.ITEM_NOT_OWNED:
            case BillingClient.BillingResponseCode.DEVELOPER_ERROR:
                return new LaunchOutcome(LaunchResult.ERROR, "", null, code);

            case BillingClient.BillingResponseCode.ERROR:
                return new LaunchOutcome(LaunchResult.ERROR, "Error completing request", "Error completing request", code);

            case BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED:
                return new LaunchOutcome(LaunchResult.ERROR, "Error processing request.", null, code);

            case BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED:
                return new LaunchOutcome(LaunchResult.ITEM_ALREADY_OWNED, "Selected item is already owned", null, code);

            case BillingClient.BillingResponseCode.ITEM_UNAVAILABLE:
                return new LaunchOutcome(LaunchResult.PRODUCT_NOT_FOUND, "Item not available", null, code);

            case BillingClient.BillingResponseCode.SERVICE_DISCONNECTED:
                return new LaunchOutcome(LaunchResult.BILLING_NOT_READY, "Play Store service is not connected now", null, code);

            case BillingClient.BillingResponseCode.SERVICE_TIMEOUT:
                return new LaunchOutcome(LaunchResult.NETWORK_ERROR, "Timeout", null, code);

            case BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE:
                return new LaunchOutcome(LaunchResult.NETWORK_ERROR, "Network Connection down", "Network error.", code);

            case BillingClient.BillingResponseCode.USER_CANCELED:
                return new LaunchOutcome(LaunchResult.USER_CANCELED, "Request Canceled", "Request Canceled", code);

            default:
                return new LaunchOutcome(LaunchResult.ERROR, "", null, code);
        }
    }

    private LaunchOutcome dispatch(LaunchOutcome outcome) {
        if (outcome.legacyErrorMessage != null && purchaseListener != null) {
            purchaseListener.displayErrorMessage(outcome.legacyErrorMessage);
        }
        if (outcome.result == LaunchResult.USER_CANCELED) {
            for (PurchaseCallback callback : purchaseCallbacks) {
                callback.onUserCancelBilling();
            }
        } else if (outcome.result == LaunchResult.ITEM_ALREADY_OWNED) {
            // Play already granted it, so restore silently instead of reporting an unactionable error
            verifyPurchased(false);
            fanOutAlreadyOwned(idPurchaseCurrent);
        } else if (outcome.result != LaunchResult.LAUNCHED && outcome.result != LaunchResult.DEV_MODE) {
            notifyCallbacksError(outcome.responseCode, outcome.result.getMessage());
        }
        return outcome;
    }

    /**
     * Carries both the typed result and the string the deprecated methods have always returned.
     */
    private static final class LaunchOutcome {
        final LaunchResult result;
        final String legacyMessage;
        /**
         * Non-null only where the legacy code called displayErrorMessage, so the fan-out stays
         * byte-for-byte compatible.
         */
        final String legacyErrorMessage;
        final int responseCode;

        LaunchOutcome(LaunchResult result, String legacyMessage, String legacyErrorMessage) {
            this(result, legacyMessage, legacyErrorMessage, BillingClient.BillingResponseCode.ERROR);
        }

        LaunchOutcome(LaunchResult result, String legacyMessage, String legacyErrorMessage, int responseCode) {
            this.result = result;
            this.legacyMessage = legacyMessage;
            this.legacyErrorMessage = legacyErrorMessage;
            this.responseCode = responseCode;
        }
    }

    private String getOfferToken(List<ProductDetails.SubscriptionOfferDetails> subsDetail) {
        String offerToken = null;
        for (ProductDetails.SubscriptionOfferDetails offer : subsDetail) {
            List<ProductDetails.PricingPhase> pricingPhases = offer.getPricingPhases().getPricingPhaseList();
            for (ProductDetails.PricingPhase phase : pricingPhases) {
                if (phase.getPriceAmountMicros() == 0L) {
                    offerToken = offer.getOfferToken();
                    break;
                }
            }
            if (offerToken != null) break;
        }

        if (offerToken == null) {
            offerToken = subsDetail.get(0).getOfferToken();
        }
        offerTokenCurrent = offerToken;
        return offerToken;
    }

    /**
     * Deterministic offer pick: exact base plan, then exact offer, then any free trial, then the
     * first offer. Every fallback is logged so a mismatched catalog is visible.
     *
     * @return the offer token, or "" when the product has no offers
     */
    public String resolveOfferToken(String productId, String basePlanId, String offerId) {
        ProductDetails skuDetails = skuDetailsSubsMap.get(productId);
        if (skuDetails == null) {
            Log.e(TAG, "resolveOfferToken: unknown subscription " + productId);
            return "";
        }
        List<ProductDetails.SubscriptionOfferDetails> offers = skuDetails.getSubscriptionOfferDetails();
        if (offers == null || offers.isEmpty()) {
            Log.e(TAG, "resolveOfferToken: no offers on " + productId);
            return "";
        }

        if (basePlanId != null && !basePlanId.isEmpty()) {
            for (ProductDetails.SubscriptionOfferDetails offer : offers) {
                if (basePlanId.equals(offer.getBasePlanId())
                        && (offerId == null || offerId.isEmpty() || offerId.equals(offer.getOfferId()))) {
                    return offer.getOfferToken();
                }
            }
            Log.w(TAG, "resolveOfferToken: base plan " + basePlanId + " not found on " + productId);
        }

        if (offerId != null && !offerId.isEmpty()) {
            for (ProductDetails.SubscriptionOfferDetails offer : offers) {
                if (offerId.equals(offer.getOfferId())) {
                    return offer.getOfferToken();
                }
            }
            Log.w(TAG, "resolveOfferToken: offer " + offerId + " not found on " + productId);
        }

        String freeTrialToken = getFreeTrialOfferToken(productId);
        if (freeTrialToken != null) {
            return freeTrialToken;
        }

        Log.w(TAG, "resolveOfferToken: falling back to first offer of " + productId);
        return offers.get(0).getOfferToken();
    }

    public void consumePurchase(String productId) {
        if (billingClient == null || !billingClient.isReady()) {
            Log.e(TAG, "BillingClient is not ready");
            return;
        }

        QueryPurchasesParams params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build();
        billingClient.queryPurchasesAsync(params, (billingResult, list) -> {
            Purchase pc = null;
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && list != null) {
                for (Purchase purchase : list) {
                    if (purchase.getProducts().contains(productId)) {
                        pc = purchase;
                    }
                }
            }

            if (pc == null) {
                Log.e(TAG, "No purchases found to consume.");
                // developer-facing only: this is not a flow the user started, so no error is shown
                notifyCallbacksError(BillingClient.BillingResponseCode.ITEM_NOT_OWNED,
                        "No purchase to consume for " + productId);
                return;
            }

            try {
                ConsumeParams consumeParams =
                        ConsumeParams.newBuilder()
                                .setPurchaseToken(pc.getPurchaseToken())
                                .build();

                billingClient.consumeAsync(consumeParams, (billingResult1, purchaseToken) -> {
                    if (billingResult1.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        Log.e(TAG, "onConsumeResponse: OK");
                        verifyPurchased(false);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "consumePurchase failed for " + productId, e);
            }
        });
    }

    private void handlePurchase(Purchase purchase) {
        final String productId = resolveProductId(purchase);
        final int type = resolveType(productId, typeIap);

        if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) {
            // Play has not taken the money yet, so nothing is owed to the user
            fanOutPurchasePending(productId);
            return;
        }

        PurchaseVerifier verifier = purchaseVerifier;
        if (verifier == null) {
            grantPurchase(purchase, productId, type);
            return;
        }

        verifier.verify(productId, purchase.getPurchaseToken(), purchase.getOriginalJson(),
                (verified, reason) -> {
                    if (verified) {
                        grantPurchase(purchase, productId, type);
                        return;
                    }
                    Log.e(TAG, "handlePurchase: verifier rejected " + productId + " - " + reason);
                    fanOutPurchaseError(BillingClient.BillingResponseCode.DEVELOPER_ERROR,
                            reason == null ? "Purchase verification failed" : reason);
                    BillingTracking.trackPurchaseFail(productId,
                            BillingClient.BillingResponseCode.DEVELOPER_ERROR);
                });
    }

    /**
     * Dev-variant simulated grant. Process-local on purpose: nothing is cached, so the next real
     * verification takes it away again.
     *
     * @param extraListener notified only when it is not the listener the fan-out already reaches
     */
    void grantDevPurchase(String productId, String transactionJson, PurchaseListener extraListener) {
        isPurchase = true;
        idPurchased = productId;
        fanOutProductPurchased(productId, transactionJson);
        if (extraListener != null && extraListener != purchaseListener) {
            extraListener.onProductPurchased(productId, transactionJson);
        }
    }

    private void grantPurchase(Purchase purchase, String productId, int type) {
        if (type != TYPE_IAP.CONSUMABLE) {
            isPurchase = true;
            idPurchased = productId;
            PurchasePrefs.write(appContext, true, "purchase");
        }

        BillingTracking.trackPurchaseSuccess(
                getPriceMicros(productId, type, offerTokenCurrent),
                getCurrency(productId, type, offerTokenCurrent),
                productId,
                type);

        fanOutProductPurchased(purchase.getOrderId(), purchase.getOriginalJson());

        BillingClient client = billingClient;
        if (isConsumePurchase || type == TYPE_IAP.CONSUMABLE) {
            if (client == null) {
                return;
            }
            consumeWithRetry(purchase, 0);
        } else if (!purchase.isAcknowledged()) {
            acknowledgeWithRetry(purchase, 0);
        }
    }

    private void consumeWithRetry(Purchase purchase, int attempt) {
        BillingClient client = billingClient;
        if (client == null) {
            return;
        }
        ConsumeParams params = ConsumeParams.newBuilder()
                .setPurchaseToken(purchase.getPurchaseToken())
                .build();
        client.consumeAsync(params, (billingResult, purchaseToken) -> {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                return;
            }
            if (attempt + 1 >= ACKNOWLEDGE_MAX_ATTEMPTS) {
                Log.e(TAG, "consumeAsync gave up after " + ACKNOWLEDGE_MAX_ATTEMPTS
                        + " attempts, code=" + billingResult.getResponseCode()
                        + " - the product stays owned and Play will reject any re-buy");
                return;
            }
            mainHandler.postDelayed(() -> consumeWithRetry(purchase, attempt + 1),
                    1000L << (attempt + 1));
        });
    }

    private void acknowledgeWithRetry(Purchase purchase, int attempt) {
        BillingClient client = billingClient;
        if (client == null) {
            return;
        }
        AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.getPurchaseToken())
                .build();
        client.acknowledgePurchase(params, billingResult -> {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                return;
            }
            if (attempt + 1 >= ACKNOWLEDGE_MAX_ATTEMPTS) {
                Log.e(TAG, "acknowledgePurchase gave up after " + ACKNOWLEDGE_MAX_ATTEMPTS
                        + " attempts, code=" + billingResult.getResponseCode()
                        + " - Play auto-refunds purchases left unacknowledged for 3 days");
                BillingTracking.trackPurchaseFail(resolveProductId(purchase),
                        billingResult.getResponseCode());
                return;
            }
            mainHandler.postDelayed(() -> acknowledgeWithRetry(purchase, attempt + 1),
                    1000L << (attempt + 1));
        });
    }

    private String resolveProductId(Purchase purchase) {
        List<String> products = purchase.getProducts();
        if (products != null && !products.isEmpty() && products.get(0) != null) {
            return products.get(0);
        }
        return idPurchaseCurrent;
    }

    private int resolveType(String productId, int fallback) {
        if (productId != null) {
            for (PurchaseItem item : purchaseItems) {
                if (productId.equals(item.getItemId())) {
                    return item.getType();
                }
            }
        }
        return fallback;
    }

    private String firstProductId(List<Purchase> list, String fallback) {
        if (list != null && !list.isEmpty()) {
            return resolveProductId(list.get(0));
        }
        return fallback;
    }

    private void fanOutProductPurchased(String orderId, String originalJson) {
        for (PurchaseCallback callback : purchaseCallbacks) {
            callback.onProductPurchased(orderId, originalJson);
        }
        if (purchaseListener != null) {
            purchaseListener.onProductPurchased(orderId, originalJson);
        }
    }

    private void fanOutPurchasePending(String productId) {
        for (PurchaseCallback callback : purchaseCallbacks) {
            callback.onPurchasePending(productId);
        }
        if (purchaseListener != null) {
            // the frozen PurchaseListener has no pending method, and a host waiting on it would hang
            purchaseListener.displayErrorMessage(PENDING_MESSAGE);
        }
    }

    private void fanOutAlreadyOwned(String productId) {
        for (PurchaseCallback callback : purchaseCallbacks) {
            callback.onAlreadyOwned(productId);
        }
    }

    private void fanOutPurchaseError(int responseCode, String message) {
        notifyCallbacksError(responseCode, message);
        if (purchaseListener != null) {
            purchaseListener.displayErrorMessage(message);
        }
    }

    private void notifyCallbacksError(int responseCode, String message) {
        for (PurchaseCallback callback : purchaseCallbacks) {
            callback.onPurchaseError(responseCode, message);
        }
    }

    private void fanOutUserCancelBilling() {
        for (PurchaseCallback callback : purchaseCallbacks) {
            callback.onUserCancelBilling();
        }
        if (purchaseListener != null) {
            purchaseListener.onUserCancelBilling();
        }
    }

    public String getPrice(String productId) {
        ProductDetails skuDetails = skuDetailsINAPMap.get(productId);
        if (skuDetails == null) {
            return "";
        }
        ProductDetails.OneTimePurchaseOfferDetails offerDetails = skuDetails.getOneTimePurchaseOfferDetails();
        if (offerDetails == null) {
            return "";
        }
        return offerDetails.getFormattedPrice();
    }

    public String getPrice(String productId, int typeIap, String offerToken) {
        if (isOneTimeType(typeIap)) {
            return getPrice(productId);
        }
        return getPriceSub(productId, offerToken);
    }

    /**
     * Renewal price: the LAST pricing phase of the LAST offer, i.e. what the user pays once any
     * trial or introductory phase has run out.
     *
     * @return formatted price, or "" when the product or its offers are unknown
     */
    public String getPriceSub(String productId) {
        ProductDetails skuDetails = skuDetailsSubsMap.get(productId);
        if (skuDetails == null) {
            return "";
        }

        List<ProductDetails.SubscriptionOfferDetails> subsDetail = skuDetails.getSubscriptionOfferDetails();
        if (subsDetail == null || subsDetail.isEmpty()) {
            return "";
        }
        List<ProductDetails.PricingPhase> pricingPhaseList =
                subsDetail.get(subsDetail.size() - 1).getPricingPhases().getPricingPhaseList();
        if (pricingPhaseList == null || pricingPhaseList.isEmpty()) {
            return "";
        }
        return pricingPhaseList.get(pricingPhaseList.size() - 1).getFormattedPrice();
    }

    /**
     * Entry price: the FIRST pricing phase of the offer matching {@code offerToken}, i.e. the trial
     * or introductory price — the opposite end of the offer from {@link #getPriceSub(String)},
     * which it falls back to when the token matches nothing.
     */
    public String getPriceSub(String productId, String offerToken) {
        ProductDetails skuDetails = skuDetailsSubsMap.get(productId);
        if (skuDetails == null || skuDetails.getSubscriptionOfferDetails() == null)
            return getPriceSub(productId);

        for (ProductDetails.SubscriptionOfferDetails offer : skuDetails.getSubscriptionOfferDetails()) {
            if (offer.getOfferToken().equals(offerToken)) {
                List<ProductDetails.PricingPhase> phases = offer.getPricingPhases().getPricingPhaseList();
                if (phases != null && !phases.isEmpty()) {
                    return phases.get(0).getFormattedPrice();
                }
            }
        }
        return getPriceSub(productId);
    }

    /**
     * Get all available subscription offers for a product
     *
     * @param productId subscription product ID
     * @return list of SubscriptionOfferDetails or null if not found
     */
    public List<ProductDetails.SubscriptionOfferDetails> getSubscriptionOffers(String productId) {
        ProductDetails skuDetails = skuDetailsSubsMap.get(productId);
        if (skuDetails == null)
            return null;
        return skuDetails.getSubscriptionOfferDetails();
    }

    /**
     * Pricing phases of the last offer, i.e. trial and introductory phases before the renewal one.
     */
    public List<ProductDetails.PricingPhase> getPricePricingPhaseList(String productId) {
        ProductDetails skuDetails = skuDetailsSubsMap.get(productId);
        if (skuDetails == null)
            return null;

        List<ProductDetails.SubscriptionOfferDetails> subsDetail = skuDetails.getSubscriptionOfferDetails();
        if (subsDetail == null || subsDetail.isEmpty()) {
            return null;
        }
        return subsDetail.get(subsDetail.size() - 1).getPricingPhases().getPricingPhaseList();
    }

    /**
     * Localised renewal price, falling back to the one-time price for a product that has both.
     */
    public String getIntroductorySubPrice(String productId) {
        ProductDetails skuDetails = skuDetailsSubsMap.get(productId);
        if (skuDetails == null) {
            return "";
        }
        if (skuDetails.getOneTimePurchaseOfferDetails() != null)
            return skuDetails.getOneTimePurchaseOfferDetails().getFormattedPrice();
        else if (skuDetails.getSubscriptionOfferDetails() != null) {
            List<ProductDetails.SubscriptionOfferDetails> subsDetail = skuDetails.getSubscriptionOfferDetails();
            if (subsDetail.isEmpty()) {
                return "";
            }
            List<ProductDetails.PricingPhase> pricingPhaseList =
                    subsDetail.get(subsDetail.size() - 1).getPricingPhases().getPricingPhaseList();
            if (pricingPhaseList == null || pricingPhaseList.isEmpty()) {
                return "";
            }
            return pricingPhaseList.get(pricingPhaseList.size() - 1).getFormattedPrice();
        } else {
            return "";
        }

    }

    /**
     * ISO 4217 code Play prices this product in for the current user.
     */
    public String getCurrency(String productId, int typeIAP) {
        return getCurrency(productId, typeIAP, "");
    }

    public String getCurrency(String productId, int typeIAP, String offerToken) {
        ProductDetails skuDetails = isOneTimeType(typeIAP) ? skuDetailsINAPMap.get(productId) : skuDetailsSubsMap.get(productId);
        if (skuDetails == null) {
            return "";
        }
        if (isOneTimeType(typeIAP)) {
            ProductDetails.OneTimePurchaseOfferDetails offerDetails = skuDetails.getOneTimePurchaseOfferDetails();
            return offerDetails == null ? "" : offerDetails.getPriceCurrencyCode();
        }

        ProductDetails.SubscriptionOfferDetails targetOffer = targetOffer(skuDetails, offerToken);
        if (targetOffer == null) {
            return "";
        }
        List<ProductDetails.PricingPhase> pricingPhaseList = targetOffer.getPricingPhases().getPricingPhaseList();
        if (pricingPhaseList == null || pricingPhaseList.isEmpty()) {
            return "";
        }
        return pricingPhaseList.get(0).getPriceCurrencyCode();
    }

    public Map<String, ProductDetails> getSkuDetailsINAPMap() {
        return Collections.unmodifiableMap(new HashMap<>(skuDetailsINAPMap));
    }

    public Map<String, ProductDetails> getSkuDetailsSubsMap() {
        return Collections.unmodifiableMap(new HashMap<>(skuDetailsSubsMap));
    }

    /**
     * @deprecated the value is micros, not currency units — use
     * {@link #getPriceMicros(String, int)}.
     */
    @Deprecated
    public double getPriceWithoutCurrency(String productId, int typeIAP) {
        return getPriceMicros(productId, typeIAP, "");
    }

    /**
     * @deprecated the value is micros, not currency units — use
     * {@link #getPriceMicros(String, int, String)}.
     */
    @Deprecated
    public double getPriceWithoutCurrency(String productId, int typeIAP, String offerToken) {
        return getPriceMicros(productId, typeIAP, offerToken);
    }

    public double getPriceMicros(String productId, int typeIAP) {
        return getPriceMicros(productId, typeIAP, "");
    }

    /**
     * @param offerToken subscription offer to price, or "" for the last offer
     * @return price in micros, 0 when the product or offer is unknown
     */
    public double getPriceMicros(String productId, int typeIAP, String offerToken) {
        ProductDetails skuDetails = isOneTimeType(typeIAP) ? skuDetailsINAPMap.get(productId) : skuDetailsSubsMap.get(productId);
        if (skuDetails == null) {
            return 0;
        }
        if (isOneTimeType(typeIAP)) {
            ProductDetails.OneTimePurchaseOfferDetails offerDetails = skuDetails.getOneTimePurchaseOfferDetails();
            return offerDetails == null ? 0 : offerDetails.getPriceAmountMicros();
        }

        ProductDetails.SubscriptionOfferDetails targetOffer = targetOffer(skuDetails, offerToken);
        if (targetOffer == null) {
            return 0;
        }
        List<ProductDetails.PricingPhase> pricingPhaseList = targetOffer.getPricingPhases().getPricingPhaseList();
        if (pricingPhaseList == null || pricingPhaseList.isEmpty()) {
            return 0;
        }
        return pricingPhaseList.get(0).getPriceAmountMicros();
    }

    private static boolean isOneTimeType(int typeIAP) {
        return typeIAP == TYPE_IAP.PURCHASE || typeIAP == TYPE_IAP.CONSUMABLE;
    }

    private static ProductDetails.SubscriptionOfferDetails targetOffer(ProductDetails skuDetails, String offerToken) {
        List<ProductDetails.SubscriptionOfferDetails> subsDetail = skuDetails.getSubscriptionOfferDetails();
        if (subsDetail == null || subsDetail.isEmpty()) {
            return null;
        }
        if (offerToken != null && !offerToken.isEmpty()) {
            for (ProductDetails.SubscriptionOfferDetails offer : subsDetail) {
                if (offer.getOfferToken().equals(offerToken)) {
                    return offer;
                }
            }
        }
        return subsDetail.get(subsDetail.size() - 1);
    }

    /**
     * Formats a price in the device locale, so prefer {@code ProductDetails.getFormattedPrice()}
     * when Play already supplies a string.
     *
     * @param amountMicros price in micros, as Play reports it
     * @param currencyCode ISO 4217 code
     * @return formatted price, or "" when the code is not a known currency
     */
    public static String formatPrice(double amountMicros, String currencyCode) {
        if (currencyCode == null || currencyCode.isEmpty()) {
            return "";
        }
        try {
            NumberFormat format = NumberFormat.getCurrencyInstance();
            format.setCurrency(Currency.getInstance(currencyCode));
            return format.format(amountMicros / 1_000_000d);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "formatPrice: unknown currency " + currencyCode, e);
            return "";
        }
    }

    /**
     * Strike-through price implied by taking {@code discountPercent} off the live price.
     *
     * @param discountPercent 0..99; anything else returns "" rather than dividing by zero
     */
    public String getOldPriceFormatted(String productId, int typeIap, int discountPercent) {
        if (discountPercent < 0 || discountPercent > 99) {
            Log.e(TAG, "getOldPriceFormatted: discountPercent out of range " + discountPercent);
            return "";
        }
        double micros = getPriceMicros(productId, typeIap, "");
        if (micros <= 0) {
            return "";
        }
        return formatPrice(micros / (1 - discountPercent / 100.0), getCurrency(productId, typeIap, ""));
    }

    /**
     * Check if a subscription has free trial offer
     *
     * @param productId subscription product ID
     * @return true if subscription has free trial, false otherwise
     */
    public boolean hasFreeTrial(String productId) {
        return !getFreeTrialPeriod(productId).isEmpty();
    }

    /**
     * Get free trial period for a subscription
     *
     * @param productId subscription product ID
     * @return ISO-8601 period (e.g. "P7D", "P1M"), or "" when there is no trial
     */
    public String getFreeTrialPeriod(String productId) {
        ProductDetails skuDetails = skuDetailsSubsMap.get(productId);
        if (skuDetails == null || skuDetails.getSubscriptionOfferDetails() == null) {
            return "";
        }

        for (ProductDetails.SubscriptionOfferDetails offerDetails : skuDetails.getSubscriptionOfferDetails()) {
            List<ProductDetails.PricingPhase> pricingPhases = offerDetails.getPricingPhases().getPricingPhaseList();
            if (pricingPhases != null && pricingPhases.size() > 1
                    && pricingPhases.get(0).getPriceAmountMicros() == 0) {
                return pricingPhases.get(0).getBillingPeriod();
            }
        }
        return "";
    }

    /**
     * Get formatted free trial period string
     *
     * @param productId subscription product ID
     * @return formatted string (e.g., "7 days free", "1 month free"), or empty string if no trial
     */
    public String getFormattedFreeTrialPeriod(String productId) {
        String period = getFreeTrialPeriod(productId);
        if (period.isEmpty()) {
            return "";
        }
        return formatIsoPeriod(period);
    }

    /**
     * Play emits compound periods such as "P1M15D", which a single Integer.parseInt cannot read.
     */
    private static String formatIsoPeriod(String period) {
        if (!period.startsWith("P")) {
            return period;
        }
        int years = 0, months = 0, weeks = 0, days = 0;
        int value = 0;
        boolean hasDigits = false;
        for (int i = 1; i < period.length(); i++) {
            char c = period.charAt(i);
            if (c >= '0' && c <= '9') {
                value = value * 10 + (c - '0');
                hasDigits = true;
                continue;
            }
            if (!hasDigits) {
                return period;
            }
            switch (c) {
                case 'Y':
                    years += value;
                    break;
                case 'M':
                    months += value;
                    break;
                case 'W':
                    weeks += value;
                    break;
                case 'D':
                    days += value;
                    break;
                default:
                    return period;
            }
            value = 0;
            hasDigits = false;
        }

        StringBuilder label = new StringBuilder();
        appendPeriodUnit(label, years, "year");
        appendPeriodUnit(label, months, "month");
        appendPeriodUnit(label, weeks, "week");
        appendPeriodUnit(label, days, "day");
        if (label.length() == 0) {
            return period;
        }
        return label + " free";
    }

    private static void appendPeriodUnit(StringBuilder label, int amount, String unit) {
        if (amount <= 0) {
            return;
        }
        if (label.length() > 0) {
            label.append(' ');
        }
        label.append(amount).append(' ').append(unit);
        if (amount != 1) {
            label.append('s');
        }
    }

    /**
     * Get the offer token for free trial subscription
     *
     * @param productId subscription product ID
     * @return offer token string, or null if no free trial available
     */
    public String getFreeTrialOfferToken(String productId) {
        ProductDetails skuDetails = skuDetailsSubsMap.get(productId);
        if (skuDetails == null || skuDetails.getSubscriptionOfferDetails() == null) {
            return null;
        }

        for (ProductDetails.SubscriptionOfferDetails offerDetails : skuDetails.getSubscriptionOfferDetails()) {
            List<ProductDetails.PricingPhase> pricingPhases = offerDetails.getPricingPhases().getPricingPhaseList();
            if (pricingPhases != null && pricingPhases.size() > 1
                    && pricingPhases.get(0).getPriceAmountMicros() == 0) {
                return offerDetails.getOfferToken();
            }
        }
        return null;
    }

    /**
     * Purchase subscription with free trial if available
     * If free trial is not available, it will use the default subscription offer
     *
     * @param activity  current activity
     * @param productId subscription product ID
     */
    public void subscribeWithFreeTrial(Activity activity, String productId) {
        if (skuDetailsSubsMap.containsKey(productId)) {
            subscribeProduct(activity, productId, getFreeTrialOfferToken(productId));
        } else {
            Log.e(TAG, "subscribeWithFreeTrial: Product not found - " + productId);
        }
    }

    private double discount = 1;

    /**
     * @deprecated nothing in the SDK reads it; use
     * {@link #getOldPriceFormatted(String, int, int)} to render a discount.
     */
    @Deprecated
    public void setDiscount(double discount) {
        this.discount = discount;
    }

    /**
     * @deprecated nothing in the SDK reads it; use
     * {@link #getOldPriceFormatted(String, int, int)} to render a discount.
     */
    @Deprecated
    public double getDiscount() {
        return discount;
    }

    private ArrayList<QueryProductDetailsParams.Product> listIdToListProduct(List<String> listId,
                                                                            String styleBilling,
                                                                            ArrayList<String> idsOut) {
        ArrayList<QueryProductDetailsParams.Product> listProduct = new ArrayList<QueryProductDetailsParams.Product>();
        idsOut.clear();
        for (String id : listId) {
            QueryProductDetailsParams.Product product = QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(id)
                    .setProductType(styleBilling)
                    .build();
            listProduct.add(product);
            idsOut.add(id);
        }
        return listProduct;
    }

    @IntDef({TYPE_IAP.PURCHASE, TYPE_IAP.SUBSCRIPTION, TYPE_IAP.CONSUMABLE})
    public @interface TYPE_IAP {
        int PURCHASE = 1;
        int SUBSCRIPTION = 2;
        int CONSUMABLE = 3;
    }
}
