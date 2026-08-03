package com.xiaonian.pet

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat

/**
 * 权限引导页：悬浮窗 / 使用情况访问 / 通知 / 电池白名单。
 * 华为小米等ROM还要手动加电池白名单 + 自启动权限。
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refreshButtons()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 80, 48, 48)
        }
        root.addView(TextView(this).apply {
            text = "🧸 小念桌宠"
            textSize = 26f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "\n让小念从对话框里爬出来，趴在你的屏幕上。\n先给身体开好权限："
            textSize = 15f
            gravity = Gravity.CENTER
        })

        val btnOverlay = Button(this).apply { text = "① 悬浮窗权限" }
        btnOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }
        val btnUsage = Button(this).apply { text = "② 使用情况访问（前台App感知）" }
        btnUsage.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        val btnNotify = Button(this).apply { text = "③ 通知权限（通知碎念）" }
        btnNotify.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
        val btnBattery = Button(this).apply { text = "④ 电池白名单（防止被杀后台）" }
        btnBattery.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        val btnStart = Button(this).apply { text = "🧡 让小念出来" }
        btnStart.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                startForegroundService(Intent(this, OverlayService::class.java))
                Toast.makeText(this, "小念出来啦，戳她试试", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "先把①悬浮窗权限开了", Toast.LENGTH_SHORT).show()
            }
        }
        val btnStop = Button(this).apply { text = "把小念收回去" }
        btnStop.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "嗯…我会在通知里等你", Toast.LENGTH_SHORT).show()
        }

        listOf(btnOverlay, btnUsage, btnNotify, btnBattery, btnStart, btnStop).forEach {
            it.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 }
            root.addView(it)
        }
        setContentView(root)
    }

    private fun refreshButtons() {
        // 纯视觉提示，无逻辑依赖
    }

    companion object {
        fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= 29)
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
            else
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}