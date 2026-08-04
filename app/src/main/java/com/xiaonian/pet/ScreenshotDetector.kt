package com.xiaonian.pet

import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * 截图检测：监听系统截图目录（Pictures/Screenshots 与 Pictures）。
 * 拍到截图时桌宠摆pose。注意回调在后台线程，必须切主线程操作WebView。
 */
class ScreenshotDetector(
    private val context: Context,
    private val onScreenshot: () -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var observer: FileObserver? = null

    fun start() {
        val pictures = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_PICTURES)
        val dirs = listOf(
            File(pictures, "Screenshots"),
            pictures
        ).filter { it.exists() }

        if (dirs.isEmpty()) return
        val dir = dirs.first()
        observer = object : FileObserver(dir.absolutePath, FileObserver.CLOSE_WRITE or FileObserver.CREATE) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return
                if (path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg")) {
                    mainHandler.post(onScreenshot)
                }
            }
        }.apply { startWatching() }
        Log.i(TAG, "watching screenshots: ${dir.absolutePath}")
    }

    fun stop() { observer?.stopWatching() }

    companion object { private const val TAG = "ScreenshotDetector" }
}