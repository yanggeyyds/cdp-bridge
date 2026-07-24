package com.devtools.cdi

import com.devtools.cdp.data.CdpEvent
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * CdpClient：OkHttp WebSocket 跑 CDP。
 *
 * 硬约束：WebSocket 不得带 Origin（Chrome ≥111 会因未授权 Origin 返回 403）。
 *
 * 研究（关键修正）：
 *   - OkHttp 的 **networkInterceptor 对 WebSocket 握手不执行**（RealCall.kt 中
 *     `if (!forWebSocket) { interceptors += client.networkInterceptors }` 整段跳过），
 *     故用 networkInterceptor removeHeader("Origin") 无效。
 *   - OkHttp 的 RealWebSocket.connect() 默认就**不**添加 Origin 头，
 *     BridgeInterceptor 也不补 Origin，所以只要不主动 .header("Origin",...) 就不会带。
 *   - 若调用方误设了 Origin，正确剥离方式是**应用拦截器** addInterceptor（WebSocket 也会执行）。
 *   来源: https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/connection/RealCall.kt
 *   来源: https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/ws/RealWebSocket.kt
 *   来源: https://square.github.io/okhttp/interceptors/
 *   来源: https://www.ibm.com/docs/fr/devops-test-ui/1.5.2?topic=tiutp-unable-record-run-web-ui-tests-chrome-111-edge-112
 *
 * 协程模式：
 *   - send(method, params):Long 自增 id，把 CompletableDeferred 存进 Map<id, deferred>，
 *     onMessage 收到 {id,result} 时 remove(id).complete(result)，调用方 await 拿结果；
 *   - {method,params}（无 id）即事件，tryEmit 到 MutableSharedFlow<CdpEvent>。
 *   来源: https://kotlinlang.org/api/kotlinx-coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-completable-deferred/
 *   来源: https://kotlinlang.org/api/kotlinx-coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-mutable-shared-flow/
 */
