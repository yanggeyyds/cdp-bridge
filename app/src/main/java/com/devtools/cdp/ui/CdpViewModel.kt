package com.devtools.cdp.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devtools.cdp.ICdpBridge
import com.devtools.cdp.data.ConsoleEntry
import com.devtools.cdp.data.CdpEvent
import com.devtools.cdp.data.DomNode
import com.devtools.cdp.data.ExceptionEntry
import com.devtools.cdp.data.NetworkRequest
import com.devtools.cdp.data.TargetInfo
import com.devtools.cdp.data.VersionInfo
import com.devtools.cdi.CdpClient
import com.devtools.cdi.TargetDiscovery
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shizuku/桥接 状态机。 */
enum class BridgeState {
    IDLE,                // 未初始化
    SHIZUKU_NOT_RUNNING, // Shizuku 未运行
    SHIZUKU_TOO_OLD,     // pre-v11 旧服务
    PERMISSION_DENIED,   // 用户拒绝授权
    WAITING_PERMISSION,  // 等待授权弹窗
    BIND_FAILED,         // 绑定 UserService 失败
    BINDER_DEAD,         // binder 死亡
    BOUND,               // UserService 已绑定，可启动桥接
    BRIDGE_RUNNING       // 桥接已启动，9222 可访问
}

data class UiState(
    val bridgeState: BridgeState = BridgeState.IDLE,
    val statusMessage: String = "",
    val version: VersionInfo? = null,
    val abstractTargets: List<String> = emptyList(),   // /proc/net/unix 枚举的 socket 名
    val httpTargets: List<TargetInfo> = emptyList(),   // /json/list 的 page target
    val selectedAbstract: String? = null,
    val console: List<ConsoleEntry> = emptyList(),
    val exceptions: List<ExceptionEntry> = emptyList(),
    val domRoot: DomNode? = null,
    val network: List<NetworkRequest> = emptyList(),
    val cdpConnected: Boolean = false,
    /** abstract socket 探测结果：null=未探测；true=可连；false=连不上。 */
    val probeOk: Boolean? = null,
    /** /json/version 失败时的具体原因（人类可读），用于诊断卡展示。null=未失败或未尝试。 */
    val discoveryError: String? = null
) {
    companion object {
        const val MAX_CONSOLE = 500
        const val MAX_NETWORK = 300
    }
}

/**
 * CdpViewModel：MVVM 中枢。
 *
 * - 持有 Shizuku UserService 句柄 [ICdpBridge]；
 * - 通过 [TargetDiscovery] 拉 /json/version 与 /json/list；
 * - 通过 [CdpClient] 跑 CDP WebSocket，collect 事件更新 Console/Elements/Network。
 */
class CdpViewModel : ViewModel() {

    companion object { private const val TAG = "CdpViewModel" }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    @Volatile private var bridge: ICdpBridge? = null
    @Volatile private var bridgePort: Int = 9222
    private var discovery: TargetDiscovery? = null
    private var cdp: CdpClient? = null
    private var cdpJob: Job? = null
    /** 桥接启动后的自动重试协程：定期拉 /json/version，成功或停止桥接时取消。 */
    private var autoRetryJob: Job? = null

    // ---------- Shizuku 回调（由 MainActivity 调入） ----------

    fun onShizukuNotRunning() = update {
        it.copy(bridgeState = BridgeState.SHIZUKU_NOT_RUNNING,
            statusMessage = "Shizuku 未运行，请先在 Shizuku App 内启动服务")
    }

    fun onShizukuTooOld() = update {
        it.copy(bridgeState = BridgeState.SHIZUKU_TOO_OLD,
            statusMessage = "Shizuku 版本过旧（pre-v11），请升级")
    }

    fun onPermissionDenied() = update {
        it.copy(bridgeState = BridgeState.PERMISSION_DENIED,
            statusMessage = "已拒绝 Shizuku 授权，请在 Shizuku App 重新授权")
    }

    fun onBindFailed(msg: String) = update {
        it.copy(bridgeState = BridgeState.BIND_FAILED, statusMessage = msg)
    }

