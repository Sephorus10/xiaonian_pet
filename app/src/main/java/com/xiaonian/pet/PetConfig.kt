package com.xiaonian.pet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 小念专属配置：从 assets/xiaonian-config.json 读取。
 * 词池、App反应、时段、喝水、后端地址全在这，改配置不用重新编译。
 */
class PetConfig private constructor(private val root: JSONObject) {

    val petName: String = root.getJSONObject("pet").getString("name")

    // ---- 碎念词池 ----
    fun murmurPool(key: String): List<String> =
        root.getJSONObject("murmur_pools").optJSONArray(key)?.toStringList() ?: emptyList()

    fun poolForHour(hour: Int): List<String> =
        if (hour >= 23 || hour < 6) murmurPool("night") else murmurPool(listOf("daily", "clingy", "chaos").random())

    // ---- App 反应映射 ----
    fun reactionFor(pkg: String): JSONObject? =
        root.getJSONObject("app_reactions").optJSONObject(pkg)

    // ---- 快速切换 ----
    val quickSwitchWindowSec: Int = root.getJSONObject("quick_switch").getInt("window_sec")
    val quickSwitchCount: Int = root.getJSONObject("quick_switch").getInt("count")
    val quickSwitchCooldownSec: Int = root.getJSONObject("quick_switch").getInt("cooldown_sec")

    // ---- 喝水 ----
    val waterIntervalMin: Int = root.getJSONObject("water_reminder").getInt("interval_min")
    val waterEscalate: List<String> = root.getJSONObject("water_reminder").getJSONArray("escalate").toStringList()

    // ---- 孤独递进 ----
    fun lonelinessAt(min: Int): JSONObject? = root.getJSONObject("loneliness").optJSONObject(min.toString())

    // ---- 时段 ----
    fun periodFor(hour: Int): JSONObject? {
        val periods = root.getJSONObject("time_periods")
        for (key in periods.keys()) {
            val p = periods.getJSONObject(key)
            val start = p.getInt("start"); val end = p.getInt("end")
            if (hour in start until end || (start > end && (hour >= start || hour < end))) return p
        }
        return null
    }

    // ---- 后端 ----
    val supabaseUrl: String = root.getJSONObject("backend").getString("supabase_url")
    val supabaseAnonKey: String = root.getJSONObject("backend").getString("supabase_anon_key")
    val realtimeFallbackPollMs: Long = root.getJSONObject("backend").getLong("realtime_fallback_poll_ms")
    val usagePollMs: Long = root.getJSONObject("backend").getLong("usage_poll_ms")

    companion object {
        @Volatile private var instance: PetConfig? = null
        fun get(context: Context): PetConfig = instance ?: synchronized(this) {
            instance ?: run {
                val raw = context.assets.open("xiaonian-config.json").bufferedReader().use { it.readText() }
                PetConfig(JSONObject(raw)).also { instance = it }
            }
        }
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { getString(it) }
}