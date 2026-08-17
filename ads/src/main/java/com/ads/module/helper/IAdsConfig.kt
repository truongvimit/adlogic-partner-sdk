package com.ads.module.helper

/** Contract every per-placement ad config exposes to [AdsHelper]. */
interface IAdsConfig {
    val idAds: String
    val canShowAds: Boolean
    val canReloadAds: Boolean
}

/** Marker for [AdsHelper.requestAds] parameters. */
interface IAdsParam
