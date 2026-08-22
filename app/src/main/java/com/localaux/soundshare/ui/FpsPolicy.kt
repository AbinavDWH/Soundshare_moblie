package com.localaux.soundshare.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.Context

/** Design doc §6 FPS policy */
object FpsPolicy {

    fun isLowEnd(ctx: Context): Boolean =
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice

    fun apply(activity: Activity, userEnabledHighFps: Boolean) {
        val refreshRate = activity.display?.refreshRate ?: 60f
        val maxFps = when {
            refreshRate >= 120f && !isLowEnd(activity) && userEnabledHighFps -> 120f
            else -> 60f
        }
        val params = activity.window.attributes
        params.preferredRefreshRate = maxFps
        activity.window.setAttributes(params)
    }
}