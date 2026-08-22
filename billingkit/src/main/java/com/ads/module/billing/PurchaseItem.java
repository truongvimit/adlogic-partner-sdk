package com.ads.module.billing;

/**
 * One product the SDK should query, with the subscription offer coordinates Play needs to pick a
 * concrete offer.
 */
public class PurchaseItem {

    private final String itemId;
    private final String basePlanId;
    private final String offerId;
    private final int type;

    /**
     * @param type one of {@link AppPurchase.TYPE_IAP}
     */
    public PurchaseItem(String itemId, int type) {
        this(itemId, "", "", type);
    }

    /**
     * @param basePlanId base plan to launch, or empty to let the SDK resolve one
     * @param offerId    offer within the base plan, or empty for the base plan itself
     * @param type       one of {@link AppPurchase.TYPE_IAP}
     */
    public PurchaseItem(String itemId, String basePlanId, String offerId, int type) {
        this.itemId = itemId;
        this.basePlanId = basePlanId == null ? "" : basePlanId;
        this.offerId = offerId == null ? "" : offerId;
        this.type = type;
    }

    public String getItemId() {
        return itemId;
    }

    public String getBasePlanId() {
        return basePlanId;
    }

    public String getOfferId() {
        return offerId;
    }

    public int getType() {
        return type;
    }
}