class CdpClient(
    /** 可复用外部 OkHttpClient；若为 null 则自建（含去 Origin 的应用拦截器）。 */
    sharedClient: OkHttpClient? = null
) {
    private val client: OkHttpClient = sharedClient ?: OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)   // WS 长连接，不超时
        .pingInterval(20, TimeUnit.SECONDS)
        // 应用拦截器：WebSocket 握手也会执行，剥离调用方误设的 Origin。
        // （OkHttp 默认不发 Origin，这里仅作双保险，满足"不得带 Origin"硬约束。）
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .removeHeader("Origin")
                .build()
            chain.proceed(req)
        }
        .build()

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val _events = MutableSharedFlow<CdpEvent>(extraBufferCapacity = 256)
    val events = _events.asSharedFlow()

    @Volatile private var ws: WebSocket? = null
    @Volatile var isOpen: Boolean = false
        private set

    /**
     * 连接结果：携带真实失败原因，便于 UI 诊断。
     *
     * - [Ok]：WS 握手成功；
     * - [HttpRejected]：握手返回非 101（Chrome 返回 401/403/404 等），含 code + body；
     * - [CleartextBlocked]：Android 网络安全策略禁明文 ws://；
     * - [Timeout]：8s 内未完成握手；
     * - [Error]：其他异常（连接 reset / IOException 等），含 throwable。
     */
    sealed class ConnectResult {
        data object Ok : ConnectResult()
        data class HttpRejected(val code: Int, val body: String?) : ConnectResult()
        data object CleartextBlocked : ConnectResult()
        data object Timeout : ConnectResult()
        data class Error(val throwable: Throwable) : ConnectResult()
    }

    /** 连接 webSocketDebuggerUrl。返回 [ConnectResult] 携带真实失败原因。 */
    fun connect(wsUrl: String): ConnectResult {
        close()
        // 关键：显式设 Host: localhost:9222。
        // rewriteWsUrl 为保证 OkHttp 连到本机桥接，把 host 改成了 127.0.0.1，
        // OkHttp 默认 Host 头取自 URL 会是 "127.0.0.1:9222"。
        // 但 Chrome DevTools 后端期望 Host 与它返回的 wsUrl（localhost:9222）一致，
        // 部分版本对 Host=127.0.0.1 会返回 403 Forbidden。
        // 显式覆盖成 localhost:9222 匹配 Chrome 期望，同时 URL 仍是 127.0.0.1 走本机桥接。
        val req = Request.Builder()
            .url(wsUrl)
            .header("Host", "localhost:9222")
            .build()
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: ConnectResult = ConnectResult.Timeout
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isOpen = true
                result = ConnectResult.Ok
                latch.countDown()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleText(text)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isOpen = false
                failAll(RuntimeException("WS closed: $code $reason"))
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // 关键修复：原实现只 failAll(t)，但 latch 没 countDown，
                // 调用方只能等 8s 超时拿到 opened=false，看不到真实原因。
                // 现在解析 response（如果有）和 throwable，分类成可读的 ConnectResult。
                isOpen = false
                failAll(t)
                result = classifyFailure(t, response)
                latch.countDown()
            }
        })
        // 8s 内等 onOpen 或 onFailure 触发；超时则 result 保持 Timeout
        latch.await(8, TimeUnit.SECONDS)
        return result
    }

    /** 把 onFailure 的 throwable + response 分类成 [ConnectResult]。 */
    private fun classifyFailure(t: Throwable, response: Response?): ConnectResult {
        // 1) cleartext 被拦：消息含 CLEARTEXT / network security policy
        var cur: Throwable? = t
        repeat(5) {
            if (cur == null) return@repeat
            val msg = cur!!.message ?: cur!!.javaClass.simpleName
            if (msg.contains("CLEARTEXT", ignoreCase = true) ||
                msg.contains("not permitted by network security policy", ignoreCase = true)
            ) return CleartextBlocked
            cur = cur!!.cause
        }
        // 2) HTTP 非 101：OkHttp 在握手响应非 101 时会 onFailure，
        //    response 携带真实状态码和 body（Chrome 401/403/404 会走这里）
        if (response != null) {
            val code = response.code
            val body = runCatching { response.body?.string() }.getOrNull()
            return ConnectResult.HttpRejected(code, body)
        }
        // 3) 其他异常（连接 reset / timeout / IOException）
        return ConnectResult.Error(t)
    }

    private fun handleText(text: String) {
        val obj: JsonObject = try {
            JsonParser.parseString(text).asJsonObject
        } catch (_: Throwable) { return }

        if (obj.has("id")) {
            // 响应：{id, result|error}
            val id = obj.get("id").asLong
            val deferred = pending.remove(id) ?: return
            if (obj.has("error")) {
                deferred.completeExceptionally(
                    RuntimeException("CDP error: ${obj.get("error")}")
                )
            } else {
                val result = obj.getAsJsonObject("result") ?: JsonObject()
                deferred.complete(result)
            }
        } else if (obj.has("method")) {
            // 事件：{method, params, sessionId?}
            val method = obj.get("method").asString
            val params = obj.getAsJsonObject("params") ?: JsonObject()
            val sessionId = if (obj.has("sessionId")) obj.get("sessionId").asString else null
            _events.tryEmit(CdpEvent(method, params, sessionId))
        }
    }

    private fun failAll(t: Throwable) {
        for (d in pending.values) d.completeExceptionally(t)
        pending.clear()
    }

    /**
     * 发 CDP 命令并 await 结果。挂起协程内调用。
     */
    suspend fun send(method: String, params: JsonObject = JsonObject()): JsonObject {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val payload = JsonObject().apply {
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
        }
        val socket = ws
        if (socket == null || !isOpen) {
            pending.remove(id)
            throw RuntimeException("WebSocket not open")
        }
        if (!socket.send(payload.toString())) {
            pending.remove(id)
            throw RuntimeException("WebSocket send failed")
        }
        return deferred.await()
    }

    /** 发命令但不等结果（用于 enable 类命令的快速触发）。 */
    fun sendFireAndForget(method: String, params: JsonObject = JsonObject()) {
        val id = nextId.getAndIncrement()
        val payload = JsonObject().apply {
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
        }
        ws?.send(payload.toString())
    }

    fun close() {
        isOpen = false
        try { ws?.close(1000, "bye") } catch (_: Throwable) {}
        ws = null
        failAll(RuntimeException("closed"))
    }
}
