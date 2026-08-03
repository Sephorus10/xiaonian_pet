package com.xiaonian.pet

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 前台 App 检测：每3秒轮询 UsageStatsManager。
 * 切换前台app时回调 onSwitch(pkg, name)。某些ROM需手动授予"使用情况访问"权限。
 */
class AppUsageDetector(
    private val context: Context,
    private val onSwitch: (pkg: String, name: String?) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var lastPkg: String? = null

    @Volatile var currentPackage: String? = null
        private set

    private val poller = object : Runnable {
        override fun run() {
            try { poll() } catch (e: Exception) { Log.w(TAG, "usage poll failed: ${e.message}") }
            handler.postDelayed(this, 3000)
        }
    }

    fun start(pollMs: Long) {
        handler.removeCallbacks(poller)
        handler.postDelayed(poller, pollMs)
    }

    private fun poll() {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val begin = end - 10_000
        val events = usm.queryEvents(begin, end)
        var pkg: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) pkg = event.packageName
        }
        currentPackage = pkg
        if (pkg != null && pkg != lastPkg) {
            lastPkg = pkg
            val name = try {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) { null }
            onSwitch(pkg, name)
        }
    }

    fun stop() { handler.removeCallbacks(poller) }

    companion object { private const val TAG = "AppUsageDetector" }
}