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
    BIND_FAILED,         // 绑定服务失败（Shizuku UserService 或 RootService）
    BINDER_DEAD,         // binder 死亡
    BOUND,               // 服务已绑定，可启动桥接
    BRIDGE_RUNNING,      // 桥接已启动，9222 可访问
    ROOT_NO_SHELL        // Root 模式下未获取 root 权限
}

/** 桥接来源模式：Shizuku（非 Root，需 Shizuku App）或 Root（需 root 设备）。 */
enum class BridgeMode { SHIZUKU, ROOT }

/** Remote socket + 所属应用信息。 */
data class AbstractTarget(
    val socketName: String,
    val processName: String,
    val packageName: String,
    val appLabel: String
) {
    /** 显示用：优先应用名，其次进程名，最后 socket 名。 */
    fun displayName(): String = appLabel.ifBlank { processName.ifBlank { socketName } }
}

data class UiState(
    val bridgeState: BridgeState = BridgeState.IDLE,
    val mode: BridgeMode = BridgeMode.ROOT,
    val statusMessage: String = "",
    val version: VersionInfo? = null,
    val abstractTargets: List<String> = emptyList(),   // /proc/net/unix 枚举的 socket 名
    val abstractTargetsDetailed: List<AbstractTarget> = emptyList(), // 带应用信息的 target
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
    val discoveryError: String? = null,
    // —— Sources ——
    val sourcesEnabled: Boolean = false,
    val scripts: List<ScriptInfo> = emptyList(),
    val scriptSources: Map<String, String> = emptyMap(),
    // —— Application（Storage）——
    val cookies: List<CookieInfo> = emptyList(),
    val localStorage: List<StorageItem> = emptyList(),
    val sessionStorage: List<StorageItem> = emptyList(),
    // —— Performance ——
    val perfRecording: Boolean = false,
    val perfProfile: String? = null,
    // —— Memory ——
    val memoryMetrics: Map<String, Double> = emptyMap(),
    val heapSnapshot: String? = null,
    // —— Security ——
    val securityInfo: String? = null,
    // —— Elements 选中节点的计算样式 ——
    val computedStyles: List<StyleEntry> = emptyList()
) {
    companion object {
        const val MAX_CONSOLE = 500
        const val MAX_NETWORK = 300
        const val MAX_SCRIPTS = 500
    }
}

/** Cookie 信息（Network.getCookies 返回项）。 */
data class CookieInfo(
    val name: String,
    val value: String,
    val domain: String = "",
    val path: String = "",
    val expires: Double = 0.0,
    val size: Int = 0,
    val httpOnly: Boolean = false,
    val secure: Boolean = false,
    val sameSite: String = "",
    val session: Boolean = false
)

/** Storage 键值对（localStorage / sessionStorage 项）。 */
data class StorageItem(val key: String, val value: String)

/** 计算样式条目（CSS.getComputedStyleForNode）。 */
data class StyleEntry(val name: String, val value: String)

/** CPU Profile 节点（Profiler.stop 返回 profile.nodes 简化）。 */
data class ProfileNode(
    val id: Int,
    val function: String,
    val url: String,
    val line: Int,
    val hitCount: Long,
    val children: List<Int> = emptyList()
)

