package com.xiaonian.pet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * 充电 / 断电 / 低电量 感知。Battery API 广播驱动。
 */
class BatteryWatcher(
    private val context: Context,
    private val onCharging: () -> Unit,
    private val onLowBattery: () -> Unit
) {
    private var wasCharging: Boolean? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            if (wasCharging != null && charging != wasCharging) {
                if (charging) onCharging()
            }
            wasCharging = charging
            if (level in 1..15) onLowBattery()
        }
    }

    fun register() {
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    fun unregister() {
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
    }
}