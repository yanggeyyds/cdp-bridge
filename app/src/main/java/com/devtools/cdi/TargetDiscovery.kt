package com.devtools.cdi

import com.devtools.cdp.data.TargetInfo
import com.devtools.cdp.data.VersionInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ConnectException
import java.net.SocketTimeoutException
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

    /**
     * 拉取结果：携带具体失败原因，便于 UI 给出可操作诊断。
     *
     * - [Ok]：成功，[value] 为解析后的对象；
     * - [HttpError]：HTTP 通了但状态码非 2xx（说明桥接到的是 HTTP 服务但不是 CDP）；
     * - [Empty]：HTTP 200 但 body 为空（对端立即关闭连接，常见于 Chrome 拒绝非 CDP 客户端）；
     * - [Refused]：连接被拒（TCP 监听未起 / 桥接已停 / 端口错）；
     * - [Timeout]：连接或读超时（abstract socket 连上但不回数据，常见于 socket 是死条目）；
     * - [Reset]：连接被对端 reset（Chrome 检测到非法请求主动断开）；
     * - [Other]：其他网络异常，[message] 为原始信息。
     */
    sealed class FetchResult<out T> {
        data class Ok<T>(val value: T) : FetchResult<T>()
        data class HttpError(val code: Int, val body: String?) : FetchResult<Nothing>()
        data object Empty : FetchResult<Nothing>()
        data object Refused : FetchResult<Nothing>()
        data object Timeout : FetchResult<Nothing>()
        data object Reset : FetchResult<Nothing>()
        /** Android NetworkSecurityConfig 禁止明文 HTTP/WS 到 localhost。 */
        data object CleartextBlocked : FetchResult<Nothing>()
        data class Other(val message: String) : FetchResult<Nothing>()
    }

    /**
     * 判断异常是否由 Android cleartext 策略拦截。
     *
     * OkHttp 在 cleartext 被禁时会抛 IOException，消息特征：
     *   "CLEARTEXT communication to 127.0.0.1 not permitted by network security policy"
     * 异常类在不同 Android 版本可能是 CleartextConfigException 或被 wrap，
     * 故用消息关键字匹配最稳妥。
     * 来源: https://developer.android.com/training/articles/security-config#CleartextTraffic
     */
    private fun isCleartextBlocked(t: Throwable): Boolean {
        var cur: Throwable? = t
        repeat(5) {
            if (cur == null) return false
            val msg = cur!!.message ?: cur!!.javaClass.simpleName
            if (msg.contains("CLEARTEXT", ignoreCase = true) ||
                msg.contains("not permitted by network security policy", ignoreCase = true)
            ) return true
            cur = cur!!.cause
        }
        return false
    }

    /** /json/version（详细结果）。 */
    fun fetchVersionResult(): FetchResult<VersionInfo> {
        val req = Request.Builder().url("$base/json/version").get().build()
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    FetchResult.HttpError(resp.code, resp.body?.string())
                } else {
                    val body = resp.body?.string()
                    if (body.isNullOrBlank()) FetchResult.Empty
                    else {
                        val v = runCatching { gson.fromJson(body, VersionInfo::class.java) }.getOrNull()
                        if (v != null) FetchResult.Ok(v) else FetchResult.Other("parse failed: ${body.take(120)}")
                    }
                }
            }
        } catch (e: ConnectException) {
            FetchResult.Refused
        } catch (e: SocketTimeoutException) {
            FetchResult.Timeout
        } catch (e: java.net.SocketException) {
            if (isCleartextBlocked(e)) FetchResult.CleartextBlocked
            else if ((e.message ?: "").contains("reset", ignoreCase = true)) FetchResult.Reset
            else FetchResult.Other(e.message ?: e.javaClass.simpleName)
        } catch (e: javax.net.ssl.SSLException) {
            if (isCleartextBlocked(e)) FetchResult.CleartextBlocked
            else if ((e.message ?: "").contains("reset", ignoreCase = true)) FetchResult.Reset
            else FetchResult.Other(e.message ?: e.javaClass.simpleName)
        } catch (e: java.io.IOException) {
            // Android NetworkSecurityConfig 拦 cleartext 时抛 IOException，
            // 消息含 "CLEARTEXT communication ... not permitted by network security policy"
            if (isCleartextBlocked(e)) FetchResult.CleartextBlocked
            else FetchResult.Other(e.message ?: e.javaClass.simpleName)
        } catch (e: Throwable) {
            if (isCleartextBlocked(e)) FetchResult.CleartextBlocked
            else FetchResult.Other(e.message ?: e.javaClass.simpleName)
        }
    }

    /** /json/version（兼容旧调用，失败返回 null）。 */
    fun fetchVersion(): VersionInfo? = (fetchVersionResult() as? FetchResult.Ok)?.value

    /** /json/list（详细结果）。 */
    fun fetchTargetsResult(): FetchResult<List<TargetInfo>> {
        val req = Request.Builder().url("$base/json/list").get().build()
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    FetchResult.HttpError(resp.code, resp.body?.string())
                } else {
                    val body = resp.body?.string()
                    if (body.isNullOrBlank()) FetchResult.Empty
                    else {
                        val type = object : TypeToken<List<TargetInfo>>() {}.type
                        val list = runCatching { gson.fromJson<List<TargetInfo>>(body, type) }.getOrNull()
                        if (list != null) FetchResult.Ok(list) else FetchResult.Other("parse failed: ${body.take(120)}")
                    }
                }
            }
        } catch (e: ConnectException) {
            FetchResult.Refused
        } catch (e: SocketTimeoutException) {
            FetchResult.Timeout
        } catch (e: java.net.SocketException) {
            if (isCleartextBlocked(e)) FetchResult.CleartextBlocked
            else if ((e.message ?: "").contains("reset", ignoreCase = true)) FetchResult.Reset
            else FetchResult.Other(e.message ?: e.javaClass.simpleName)
        } catch (e: javax.net.ssl.SSLException) {
            if (isCleartextBlocked(e)) FetchResult.CleartextBlocked
            else if ((e.message ?: "").contains("reset", ignoreCase = true)) FetchResult.Reset
            else FetchResult.Other(e.message ?: e.javaClass.simpleName)
        } catch (e: java.io.IOException) {
            if (isCleartextBlocked(e)) FetchResult.CleartextBlocked
            else FetchResult.Other(e.message ?: e.javaClass.simpleName)
        } catch (e: Throwable) {
            if (isCleartextBlocked(e)) FetchResult.CleartextBlocked
            else FetchResult.Other(e.message ?: e.javaClass.simpleName)
        }
    }

    /** /json/list（兼容旧调用，失败返回空表）。 */
    fun fetchTargets(): List<TargetInfo> =
        (fetchTargetsResult() as? FetchResult.Ok)?.value ?: emptyList()

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