/** JS 脚本信息（来自 Debugger.scriptParsed 事件）。 */
data class ScriptInfo(
    val scriptId: String,
    val url: String = "",
    val startLine: Int = 0,
    val startColumn: Int = 0,
    val endLine: Int = 0,
    val endColumn: Int = 0,
    val executionContextId: Int = 0,
    val hash: String = "",
    val isContentScript: Boolean = false
)

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
            statusMessage = "${if (_ui.value.mode == BridgeMode.ROOT) "Root" else "Shizuku"} binder 死亡",
            cdpConnected = false)
    }

    /** Root 模式：未获取 root 权限（Shell.getShell 抛 NoShellException）。 */
    fun onRootNoShell() = update {
        it.copy(bridgeState = BridgeState.ROOT_NO_SHELL,
            statusMessage = "未获取 Root 权限，请改用 Shizuku 模式或在 root 设备使用")
    }

    /** Shizuku 模式：已弹出授权请求，等待用户操作。 */
    fun onWaitingPermission() = update {
        it.copy(bridgeState = BridgeState.WAITING_PERMISSION,
            statusMessage = "请在 Shizuku 弹窗中授权本应用")
    }

    /** 初始化模式（仅同步 UiState.mode，不重置状态，用于 Activity 启动时）。 */
    fun initMode(mode: BridgeMode) = update { it.copy(mode = mode) }

    /** 切换桥接来源模式（由 UI 触发）。同步重置状态以便 Activity 立即重新绑定。 */
    fun setMode(mode: BridgeMode) {
        val oldBridge = bridge
        bridge = null
        update {
            it.copy(mode = mode, bridgeState = BridgeState.IDLE,
                statusMessage = "已切换至${if (mode == BridgeMode.ROOT) "Root" else "Shizuku"}模式，请重新连接",
                cdpConnected = false, probeOk = null, discoveryError = null,
                version = null, httpTargets = emptyList(), abstractTargets = emptyList(),
                selectedAbstract = null)
        }
        viewModelScope.launch(Dispatchers.IO) {
            stopCdpInternal()
            runCatching { oldBridge?.stopBridge() }
        }
    }

    fun onBridgeConnected(b: ICdpBridge, port: Int) {
        bridge = b
        bridgePort = port
        discovery = TargetDiscovery(port)
        val m = _ui.value.mode
        update { it.copy(bridgeState = BridgeState.BOUND,
            statusMessage = "${if (m == BridgeMode.ROOT) "Root" else "Shizuku"} 服务已绑定，可启动桥接") }
        refreshAbstractTargetsDetailed()
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

    /** 刷新带应用信息的 target 列表（abstract socket + 所属进程/包名/应用名）。 */
    fun refreshAbstractTargetsDetailed() {
        val b = bridge ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val infos = runCatching { b.listTargetsWithInfo() }.getOrDefault(emptyList())
            // 解析 "sock\tproc\tpkg\tlabel"，label 可能为空
            val models = infos.mapNotNull { line ->
                val p = line.split("\t")
                if (p.size < 4) return@mapNotNull null
                AbstractTarget(
                    socketName = p[0],
                    processName = p[1],
                    packageName = p[2],
                    appLabel = p[3]
                )
            }
            // 兼容：如果新版方法无响应，回退到旧 listTargets 仅显示 socket 名
            val fallback = if (models.isEmpty()) {
                runCatching { b.listTargets() }.getOrDefault(emptyList()).map { AbstractTarget(it, "", "", "") }
            } else models
            update { it.copy(abstractTargetsDetailed = fallback,
                abstractTargets = fallback.map { it.socketName }) }
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

    // ---------- Page 域：导航 / 重载 / 截图 ----------
    // 对齐 Chrome DevTools 的页面控制能力（无需电脑即可在手机上触发）。

    /** 重新加载当前页面（Page.reload）。 */
    fun reloadPage() {
        val c = cdp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { c.send("Page.reload") }
            update { it.copy(statusMessage = "已触发页面重载") }
        }
    }

    /** 导航到指定 URL（Page.navigate）。 */
    fun navigateTo(url: String) {
        val c = cdp ?: return
        if (url.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val params = JsonObject().apply { addProperty("url", url) }
            runCatching { c.send("Page.navigate", params) }
            update { it.copy(statusMessage = "已导航至 $url") }
        }
    }

    /** 截图当前页面（Page.captureScreenshot），返回 base64 PNG data url。 */
    fun captureScreenshot(onResult: (String?) -> Unit) {
        val c = cdp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val res = runCatching { c.send("Page.captureScreenshot") }.getOrNull()
            val data = res?.get("data")?.asString
            onResult(data?.let { "data:image/png;base64,$it" })
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
            // enable 域：Runtime/Network/DOM/Debugger/Page，对齐 Chrome DevTools 连上即启用
            runCatching { c.send("Runtime.enable") }
            runCatching { c.send("Network.enable") }
            runCatching { c.send("DOM.enable") }
            runCatching { c.send("Debugger.enable") }
            runCatching { c.send("Page.enable") }
            update { it.copy(sourcesEnabled = true) }
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
                console = emptyList(), exceptions = emptyList(), network = emptyList(),
                scripts = emptyList(), scriptSources = emptyMap(), sourcesEnabled = false) }
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
            // 关键：returnByValue=false + generatePreview=true，这样返回的 RemoteObject
            // 带 description 和 preview，能在 UI 像原版 DevTools 一样展开对象/数组。
            // 来源: https://chromedevtools.github.io/devtools-protocol/tot/Runtime/#method-evaluate
            val params = JsonObject().apply {
                addProperty("expression", expression)
                addProperty("returnByValue", false)
                addProperty("generatePreview", true)
                addProperty("awaitPromise", true)
                addProperty("userGesture", true)
            }
            val result = runCatching { c.send("Runtime.evaluate", params) }.getOrNull()
            if (result == null) {
                pushConsole(ConsoleEntry(level = "error", text = "→ $expression\n(eval 失败：无响应)"))
                return@launch
            }
            // 异常分支：Runtime.evaluate 的 exceptionDetails
            val excDetails = result.getAsJsonObject("exceptionDetails")
            if (excDetails != null) {
                val text = excDetails.get("text")?.asString
                    ?: excDetails.getAsJsonObject("exception")?.get("description")?.asString
                    ?: "eval exception"
                val st = excDetails.getAsJsonObject("stackTrace")
                val trace = formatStackTrace(st)
                val url = st?.getAsJsonArray("callFrames")?.firstOrNull()?.asJsonObject?.let { f ->
                    f.get("url")?.asString
                }
                val line = st?.getAsJsonArray("callFrames")?.firstOrNull()?.asJsonObject?.get("lineNumber")?.asInt
                pushConsole(ConsoleEntry(
                    level = "error",
                    text = "→ $expression\n$text",
                    stackTrace = trace, url = url, lineNumber = line
                ))
                return@launch
            }
            // 正常分支：result 是 RemoteObject，保留结构可展开
            val resObj = result.getAsJsonObject("result")
            val remote = com.devtools.cdp.data.RemoteObject.parse(resObj)
            if (remote != null) {
                // 把表达式作为"输入提示"，返回值作为可展开对象
                pushConsole(ConsoleEntry(
                    level = "info",
                    args = listOf(remote),
                    text = "→ $expression"
                ))
            } else {
                pushConsole(ConsoleEntry(level = "info", text = "→ $expression\n(无返回值)"))
            }
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

    // ---------- Network：拉响应体 / 重发 / 自定义请求 ----------

    /** 拉取某请求的响应体（Network.getResponseBody）。 */
    fun fetchNetworkBody(req: NetworkRequest) {
        val c = cdp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val params = JsonObject().apply { addProperty("requestId", req.requestId) }
            val res = runCatching { c.send("Network.getResponseBody", params) }.getOrNull()
            val body = res?.get("body")?.asString
            val b64 = res?.get("base64Encoded")?.asBoolean ?: false
            upsertNetwork(req.requestId) {
                it.body = body ?: ""
                it.bodyBase64Encoded = b64
                it.bodyFetched = true
            }
        }
    }

    /**
     * 重发已捕获的 XHR 请求（Network.replayXHR）。
     * Chrome DevTools「Replay XHR」功能的等价实现：用原 requestId 重新发起，
     * 请求头/body 与原请求一致。仅对 XHR/Fetch 类型有效（Document/Script 等不支持）。
     * 来源: https://chromedevtools.github.io/devtools-protocol/tot/Network/#method-replayXHR
     */
    fun replayRequest(requestId: String) {
        val c = cdp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val params = JsonObject().apply { addProperty("requestId", requestId) }
            val ok = runCatching { c.send("Network.replayXHR", params) }.isSuccess
            update {
                it.copy(statusMessage = if (ok) "已重发请求 $requestId（留意列表新增条目）"
                    else "重发失败：replayXHR 仅支持 XHR/Fetch 类型")
            }
        }
    }

    /**
     * 发送自定义请求（编辑后重发 / 改 token）。
     *
     * Chrome DevTools「Edit and Resend」的等价实现：在 page 上下文内用 fetch() 发起请求，
     * 请求头/body 完全由调用方控制，可改 Authorization/Cookie 等 token。
     *
     * 实现：Runtime.evaluate 执行异步 fetch 并 awaitPromise。
     * 用 try/catch 包 fetch 以便把网络错误也返回给 UI（避免 unhandled rejection）。
     *
     * @param url 目标 URL
     * @param method HTTP 方法
     * @param headers 请求头键值对（已包含用户修改的 token）
     * @param body 请求体（GET/HEAD 自动忽略）
     * @param onResult 回调：(status, statusText, 响应体预览) 或错误信息
     */
    fun sendCustomRequest(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        onResult: (success: Boolean, summary: String) -> Unit
    ) {
        val c = cdp ?: return
        if (url.isBlank()) {
            onResult(false, "URL 不能为空")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            // 构造 fetch 调用：把 headers 转成 JS 对象字面量字符串
            val headerJs = headers.entries.joinToString(", ") { (k, v) ->
                "\"${escapeJs(k)}\":\"${escapeJs(v)}\""
            }
            val methodUpper = method.uppercase().ifBlank { "GET" }
            val bodyArg = if (methodUpper == "GET" || methodUpper == "HEAD" || body.isNullOrBlank()) {
                "undefined"
            } else {
                "\"${escapeJs(body)}\""
            }
            // 包一层 try/catch，避免 fetch 抛出的网络错误变成 unhandled rejection
            val expression = """
                (async () => {
                    try {
                        const resp = await fetch(${jsStr(url)}, {
                            method: ${jsStr(methodUpper)},
                            headers: { $headerJs },
                            body: $bodyArg,
                            credentials: 'include'
                        });
                        const text = await resp.text();
                        return JSON.stringify({
                            ok: true,
                            status: resp.status,
                            statusText: resp.statusText,
                            headers: Object.fromEntries(resp.headers.entries()),
                            body: text.substring(0, 2000)
                        });
                    } catch (e) {
                        return JSON.stringify({ ok: false, error: String(e) });
                    }
                })()
            """.trimIndent()
            val params = JsonObject().apply {
                addProperty("expression", expression)
                addProperty("awaitPromise", true)
                addProperty("returnByValue", true)
                addProperty("userGesture", true)
            }
            val res = runCatching { c.send("Runtime.evaluate", params) }.getOrNull()
            if (res == null) {
                onResult(false, "CDP 调用失败")
                return@launch
            }
            val exc = res.getAsJsonObject("exceptionDetails")
            if (exc != null) {
                val msg = exc.get("text")?.asString ?: "evaluate exception"
                onResult(false, "执行失败：$msg")
                return@launch
            }
            val resultVal = res.getAsJsonObject("result")?.get("value")?.asString
            if (resultVal == null) {
                onResult(false, "无返回值")
                return@launch
            }
            try {
                val parsed = com.google.gson.JsonParser.parseString(resultVal).asJsonObject
                if (parsed.get("ok")?.asBoolean == true) {
                    val status = parsed.get("status")?.asInt ?: 0
                    val statusText = parsed.get("statusText")?.asString ?: ""
                    val bodyPreview = parsed.get("body")?.asString ?: ""
                    onResult(true, "$status $statusText\n${bodyPreview.take(500)}")
                } else {
                    val err = parsed.get("error")?.asString ?: "fetch failed"
                    onResult(false, "请求失败：$err")
                }
            } catch (e: Throwable) {
                onResult(false, "解析响应失败：${e.message}")
            }
        }
    }

    /** 把字符串转成 JS 字面量字符串（带引号），转义引号/反斜杠/换行。 */
    private fun jsStr(s: String): String {
        val escaped = s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        return "\"$escaped\""
    }

    private fun escapeJs(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r")

    // ---------- Elements：DOM 编辑 ----------

    /** 设置元素属性（DOM.setAttributeValue）。 */
    fun setAttribute(nodeId: Int, name: String, value: String) {
        val c = cdp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val params = JsonObject().apply {
                addProperty("nodeId", nodeId)
                addProperty("name", name)
                addProperty("value", value)
            }
            runCatching { c.send("DOM.setAttributeValue", params) }
            refreshDom()
        }
    }

    /** 删除元素属性（DOM.removeAttribute）。 */
    fun removeAttribute(nodeId: Int, name: String) {
        val c = cdp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val params = JsonObject().apply {
                addProperty("nodeId", nodeId)
                addProperty("name", name)
            }
            runCatching { c.send("DOM.removeAttribute", params) }
            refreshDom()
        }
    }

    /** 替换整个元素 HTML（DOM.setOuterHTML）—— 用于改标签结构。 */
    fun setOuterHTML(nodeId: Int, html: String) {
        val c = cdp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val params = JsonObject().apply {
                addProperty("nodeId", nodeId)
                addProperty("outerHTML", html)
            }
            runCatching { c.send("DOM.setOuterHTML", params) }
            refreshDom()
        }
    }

    /** 设置节点文本内容（DOM.setNodeValue）—— 用于改文本节点。 */
    fun setNodeValue(nodeId: Int, value: String) {
        val c = cdp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val params = JsonObject().apply {
                addProperty("nodeId", nodeId)
                addProperty("value", value)
            }
            runCatching { c.send("DOM.setNodeValue", params) }
            refreshDom()
        }
    }

    /** 设置元素 innerHTML（DOM.setNodeValue 不支持 HTML，用 Runtime.evaluate 注入）。 */
    fun setInnerHTML(nodeId: Int, html: String) {
        val c = cdp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            // 解析 nodeId → RemoteObject 再设 innerHTML
            val resolveParams = JsonObject().apply { addProperty("nodeId", nodeId) }
            val resolved = runCatching { c.send("DOM.resolveNode", resolveParams) }.getOrNull()
            val objectGroup = resolved?.getAsJsonObject("object")?.get("objectId")?.asString
            if (objectGroup != null) {
                val evalParams = JsonObject().apply {
                    addProperty("functionDeclaration",
                        "function(html){ this.innerHTML = html; }")
                    addProperty("objectId", objectGroup)
                    val args = com.google.gson.JsonArray().apply {
                        add(JsonObject().apply { addProperty("value", html) })
                    }
                    add("arguments", args)
                    addProperty("returnByValue", true)
                }
                runCatching { c.send("Runtime.callFunctionOn", evalParams) }
                runCatching {
                    val relParams = JsonObject().apply { addProperty("objectId", objectGroup) }
                    c.send("Runtime.releaseObject", relParams)
                }
                refreshDom()
            }
        }
    }

    // ---------- Sources：脚本源码 ----------

    /** 启用 Debugger 域并触发 scriptParsed 事件收集脚本列表。 */
    fun enableSources() {
        val c = cdp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { c.send("Debugger.enable") }
            update { it.copy(sourcesEnabled = true) }
        }
    }

    /** 拉取某脚本的源码（Debugger.getScriptSource）。 */
    fun fetchScriptSource(scriptId: String) {
        val c = cdp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val params = JsonObject().apply { addProperty("scriptId", scriptId) }
            val res = runCatching { c.send("Debugger.getScriptSource", params) }.getOrNull()
            val src = res?.get("scriptSource")?.asString ?: "(无源码)"
            update { st ->
                st.copy(scriptSources = st.scriptSources + (scriptId to src))
            }
        }
    }

    // ---------- 事件分发 ----------

    /** 把 CDP stackTrace.callFrames 格式化成可读调用栈（每帧一行）。 */
    private fun formatStackTrace(st: com.google.gson.JsonObject?): String? {
        val frames = st?.getAsJsonArray("callFrames") ?: return null
        if (frames.size() == 0) return null
        return frames.joinToString("\n") { f ->
            val o = f.asJsonObject
            val fn = o.get("functionName")?.asString?.ifEmpty { "(anonymous)" } ?: "(anonymous)"
            val url = o.get("url")?.asString ?: "<unknown>"
            val line = o.get("lineNumber")?.asInt
            val col = o.get("columnNumber")?.asInt
            val loc = if (line != null && col != null) "$url:$line:$col"
                      else if (line != null) "$url:$line" else url
            "  at $fn ($loc)"
        }
    }

    private fun handleEvent(ev: CdpEvent) {
        when (ev.method) {
            "Runtime.consoleAPICalled" -> {
                // 完整解析：args 转成 RemoteObject 列表（保留对象结构可展开），
                // stackTrace 提取调用栈，第一帧 url+line 作为来源定位。
                val level = ev.params.get("type")?.asString ?: "log"
                val argsArr = ev.params.getAsJsonArray("args")
                val args = argsArr?.mapNotNull { com.devtools.cdp.data.RemoteObject.parse(it) } ?: emptyList()
                val st = ev.params.getAsJsonObject("stackTrace")
                val firstFrame = st?.getAsJsonArray("callFrames")?.firstOrNull()?.asJsonObject
                pushConsole(ConsoleEntry(
                    level = level,
                    args = args,
                    stackTrace = formatStackTrace(st),
                    url = firstFrame?.get("url")?.asString,
                    lineNumber = firstFrame?.get("lineNumber")?.asInt,
                    columnNumber = firstFrame?.get("columnNumber")?.asInt
                ))
            }
            "Runtime.exceptionThrown" -> {
                val details = ev.params.getAsJsonObject("exceptionDetails")
                val exc = details?.getAsJsonObject("exception")
                val text = exc?.get("description")?.asString
                    ?: details?.get("text")?.asString ?: "exception"
                val st = details?.getAsJsonObject("stackTrace")
                val firstFrame = st?.getAsJsonArray("callFrames")?.firstOrNull()?.asJsonObject
                pushException(ExceptionEntry(
                    text = text,
                    stackTrace = formatStackTrace(st),
                    url = firstFrame?.get("url")?.asString,
                    lineNumber = firstFrame?.get("lineNumber")?.asInt
                ))
            }
            "Network.requestWillBeSent" -> {
                val rid = ev.params.get("requestId")?.asString ?: return
                val req = ev.params.getAsJsonObject("request")
                upsertNetwork(rid) {
                    it.url = req?.get("url")?.asString ?: ""
                    it.method = req?.get("method")?.asString ?: ""
                    it.type = ev.params.get("type")?.asString ?: ""
                    // 请求头（CDP 在 request.headers 里给扁平 map）
                    it.requestHeaders = req?.getAsJsonObject("headers")?.entrySet()
                        ?.associate { e -> e.key to (e.value?.asString ?: "") } ?: emptyMap()
                    it.postData = req?.get("postData")?.takeIf { !it.isJsonNull }?.asString
                    it.hasPostData = req?.get("hasPostData")?.asBoolean ?: (it.postData != null)
                }
            }
            "Network.responseReceived" -> {
                val rid = ev.params.get("requestId")?.asString ?: return
                val resp = ev.params.getAsJsonObject("response")
                upsertNetwork(rid) {
                    it.status = resp?.get("status")?.asInt ?: 0
                    it.statusText = resp?.get("statusText")?.asString ?: ""
                    it.mimeType = resp?.get("mimeType")?.asString ?: ""
                    it.responseHeaders = resp?.getAsJsonObject("headers")?.entrySet()
                        ?.associate { e -> e.key to (e.value?.asString ?: "") } ?: emptyMap()
                    it.remoteIP = resp?.get("remoteIPAddress")?.asString ?: ""
                    it.remotePort = resp?.get("remotePort")?.asInt ?: 0
                    it.protocol = resp?.get("protocol")?.asString ?: ""
                    // timing 嵌套对象
                    it.timing = resp?.getAsJsonObject("timing")?.entrySet()
                        ?.associate { e -> e.key to (e.value?.asDouble ?: 0.0) } ?: emptyMap()
                }
            }
            "Network.loadingFinished" -> {
                val rid = ev.params.get("requestId")?.asString ?: return
                upsertNetwork(rid) {
                    it.finished = true
                    it.encodedDataLength = ev.params.get("encodedDataLength")?.asLong ?: 0
                }
            }
            "Network.loadingFailed" -> {
                val rid = ev.params.get("requestId")?.asString ?: return
                upsertNetwork(rid) {
                    it.failed = true
                    it.errorText = ev.params.get("errorText")?.asString
                }
            }
            "Debugger.scriptParsed" -> {
                // 收集 JS 脚本信息，供 Sources 页查看
                val sid = ev.params.get("scriptId")?.asString ?: return
                val script = ScriptInfo(
                    scriptId = sid,
                    url = ev.params.get("url")?.asString ?: "",
                    startLine = ev.params.get("startLine")?.asInt ?: 0,
                    startColumn = ev.params.get("startColumn")?.asInt ?: 0,
                    endLine = ev.params.get("endLine")?.asInt ?: 0,
                    endColumn = ev.params.get("endColumn")?.asInt ?: 0,
                    executionContextId = ev.params.get("executionContextId")?.asInt ?: 0,
                    hash = ev.params.get("hash")?.asString ?: "",
                    isContentScript = ev.params.get("isContentScript")?.asBoolean ?: false
                )
                update { st ->
                    val list = (st.scripts + script).takeLast(UiState.MAX_SCRIPTS)
                    st.copy(scripts = list)
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

    // ==================== Application（Storage）====================
    // 对齐 Chrome DevTools Application 面板：Cookie / LocalStorage / SessionStorage。
    // Cookie 走 Network 域；Web Storage 走 Runtime.evaluate（localStorage/sessionStorage）。

    /** 拉取当前页面所有 Cookie（Network.getCookies）。 */
    fun getCookies() {
        val c = cdp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val res = runCatching { c.send("Network.getCookies") }.getOrNull()
            val arr = res?.getAsJsonArray("cookies") ?: return@launch
            val list = arr.mapNotNull { it.asJsonObject?.let { parseCookie(it) } }
            update { it.copy(cookies = list) }
        }
    }

    private fun parseCookie(o: com.google.gson.JsonObject): CookieInfo? = try {
        CookieInfo(
            name = o.get("name")?.asString ?: "",
            value = o.get("value")?.asString ?: "",
            domain = o.get("domain")?.asString ?: "",
            path = o.get("path")?.asString ?: "",
            expires = o.get("expires")?.asDouble ?: 0.0,
            size = o.get("size")?.asInt ?: 0,
            httpOnly = o.get("httpOnly")?.asBoolean ?: false,
            secure = o.get("secure")?.asBoolean ?: false,
            sameSite = o.get("sameSite")?.asString ?: "",
            session = o.get("session")?.asBoolean ?: false
        )
    } catch (_: Throwable) { null }

    /** 设置/更新 Cookie（Network.setCookie）。 */
    fun setCookie(name: String, value: String, domain: String, path: String) {
        val c = cdp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val params = JsonObject().apply {
                addProperty("name", name)
                addProperty("value", value)
                if (domain.isNotBlank()) addProperty("domain", domain)
                if (path.isNotBlank()) addProperty("path", path)
            }
            runCatching { c.send("Network.setCookie", params) }
            getCookies()
        }
    }

    /** 删除指定 Cookie（Network.deleteCookies）。 */
    fun deleteCookie(name: String, domain: String, path: String) {
        val c = cdp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val params = JsonObject().apply {
                addProperty("name", name)
                if (domain.isNotBlank()) addProperty("domain", domain)
                if (path.isNotBlank()) addProperty("path", path)
            }
            runCatching { c.send("Network.deleteCookies", params) }
            getCookies()
        }
    }

    /** 清空所有 Cookie（Network.clearBrowserCookies）。 */
    fun clearCookies() {
        val c = cdp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { c.send("Network.clearBrowserCookies") }
            getCookies()
        }
    }

    /** 拉取 localStorage（Runtime.evaluate 序列化为 JSON）。 */
    fun getLocalStorage() = evalStorage("localStorage")

    /** 拉取 sessionStorage（Runtime.evaluate 序列化为 JSON）。 */
    fun getSessionStorage() = evalStorage("sessionStorage")

    private fun evalStorage(which: String) {
        val c = cdp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val expr = "JSON.stringify(Object.assign(...Object.keys($which).map(k=>({[k]:$which.getItem(k)}))))"
            val params = JsonObject().apply {
                addProperty("expression", expr)
                addProperty("returnByValue", true)
            }
            val res = runCatching { c.send("Runtime.evaluate", params) }.getOrNull()
            val json = res?.getAsJsonObject("result")?.get("value")?.asString ?: "{}"
            val items = parseStorageJson(json)
            update { st ->
                if (which == "localStorage") st.copy(localStorage = items)
                else st.copy(sessionStorage = items)
            }
        }
    }

    private fun parseStorageJson(json: String): List<StorageItem> = try {
        val obj = com.google.gson.JsonParser.parseString(json).asJsonObject
        obj.entrySet().map { StorageItem(it.key, it.value?.asString ?: "") }
    } catch (_: Throwable) { emptyList() }

    /** 设置 storage 项（localStorage.setItem / sessionStorage.setItem）。 */
    fun setStorageItem(which: String, key: String, value: String) {
        val c = cdp ?: return
        if (key.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val expr = "$which.setItem(${jsStr(key)},${jsStr(value)})"
            val params = JsonObject().apply { addProperty("expression", expr) }
            runCatching { c.send("Runtime.evaluate", params) }
            if (which == "localStorage") getLocalStorage() else getSessionStorage()
        }
    }

    /** 删除 storage 项（removeItem）。 */
    fun removeStorageItem(which: String, key: String) {
        val c = cdp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val expr = "$which.removeItem(${jsStr(key)})"
            val params = JsonObject().apply { addProperty("expression", expr) }
            runCatching { c.send("Runtime.evaluate", params) }
            if (which == "localStorage") getLocalStorage() else getSessionStorage()
        }
    }

    /** 清空 storage（clear）。 */
    fun clearStorage(which: String) {
        val c = cdp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val params = JsonObject().apply { addProperty("expression", "$which.clear()") }
            runCatching { c.send("Runtime.evaluate", params) }
            if (which == "localStorage") getLocalStorage() else getSessionStorage()
        }
    }

    // ==================== Performance（CPU Profile）====================
    // 对齐 Chrome DevTools Performance 录制：Profiler.start / Profiler.stop。
    // stop 返回 profile.nodes / startTime / endTime，简化为热点函数排行展示。

    /** 开始 CPU 性能录制（Profiler.start）。 */
    fun startPerfRecording() {
        val c = cdp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { c.send("Profiler.enable") }
            runCatching { c.send("Profiler.start") }
            update { it.copy(perfRecording = true, perfProfile = null,
                statusMessage = "性能录制中…") }
        }
    }

    /** 停止录制并解析热点（Profiler.stop）。 */
    fun stopPerfRecording() {
        val c = cdp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val res = runCatching { c.send("Profiler.stop") }.getOrNull()
            val profile = res?.getAsJsonObject("profile")
            val nodes = profile?.getAsJsonArray("nodes")
            val sb = StringBuilder()
            sb.append("=== CPU Profile 热点函数 ===\n")
            // 按 hitCount 排序取 top 20
            val hot = mutableListOf<ProfileNode>()
            nodes?.forEach { n ->
                val o = n.asJsonObject
                val cf = o.getAsJsonObject("callFrame")
                hot.add(ProfileNode(
                    id = o.get("id")?.asInt ?: 0,
                    function = cf?.get("functionName")?.asString?.ifEmpty { "(anonymous)" } ?: "(anonymous)",
                    url = cf?.get("url")?.asString ?: "",
                    line = cf?.get("lineNumber")?.asInt ?: 0,
                    hitCount = o.get("hitCount")?.asLong ?: 0,
                    children = o.getAsJsonArray("children")?.map { it.asInt } ?: emptyList()
                ))
            }
            hot.sortedByDescending { it.hitCount }.take(20).forEach { node ->
                val loc = if (node.url.isNotBlank()) "  ${node.url.substringAfterLast('/')}:${node.line}" else ""
                sb.append("${node.hitCount.toString().padStart(8)}  ${node.function}$loc\n")
            }
            val startT = profile?.get("startTime")?.asLong ?: 0
            val endT = profile?.get("endTime")?.asLong ?: 0
            sb.append("\n总时长：${(endT - startT) / 1000.0} ms（采样 ${hot.size} 节点）")
            update { it.copy(perfRecording = false, perfProfile = sb.toString(),
                statusMessage = "性能录制完成") }
        }
    }

    // ==================== Memory ====================
    // 对齐 Chrome DevTools Memory：用 Performance.getMetrics 拿内存指标，
    // HeapProfiler.takeHeapSnapshot 会回流大量数据，简化为指标视图。

    /** 拉取内存指标（Performance.getMetrics）。 */
    fun getMemoryMetrics() {
        val c = cdp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { c.send("Performance.enable") }
            val res = runCatching { c.send("Performance.getMetrics") }.getOrNull()
            val arr = res?.getAsJsonArray("metrics") ?: return@launch
            val map = arr.mapNotNull { m ->
                val o = m.asJsonObject
                val name = o.get("name")?.asString ?: return@mapNotNull null
                name to (o.get("value")?.asDouble ?: 0.0)
            }.toMap()
            update { it.copy(memoryMetrics = map) }
        }
    }

    // ==================== Security ====================
    // 对齐 Chrome DevTools Security 面板：显示页面安全状态。
    // CDP 无独立 Security 域，用 Runtime.evaluate 取 location + document.securityPolicy。

    /** 拉取页面安全信息。 */
    fun getSecurityInfo() {
        val c = cdp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val expr = """JSON.stringify({
                href: location.href,
                protocol: location.protocol,
                host: location.host,
                origin: location.origin,
                isSecure: location.protocol === 'https:' || location.hostname === 'localhost' || location.hostname === '127.0.0.1',
                mixedContent: (function(){ try { return document.querySelector('[src^="http:"],[href^="http:"]') !== null } catch(e){ return false } })()
            })""".trimIndent()
            val params = JsonObject().apply {
                addProperty("expression", expr)
                addProperty("returnByValue", true)
            }
            val res = runCatching { c.send("Runtime.evaluate", params) }.getOrNull()
            val json = res?.getAsJsonObject("result")?.get("value")?.asString
            update { it.copy(securityInfo = json ?: "{}") }
        }
    }

    // ==================== Elements：计算样式 ====================
    // 对齐 Chrome DevTools Elements 的 Computed 面板：CSS.getComputedStyleForNode。

    /** 拉取选中节点的计算样式（CSS.getComputedStyleForNode）。 */
    fun getComputedStyles(nodeId: Int) {
        val c = cdp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { c.send("CSS.enable") }
            val params = JsonObject().apply { addProperty("nodeId", nodeId) }
            val res = runCatching { c.send("CSS.getComputedStyleForNode", params) }.getOrNull()
            val arr = res?.getAsJsonObject("computedStyle")?.getAsJsonArray("cssProperties") ?: return@launch
            val list = arr.mapNotNull { p ->
                val o = p.asJsonObject
                val name = o.get("name")?.asString ?: return@mapNotNull null
                val value = o.get("value")?.asString ?: ""
                if (value.isBlank()) null else StyleEntry(name, value)
            }
            update { it.copy(computedStyles = list) }
        }
    }

    // ==================== 便捷功能 ====================
    // 发掘的额外功能：清除缓存、页面信息、设备信息。

    /** 清除浏览器缓存（Network.clearBrowserCache）。 */
    fun clearBrowserCache() {
        val c = cdp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { c.send("Network.clearBrowserCache") }
            update { it.copy(statusMessage = "已清除浏览器缓存") }
        }
    }

    /** 禁用缓存（Network.setCacheDisabled），对齐 DevTools Network 的 Disable cache。 */
    fun setCacheDisabled(disabled: Boolean) {
        val c = cdp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val params = JsonObject().apply { addProperty("cacheDisabled", disabled) }
            runCatching { c.send("Network.setCacheDisabled", params) }
            update { it.copy(statusMessage = if (disabled) "已禁用缓存" else "已启用缓存") }
        }
    }

    /** 拉取页面信息（标题/URL/UserAgent/视口/语言）via Runtime.evaluate。 */
    fun getPageInfo(onResult: (String) -> Unit) {
        val c = cdp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val expr = """JSON.stringify({
                title: document.title, url: location.href, ua: navigator.userAgent,
                lang: navigator.language, platform: navigator.platform,
                viewport: window.innerWidth + 'x' + window.innerHeight,
                screen: screen.width + 'x' + screen.height + ' (dpr ' + window.devicePixelRatio + ')',
                online: navigator.onLine, cookies: document.cookie.split(';').length
            })""".trimIndent()
            val params = JsonObject().apply {
                addProperty("expression", expr)
                addProperty("returnByValue", true)
            }
            val res = runCatching { c.send("Runtime.evaluate", params) }.getOrNull()
            val json = res?.getAsJsonObject("result")?.get("value")?.asString ?: "{}"
            val sb = StringBuilder()
            try {
                val o = com.google.gson.JsonParser.parseString(json).asJsonObject
                sb.append("标题: ${o.get("title")?.asString ?: ""}\n")
                sb.append("URL: ${o.get("url")?.asString ?: ""}\n")
                sb.append("UA: ${o.get("ua")?.asString ?: ""}\n")
                sb.append("语言: ${o.get("lang")?.asString ?: ""}  平台: ${o.get("platform")?.asString ?: ""}\n")
                sb.append("视口: ${o.get("viewport")?.asString ?: ""}\n")
                sb.append("屏幕: ${o.get("screen")?.asString ?: ""}\n")
                sb.append("在线: ${o.get("online")?.asBoolean ?: false}  Cookie数: ${o.get("cookies")?.asInt ?: 0}")
            } catch (_: Throwable) { sb.append(json) }
            onResult(sb.toString())
        }
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
