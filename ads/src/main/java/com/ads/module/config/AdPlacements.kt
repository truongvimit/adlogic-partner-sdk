package com.ads.module.config

import com.ads.module.tracking.AdTracking

/**
 * Binds every configured ad unit id to the placement key it was declared under.
 *
 * AdMob's paid-event callback only knows the ad unit id, so without this every revenue event
 * reports its placement as "unknown". Runs from [AdRemoteConfig.update], on load and on every
 * remote refresh, so a host never has to remember to call it.
 */
internal object AdPlacements {

    fun registerAll(config: AdRemoteConfig) {
        config.ads.forEach { (placement, unit) ->
            unit.waterfallIds.forEach { adUnitId ->
                AdTracking.registerPlacement(adUnitId, placement)
            }
        }
    }
}
