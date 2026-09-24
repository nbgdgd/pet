package com.aniblaze.app.ads

import android.app.Activity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ads are disabled. Kept as a no-op (rather than deleted) so the two call sites
 * — [AniBlazeApp] and [MainActivity] — stay unchanged: [initialize] does nothing
 * and [showThen] runs the continuation immediately, so playback starts with no
 * interstitial.
 */
@Singleton
class AdManager @Inject constructor() {

    fun initialize() = Unit

    fun showThen(activity: Activity, onContinue: () -> Unit) = onContinue()
}
