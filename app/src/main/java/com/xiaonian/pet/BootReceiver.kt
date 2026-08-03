package com.xiaonian.pet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * 开机自启：重启后如果悬浮窗权限还在，就自己爬出来。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            if (Settings.canDrawOverlays(context)) {
                context.startForegroundService(Intent(context, OverlayService::class.java))
            }
        } catch (e: Exception) {
            Log.w(TAG, "boot start failed: ${e.message}")
        }
    }
    companion object { private const val TAG = "BootReceiver" }
}