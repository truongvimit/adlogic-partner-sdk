package com.ads.module.util;

public class AppUtil {
    // ERainAd.init overwrites this from the app's config. Billing can start before that call, so a
    // release build that never runs ERainAd.init simulates purchases — initBilling logs it loudly.
    public static Boolean VARIANT_DEV = true;
}
