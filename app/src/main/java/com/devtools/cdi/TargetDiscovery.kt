package com.devtools.cdi

import com.devtools.cdp.data.TargetInfo
import com.devtools.cdp.data.VersionInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * TargetDiscovery：用 OkHttp 拉 CDP HTTP 发现接口。
 *
 * 桥接后所有 CDP 流量走 127.0.0.1:9222（无局域网、无电脑）。
 *
 * 研究：
 *   - GET /json/version 返回 VersionInfo（含 browser 级 webSocketDebuggerUrl）
 *   - GET /json/list 返回 List<TargetInfo>（含 page 级 webSocketDebuggerUrl，
 *     形如 ws://localhost:9222/devtools/page/<id>）
 *   - 页面调试 WS 用 /json/list 的 webSocketDebuggerUrl
 *   来源: https://chromedevtools.github.io/devtools-protocol/
 *   来源: https://learn.microsoft.com/en-za/microsoft-edge/devtools-protocol/#jsonversion
 */
class TargetDiscovery(
    private val port: Int = 9222,
    client: OkHttpClient? = null
) {
    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val base = "http://127.0.0.1:$port"

    /** /json/version */
    fun fetchVersion(): VersionInfo? = runCatching {
        val req = Request.Builder().url("$base/json/version").get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            gson.fromJson(body, VersionInfo::class.java)
        }
    }.getOrNull()

    /** /json/list */
    fun fetchTargets(): List<TargetInfo> = runCatching {
        val req = Request.Builder().url("$base/json/list").get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val type = object : TypeToken<List<TargetInfo>>() {}.type
            gson.fromJson<List<TargetInfo>>(body, type) ?: emptyList()
        }
    }.getOrDefault(emptyList())

    /**
     * 把 webSocketDebuggerUrl 的 scheme://host:port 强制换成 ws://127.0.0.1:<port>。
     * Chrome 返回的 URL 通常是 ws://localhost:9222/...，桥接后必须改 host 才能命中本机桥。
     */
    fun rewriteWsUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        // 形如 ws://localhost:9222/devtools/page/ABC
        val idx = raw.indexOf("/devtools/")
        return if (idx >= 0) "ws://127.0.0.1:$port${raw.substring(idx)}"
        else {
            // 兜底：正则替换 scheme://host:port
            val m = Regex("^[a-zA-Z]+://[^/]+").find(raw)
            if (m != null) "ws://127.0.0.1:$port${raw.substring(m.range.last + 1)}" else raw
        }
    }
}
