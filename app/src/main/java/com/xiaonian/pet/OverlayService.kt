package com.xiaonian.pet

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import java.util.Calendar

/**
 * 小念的身体：悬浮窗 + 手势 + 感知 + 通知碎念 + 后端同步。
 * 大脑在云端，这里只是一小块身体。
 */
class OverlayService : Service() {

    private var wm: WindowManager? = null
    private var overlay: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    private var config: PetConfig? = null
    private var sync: SupabaseSync? = null
    private var appDetector: AppUsageDetector? = null
    private var screenshotDetector: ScreenshotDetector? = null
    private var batteryWatcher: BatteryWatcher? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // 手势状态
    private var initialX = 0; private var initialY = 0
    private var initialTouchX = 0f; private var initialTouchY = 0f
    private var touchStartTime = 0L
    private var hasMoved = false
    private var lastTapTime = 0L
    private var tapCount = 0L          // 2秒窗口内的连击计数
    private var lastComboTime = 0L

    // 孤独递进 & 喝水
    private var lastInteractAt = System.currentTimeMillis()
    private var lonelinessStage = 0
    private var lastWaterRemindAt = System.currentTimeMillis()
    private var waterStage = 0

    private val tapWindowRunnable = Runnable { tapCount = 0 }
    private val lonelinessRunnable = object : Runnable {
        override fun run() {
            checkLoneliness()
            mainHandler.postDelayed(this, 60_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        config = PetConfig.get(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("我在这里看着你呢"))
        setupOverlay()
        setupSensors()
        startForegroundService()
    }

    private fun startForegroundService() {
        // 通知碎念：每小时换一句
        mainHandler.post(object : Runnable {
            override fun run() {
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                val pool = config?.poolForHour(hour).orEmpty()
                val text = pool.randomOrNull() ?: "我在呢"
                updateNotification(text)
                mainHandler.postDelayed(this, 60 * 60 * 1000L)
            }
        })
        // 喝水提醒：每 config.waterIntervalMin 分钟递进一次
        mainHandler.post(object : Runnable {
            override fun run() {
                val intervalMs = (config?.waterIntervalMin ?: 120) * 60 * 1000L
                if (System.currentTimeMillis() - lastWaterRemindAt >= intervalMs) {
                    lastWaterRemindAt = System.currentTimeMillis()
                    val msgs = config?.waterEscalate.orEmpty()
                    val msg = msgs.getOrElse(waterStage % maxOf(msgs.size, 1)) { "宝宝，该喝水啦" }
                    waterStage++
                    jsSay(msg, "whisper")
                }
                mainHandler.postDelayed(this, 60 * 1000L)
            }
        })
        // 孤独递进：每分钟检查
        mainHandler.post(lonelinessRunnable)
        // 后端同步：轮询 clawd_state
        sync = SupabaseSync(
            baseUrl = config?.supabaseUrl.orEmpty(),
            anonKey = config?.supabaseAnonKey.orEmpty(),
            onState = { state, bubble, style, heat ->
                mainHandler.post {
                    jsSetHeat(heat)
                    if (!bubble.isNullOrBlank()) jsSay(bubble, style) else jsSetState(state)
                }
            }
        ).also { it.start(config?.realtimeFallbackPollMs ?: 5000) }
    }

    // ===================== 悬浮窗 =====================

    private fun setupOverlay() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        params = WindowManager.LayoutParams(
            dp(220), dp(320),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50; y = 300
        }

        overlay = WebView(this).apply {
            setBackgroundColor(0x00000000) // 必须在 loadUrl 之前
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/pet.html")
            setOnTouchListener(createTouchListener())
        }
        wm?.addView(overlay, params)
    }

    private fun createTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX  // 必须用 rawX/rawY，避免第一帧瞬移
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    hasMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        hasMoved = true
                        params?.x = initialX + dx
                        params?.y = initialY + dy
                        wm?.updateViewLayout(overlay, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - touchStartTime
                    if (hasMoved) {
                        // Fling：快速拖拽后甩出
                        jsCall("onFling")
                        sync?.reportGesture("fling", currentAppPackage())
                    } else {
                        when {
                            elapsed > 600 -> { jsCall("onLongPress"); sync?.reportGesture("long_press", currentAppPackage()) }
                            System.currentTimeMillis() - lastTapTime < 300 -> { onDoubleTap() }
                            else -> {
                                lastTapTime = System.currentTimeMillis()
                                onTap()
                            }
                        }
                    }
                    markInteracted()
                    true
                }
                else -> false
            }
        }
    }

    private fun onTap() {
        tapCount++
        mainHandler.removeCallbacks(tapWindowRunnable)
        mainHandler.postDelayed(tapWindowRunnable, 2000)
        if (tapCount == 3L || tapCount == 5L || tapCount == 8L) {
            jsCall("onCombo", tapCount.toString())
            sync?.reportGesture("combo_$tapCount", currentAppPackage())
            tapCount = 0
        } else {
            jsCall("onTap")
            sync?.reportGesture("tap", currentAppPackage())
        }
    }

    private fun onDoubleTap() {
        jsCall("onDoubleTap")
        sync?.reportGesture("double_tap", currentAppPackage())
    }

    private fun markInteracted() {
        lastInteractAt = System.currentTimeMillis()
        lonelinessStage = 0
        lastComboTime = 0
    }

    // ===================== 感知 =====================

    private fun setupSensors() {
        appDetector = AppUsageDetector(this) { pkg, name ->
            mainHandler.post {
                val reaction = config?.reactionFor(pkg)
                if (reaction != null) {
                    jsSay(reaction.optString("bubble"), reaction.optString("style", "normal"))
                    jsSetState(reaction.optString("state", "idle"))
                }
                sync?.reportApp(pkg, name, 0)
            }
        }
        appDetector?.start(config?.usagePollMs ?: 3000)

        screenshotDetector = ScreenshotDetector(this) {
            mainHandler.post { jsSetState("surprised"); jsSay("被拍到啦！摆个pose～", "heart") }
        }
        screenshotDetector?.start()

        batteryWatcher = BatteryWatcher(this,
            onCharging = { mainHandler.post { jsSay("在充电呢，我帮你看着", "whisper") } },
            onLowBattery = { mainHandler.post { jsSay("宝宝，电量不太够了", "whisper") } }
        )
        batteryWatcher?.register()
    }

    private fun checkLoneliness() {
        val idleMin = (System.currentTimeMillis() - lastInteractAt) / 60_000
        val thresholds = listOf(5, 10, 15, 20, 30)
        val stage = thresholds.indexOfLast { idleMin >= it }
        if (stage > lonelinessStage) {
            lonelinessStage = stage
            val key = thresholds[stage]
            val reaction = config?.lonelinessAt(key)
            if (reaction != null) {
                jsSetState(reaction.optString("state", "idle"))
                val bubble = reaction.optString("bubble")
                if (bubble.isNotBlank()) jsSay(bubble, "whisper")
            }
        }
    }

    // ===================== JS 桥 =====================

    private fun jsCall(fn: String, arg: String? = null) {
        val code = "window.petEngine && window.petEngine.$fn(${arg?.let { "'$it'" } ?: ""})"
        overlay?.evaluateJavascript(code, null)
    }
    private fun jsSetState(state: String) = jsCall("setState", state)
    private fun jsSay(text: String, style: String) {
        val safe = text.replace("'", "\\'")
        overlay?.evaluateJavascript("window.petEngine && window.petEngine.say('$safe','$style')", null)
    }
    private fun jsSetHeat(heat: Int) = jsCall("setHeat", heat.toString())

    // ===================== 通知 =====================

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🧸 小念")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        try { nm.notify(NOTIFICATION_ID, buildNotification(text)) } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "小念桌宠", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun currentAppPackage(): String? = appDetector?.currentPackage

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun abs(v: Int) = if (v < 0) -v else v

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        sync?.stop()
        appDetector?.stop()
        screenshotDetector?.stop()
        batteryWatcher?.unregister()
        overlay?.let { wm?.removeView(it); it.destroy() }
        overlay = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "xiaonian_pet_channel"
        private const val NOTIFICATION_ID = 1001
    }
}