    fun onBinderDead() = update {
        it.copy(bridgeState = BridgeState.BINDER_DEAD,
            statusMessage = "Shizuku binder 死亡", cdpConnected = false)
    }

    fun onBridgeConnected(b: ICdpBridge, port: Int) {
        bridge = b
        bridgePort = port
        discovery = TargetDiscovery(port)
        update { it.copy(bridgeState = BridgeState.BOUND, statusMessage = "UserService 已绑定，可启动桥接") }
        refreshAbstractTargets()
    }

    fun onBridgeDisconnected() {
        stopCdpInternal()
        update { it.copy(bridgeState = BridgeState.IDLE, statusMessage = "UserService 已断开",
            cdpConnected = false, probeOk = null, discoveryError = null,
            version = null, httpTargets = emptyList(), selectedAbstract = null) }
    }

    // ---------- 桥接 / 枚举 ----------

    /** 刷新 /proc/net/unix 枚举的 abstract socket 名。 */
    fun refreshAbstractTargets() {
        val b = bridge ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val names = runCatching { b.listTargets() }.getOrDefault(emptyList())
            update { it.copy(abstractTargets = names) }
        }
    }

    /** 启动桥接到指定 abstract socket 名。 */
    fun startBridge(abstractName: String) {
        val b = bridge ?: run {
            update { it.copy(statusMessage = "UserService 未连接") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            // 先停旧的
            cancelAutoRetry()
            runCatching { b.stopBridge() }
            val ok = runCatching { b.startBridge(bridgePort, abstractName) }.getOrDefault(false)
            if (ok) {
                update {
                    it.copy(bridgeState = BridgeState.BRIDGE_RUNNING,
                        selectedAbstract = abstractName,
                        statusMessage = "桥接已启动：127.0.0.1:$bridgePort <-> @$abstractName",
                        version = null, probeOk = null, discoveryError = null)
                }
                // 关键：startBridge 只绑了 TCP 监听，立即探测 abstract socket 是否真的可连。
                // 这样能区分"TCP 监听起来了但 Chrome 没在监听"vs"Chrome 在监听但 CDP 无响应"。
                val probe = runCatching { b.probeAbstract(abstractName) }.getOrDefault(-2)
                update { it.copy(probeOk = probe == 0) }
                // 立刻拉一次 version；失败则启动自动重试（用户随后打开 Chrome 时能自动发现）
                refreshVersion()
                refreshHttpTargets()
                scheduleAutoRetry()
            } else {
                update { it.copy(statusMessage = "启动桥接失败，请确认 Chrome 已运行且可调试") }
            }
        }
    }

    /**
     * 桥接启动后自动重试：每 2s 拉一次 /json/version，最多 10 次（约 20s）。
     * 成功拿到 version 即停；用户手动停止桥接或重连也会取消。
     * 解决"用户先启动桥接、后打开 Chrome"的时序问题。
     */
    private fun scheduleAutoRetry() {
        cancelAutoRetry()
        autoRetryJob = viewModelScope.launch(Dispatchers.IO) {
            repeat(10) { i ->
                delay(2000)
                if (!isActive) return@repeat
                // 桥接已停或已拿到 version，停止重试
                val st = _ui.value
                if (st.bridgeState != BridgeState.BRIDGE_RUNNING) return@launch
                if (st.version != null) return@launch
                val d = discovery ?: return@launch
                val res = d.fetchVersionResult()
                if (res is TargetDiscovery.FetchResult.Ok) {
                    update { it.copy(version = res.value,
                        discoveryError = null,
                        statusMessage = "桥接 OK：${res.value.browser}（第 ${i + 1} 次重试命中）") }
                    // 顺带刷新 targets
                    refreshHttpTargets()
                    return@launch
                } else {
                    update { it.copy(discoveryError = describeFetchError(res)) }
                }
            }
            // 10 次都未命中，保留最后一次错误供诊断卡展示
            Log.w(TAG, "auto-retry exhausted, /json/version never responded")
        }
    }

    private fun cancelAutoRetry() {
        autoRetryJob?.cancel()
        autoRetryJob = null
    }

    /** 把 [TargetDiscovery.FetchResult] 的失败分支转成人类可读诊断。 */
    private fun describeFetchError(res: TargetDiscovery.FetchResult<*>): String = when (res) {
        is TargetDiscovery.FetchResult.Ok -> ""
        is TargetDiscovery.FetchResult.HttpError ->
            "HTTP ${res.code}：桥接到的服务不是 CDP（或被 Chrome 拒绝）"
        TargetDiscovery.FetchResult.Empty ->
            "/json/version 返回空：对端立即关闭连接，Chrome 可能未真正在监听"
        TargetDiscovery.FetchResult.Refused ->
            "连接被拒：127.0.0.1:$bridgePort 未监听（桥接已停或未启动）"
        TargetDiscovery.FetchResult.Timeout ->
            "超时：abstract socket 连上但 Chrome 不回数据（socket 可能是残留死条目）"
        TargetDiscovery.FetchResult.Reset ->
            "连接被 reset：Chrome 检测到非 CDP 请求主动断开"
        TargetDiscovery.FetchResult.CleartextBlocked ->
            "明文 HTTP 被 Android 网络安全策略拦截：CLEARTEXT to 127.0.0.1 not permitted。" +
            "这是 App 配置问题，需在 manifest 的 networkSecurityConfig 里允许 localhost cleartext（v1.4 已修复）"
        is TargetDiscovery.FetchResult.Other -> "网络异常：${res.message}"
    }

    fun stopBridge() {
        val b = bridge ?: return
        viewModelScope.launch(Dispatchers.IO) {
            cancelAutoRetry()
            stopCdpInternal()
            runCatching { b.stopBridge() }
            update {
                it.copy(bridgeState = BridgeState.BOUND, cdpConnected = false,
                    version = null, httpTargets = emptyList(), domRoot = null,
                    console = emptyList(), exceptions = emptyList(), network = emptyList(),
                    probeOk = null, discoveryError = null,
                    statusMessage = "桥接已停止")
            }
        }
    }

    /** GET /json/version（验证桥接是否通）。 */
    fun refreshVersion() {
        val d = discovery ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val res = d.fetchVersionResult()
            if (res is TargetDiscovery.FetchResult.Ok) {
                update { it.copy(version = res.value, discoveryError = null,
                    statusMessage = "桥接 OK：${res.value.browser}") }
            } else {
                update { it.copy(version = null,
                    discoveryError = describeFetchError(res),
                    statusMessage = "/json/version 无响应") }
            }
        }
    }

    /** GET /json/list。 */
    fun refreshHttpTargets() {
        val d = discovery ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val list = d.fetchTargets()
            update { it.copy(httpTargets = list) }
        }
    }

    // ---------- CDP 客户端 ----------

    /** 连接某 page target 的 webSocketDebuggerUrl，并 enable 各域。 */
    fun connectTarget(target: TargetInfo) {
        val d = discovery ?: return
        val wsUrl = d.rewriteWsUrl(target.webSocketDebuggerUrl) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            stopCdpInternal()
            val c = CdpClient()
            cdp = c
            update { it.copy(statusMessage = "正在连接 CDP WebSocket…", discoveryError = null) }
            val result = withContext(Dispatchers.IO) { c.connect(wsUrl) }
            if (result !is CdpClient.ConnectResult.Ok) {
                // 关键修复：原代码只显示 URL，看不到真实原因；
                // 现在把 ConnectResult 翻译成可读诊断，便于定位
                val diag = describeConnectError(result, wsUrl)
                update { it.copy(cdpConnected = false,
                    statusMessage = "CDP WebSocket 连接失败",
                    discoveryError = diag) }
                Log.e(TAG, "connectTarget failed: $diag")
                return@launch
            }
            update { it.copy(cdpConnected = true, statusMessage = "已连接 CDP：${target.displayTitle()}",
                discoveryError = null) }
            // enable 域
            runCatching { c.send("Runtime.enable") }
            runCatching { c.send("Network.enable") }
            runCatching { c.send("DOM.enable") }
            // 独立协程订阅事件，断开时 cancel 即可退出 collect
            cdpJob = viewModelScope.launch(Dispatchers.IO) {
                c.events.collect { ev -> handleEvent(ev) }
            }
        }
    }

    /** 把 [CdpClient.ConnectResult] 翻译成人类可读诊断。 */
    private fun describeConnectError(r: CdpClient.ConnectResult, wsUrl: String): String = when (r) {
        CdpClient.ConnectResult.Ok -> ""
        is CdpClient.ConnectResult.HttpRejected -> {
            val hint = when (r.code) {
                401 -> "401 Unauthorized：target id 可能已失效（页面刷新/关闭/切换），重新点 Targets 刷新"
                403 -> "403 Forbidden：Origin/Host 校验失败，或 Chrome 拒绝非授权客户端"
                404 -> "404 Not Found：target 不存在（页面已销毁）"
                else -> "HTTP ${r.code}"
            }
            "$hint\nURL: $wsUrl" + (r.body?.let { "\n响应: ${it.take(200)}" } ?: "")
        }
        CdpClient.ConnectResult.CleartextBlocked ->
            "明文 ws:// 被 Android 网络安全策略拦截（CLEARTEXT not permitted）。" +
            "需 networkSecurityConfig 允许 localhost cleartext"
        CdpClient.ConnectResult.Timeout ->
            "WebSocket 握手 8s 超时：Chrome 可能未响应 WS 升级请求" +
            "（页面已后台/锁屏/被杀），保持页面前台后重试"
        is CdpClient.ConnectResult.Error ->
            "WebSocket 连接异常：${r.throwable.javaClass.simpleName}: ${r.throwable.message}"
    }

    fun disconnectTarget() {
        viewModelScope.launch(Dispatchers.IO) {
            stopCdpInternal()
            update { it.copy(cdpConnected = false, domRoot = null,
                console = emptyList(), exceptions = emptyList(), network = emptyList()) }
        }
    }

    private fun stopCdpInternal() {
        cdpJob?.cancel()
        cdpJob = null
        cdp?.close()
        cdp = null
        cancelAutoRetry()
    }

    /** Console：Runtime.evaluate 输入框提交。 */
    fun evaluateExpression(expression: String) {
        val c = cdp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val params = JsonObject().apply {
                addProperty("expression", expression)
                addProperty("returnByValue", true)
            }
            val result = runCatching { c.send("Runtime.evaluate", params) }.getOrNull()
            val text = if (result != null) {
                val res = result.getAsJsonObject("result")
                val v = res?.get("value")
                val desc = res?.get("description")
                v?.toString() ?: desc?.asString ?: result.toString()
            } else "(eval error)"
            pushConsole(ConsoleEntry(level = "info", text = "→ $expression\n$text"))
        }
    }

    /** Elements：拉 DOM 树（递归到深度 -1）。 */
    fun refreshDom() {
        val c = cdp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val res = runCatching { c.send("DOM.getDocument",
                JsonObject().apply { addProperty("depth", -1); addProperty("pierce", false) })
            }.getOrNull()
            val root = res?.getAsJsonObject("root") ?: return@launch
            val node = com.google.gson.Gson().fromJson(root, DomNode::class.java)
            update { it.copy(domRoot = node) }
        }
    }

    /** Elements：点击节点高亮（DOM.highlightNode）。 */
    fun highlightNode(nodeId: Int) {
        val c = cdp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val hc = JsonObject().apply {
                addProperty("showInfo", true)
                addProperty("contentColor", "rgba(80,160,255,0.6)")
                addProperty("paddingColor", "rgba(160,255,80,0.4)")
                addProperty("borderColor", "rgba(255,80,80,0.6)")
            }
            val params = JsonObject().apply {
                add("highlightConfig", hc)
                addProperty("nodeId", nodeId)
            }
            runCatching { c.send("DOM.highlightNode", params) }
        }
    }

    // ---------- 事件分发 ----------

    private fun handleEvent(ev: CdpEvent) {
        when (ev.method) {
            "Runtime.consoleAPICalled" -> {
                val level = ev.params.get("type")?.asString ?: "log"
                val args = ev.params.getAsJsonArray("args")
                val text = args?.joinToString(" ") { it.asJsonObject?.get("value")?.asString ?: it.toString() } ?: ""
                val st = ev.params.getAsJsonObject("stackTrace")
                val url = st?.getAsJsonArray("callFrames")?.firstOrNull()?.asJsonObject?.get("url")?.asString
                pushConsole(ConsoleEntry(level = level, text = text, url = url))
            }
            "Runtime.exceptionThrown" -> {
                val details = ev.params.getAsJsonObject("exceptionDetails")
                val text = details?.get("text")?.asString ?: "exception"
                val st = details?.getAsJsonObject("stackTrace")
                val trace = st?.getAsJsonArray("callFrames")?.joinToString("\n") {
                    val f = it.asJsonObject
                    "  at ${f.get("functionName")?.asString ?: ""} (${f.get("url")?.asString ?: ""}:${f.get("lineNumber")?.asString ?: ""})"
                }
                pushException(ExceptionEntry(text = text, stackTrace = trace))
            }
            "Network.requestWillBeSent" -> {
                val rid = ev.params.get("requestId")?.asString ?: return
                val req = ev.params.getAsJsonObject("request")
                upsertNetwork(rid) {
                    it.url = req?.get("url")?.asString ?: ""
                    it.method = req?.get("method")?.asString ?: ""
                    it.type = ev.params.get("type")?.asString ?: ""
                }
            }
            "Network.responseReceived" -> {
                val rid = ev.params.get("requestId")?.asString ?: return
                val resp = ev.params.getAsJsonObject("response")
                upsertNetwork(rid) {
                    it.status = resp?.get("status")?.asInt ?: 0
                    it.mimeType = resp?.get("mimeType")?.asString ?: ""
                }
            }
            "Network.loadingFinished" -> {
                val rid = ev.params.get("requestId")?.asString ?: return
                upsertNetwork(rid) { it.finished = true }
            }
            "Network.loadingFailed" -> {
                val rid = ev.params.get("requestId")?.asString ?: return
                upsertNetwork(rid) {
                    it.failed = true
                    it.errorText = ev.params.get("errorText")?.asString
                }
            }
        }
    }

    private fun pushConsole(e: ConsoleEntry) {
        update { st ->
            val list = (st.console + e).takeLast(UiState.MAX_CONSOLE)
            st.copy(console = list)
        }
    }

    /** 清空 Console 与异常列表。 */
    fun clearConsole() {
        update { it.copy(console = emptyList(), exceptions = emptyList()) }
    }

    private fun pushException(e: ExceptionEntry) {
        update { st ->
            val list = (st.exceptions + e).takeLast(UiState.MAX_CONSOLE)
            st.copy(exceptions = list)
        }
    }

    private fun upsertNetwork(requestId: String, mutate: (NetworkRequest) -> Unit) {
        update { st ->
            val list = st.network.toMutableList()
            val idx = list.indexOfFirst { it.requestId == requestId }
            if (idx >= 0) {
                val cur = list[idx]
                mutate(cur)
                list[idx] = cur
            } else {
                val nr = NetworkRequest(requestId = requestId)
                mutate(nr)
                list.add(nr)
            }
            st.copy(network = list.takeLast(UiState.MAX_NETWORK))
        }
    }

    /** 清空 Network 请求列表。 */
    fun clearNetwork() {
        update { it.copy(network = emptyList()) }
    }

    private fun update(block: (UiState) -> UiState) {
        // 用 MutableStateFlow.update 原子 CAS，避免并发事件（Network 流量很大时）
        // 多线程同时读 _ui.value 再写回导致更新丢失。
        _ui.update(block)
    }

    override fun onCleared() {
        super.onCleared()
        stopCdpInternal()
        runCatching { bridge?.destroy() }
    }
}
