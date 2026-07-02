package org.jellyfin.mobile.player.xr

import android.content.Context
import android.os.Build
import timber.log.Timber

/**
 * Helper for detecting Android XR (spatial) capabilities.
 *
 * This version uses standard Android APIs without reflection.
 */
object XrSupportHelper {
    /**
     * Returns true if the device supports Android XR spatial features.
     * Safe to call on any API level.
     */
    fun isXrSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            val pm = context.packageManager
            pm.hasSystemFeature("android.software.xr.api.spatial")
        } catch (e: Exception) {
            Timber.v("XR not available: %s", e.message)
            false
        }
    }
}
