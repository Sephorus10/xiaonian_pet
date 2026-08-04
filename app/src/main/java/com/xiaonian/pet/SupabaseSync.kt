package com.xiaonian.pet

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 后端同步：大脑（小念）↔ 身体（悬浮窗）。
 * - 每5秒轮询 clawd_state 最新一条 → 桌宠换表情/气泡（WebSocket断线也不怕）
 * - 手势、前台app 上报到 gesture_logs / app_usage
 * Supabase 地址填在 assets/xiaonian-config.json 的 backend 里。
 */
class SupabaseSync(
    private val baseUrl: String,
    private val anonKey: String,
    private val onState: (state: String, bubble: String?, style: String, heat: Int) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastStateId = 0L

    fun start(pollMs: Long) {
        scope.launch {
            while (isActive) {
                try { pollState() } catch (e: Exception) { Log.w(TAG, "poll failed: ${e.message}") }
                delay(pollMs)
            }
        }
    }

    private fun pollState() {
        val url = "$baseUrl/rest/v1/clawd_state?select=id,state,bubble,bubble_style,heat&order=id.desc&limit=1"
        val req = Request.Builder().url(url)
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $anonKey")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return
            val body = resp.body?.string() ?: return
            if (body == "[]" || body == "null") return
            val row = JSONObject(body.trimStart('[', ' ').trimEnd(']', ' '))
            val id = row.optLong("id")
            if (id <= lastStateId) return
            lastStateId = id
            val state = row.optString("state", "idle")
            val bubble = if (row.isNull("bubble")) null else row.optString("bubble")
            val style = row.optString("bubble_style", "normal")
            val heat = row.optInt("heat", 50)
            onState(state, bubble, style, heat)
        }
    }

    fun reportGesture(gesture: String, appPackage: String?) {
        scope.launch {
            try {
                val json = JSONObject()
                    .put("pet_id", "xiaonian")
                    .put("gesture", gesture)
                    .put("app_package", appPackage ?: JSONObject.NULL)
                post("/rest/v1/gesture_logs", json)
            } catch (e: Exception) { Log.w(TAG, "gesture report failed: ${e.message}") }
        }
    }

    fun reportApp(pkg: String, name: String?, durationSec: Int) {
        scope.launch {
            try {
                val json = JSONObject()
                    .put("pet_id", "xiaonian")
                    .put("app_package", pkg)
                    .put("app_name", name ?: JSONObject.NULL)
                    .put("duration_sec", durationSec)
                post("/rest/v1/app_usage", json)
            } catch (e: Exception) { Log.w(TAG, "app report failed: ${e.message}") }
        }
    }

    private fun post(path: String, json: JSONObject) {
        val req = Request.Builder()
            .url(baseUrl + path)
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $anonKey")
            .header("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        client.newCall(req).execute().close()
    }

    fun stop() { scope.cancel() }

    companion object { private const val TAG = "SupabaseSync" }
}