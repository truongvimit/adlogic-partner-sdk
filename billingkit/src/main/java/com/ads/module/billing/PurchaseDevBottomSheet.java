package com.ads.module.billing;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.ads.module.billingkit.R;
import com.ads.module.funtion.PurchaseListener;
import com.android.billingclient.api.ProductDetails;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.List;

public class PurchaseDevBottomSheet extends BottomSheetDialog {
    private static final String TAG = "PurchaseDevBottomSheet";
    private static final String DEV_TRANSACTION_JSON =
            "{\"productId\":\"%s\",\"type\":\"inapp\",\"purchaseState\":0,"
                    + "\"purchaseToken\":\"dev-purchase-token\",\"acknowledged\":false}";

    private ProductDetails productDetails;
    private int typeIap;
    private TextView txtTitle;
    private TextView txtDescription;
    private TextView txtId;
    private TextView txtPrice;
    private TextView txtContinuePurchase;
    private PurchaseListener purchaseListener;

    /**
     * On a non-dev variant the sheet renders but grants nothing — it would otherwise hand out
     * premium without Play.
     */
    public PurchaseDevBottomSheet(int typeIap, ProductDetails productDetails, @NonNull Context context, PurchaseListener purchaseListener) {
        super(context);
        if (!BillingKit.isDevMode()) {
            Log.e(TAG, "PurchaseDevBottomSheet outside a dev variant: no purchase will be granted");
        }
        this.productDetails = productDetails;
        this.typeIap = typeIap;
        this.purchaseListener = purchaseListener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.bk_view_billing_test);
        txtTitle = findViewById(R.id.bk_txt_title);
        txtDescription = findViewById(R.id.bk_txt_description);
        txtId = findViewById(R.id.bk_txt_id);
        txtPrice = findViewById(R.id.bk_txt_price);
        txtContinuePurchase = findViewById(R.id.bk_txt_continue_purchase);
        if (productDetails == null) {
            if (BillingKit.isDevMode()) {
                txtContinuePurchase.setOnClickListener(v -> {
                    grantTestPurchase(AppPurchase.PRODUCT_ID_TEST);
                    dismiss();
                });
            } else {
                txtContinuePurchase.setOnClickListener(v -> {
                    dismiss();
                });
            }
        } else {
            txtTitle.setText(productDetails.getTitle());
            txtDescription.setText(productDetails.getDescription());
            txtId.setText(productDetails.getProductId());
            String price = "$2.00";
            if (typeIap == AppPurchase.TYPE_IAP.SUBSCRIPTION) {
                List<ProductDetails.SubscriptionOfferDetails> subscriptionOfferDetails = productDetails.getSubscriptionOfferDetails();
                if (subscriptionOfferDetails != null && !subscriptionOfferDetails.isEmpty()) {
                    price = subscriptionOfferDetails.get(0).getPricingPhases().getPricingPhaseList().get(0).getFormattedPrice();
                }
                txtPrice.setText(price);
            } else {
                ProductDetails.OneTimePurchaseOfferDetails oneTimePurchaseOfferDetails = productDetails.getOneTimePurchaseOfferDetails();
                if (oneTimePurchaseOfferDetails != null) {
                    price = oneTimePurchaseOfferDetails.getFormattedPrice();
                }
                txtPrice.setText(price);
            }

            txtContinuePurchase.setOnClickListener(v -> {
                if (BillingKit.isDevMode()) {
                    grantTestPurchase(productDetails.getProductId());
                }
                dismiss();
            });
        }
    }

    private void grantTestPurchase(String productId) {
        AppPurchase.getInstance().grantDevPurchase(productId,
                String.format(DEV_TRANSACTION_JSON, productId), purchaseListener);
    }

    @Override
    protected void onStart() {
        super.onStart();
        int w = ViewGroup.LayoutParams.MATCH_PARENT;
        int h = ViewGroup.LayoutParams.WRAP_CONTENT;
        getWindow().setLayout(w, h);
    }
}
