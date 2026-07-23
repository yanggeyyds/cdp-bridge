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
 *   来源: https://www.ibm.com/docs/fr/devops-test-ui/10.5.2?topic=tiutp-unable-record-run-web-ui-tests-chrome-111-edge-112
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

    /** 连接 webSocketDebuggerUrl。返回是否成功打开。 */
    fun connect(wsUrl: String): Boolean {
        close()
        val req = Request.Builder().url(wsUrl).build()
        val latch = java.util.concurrent.CountDownLatch(1)
        var opened = false
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isOpen = true
                opened = true
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
                isOpen = false
                failAll(t)
            }
        })
        latch.await(8, TimeUnit.SECONDS)
        return opened
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
