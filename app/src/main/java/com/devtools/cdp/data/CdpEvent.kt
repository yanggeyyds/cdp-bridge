package com.devtools.cdp.data

import com.google.gson.JsonObject

/**
 * CDP 事件包装：method + params（+ 可选 sessionId）。
 *
 * 研究：CDP 事件报文 { "method": "Domain.event", "params": {...} }，
 * 带 target session 时多一个 "sessionId" 字段。
 * 来源: https://chromedevtools.github.io/devtools-protocol/
 * 来源: https://github.com/aslushnikov/getting-started-with-cdp/blob/master/README.md
 */
data class CdpEvent(
    val method: String,
    val params: JsonObject,
    val sessionId: String? = null
)

/** Console 日志条目（来自 Runtime.consoleAPICalled）。 */
data class ConsoleEntry(
    val level: String,            // log/info/warning/error/debug/verbose
    /** console.log 的参数（已是 RemoteObject 列表，保留对象结构可展开）。 */
    val args: List<RemoteObject> = emptyList(),
    /** 兜底纯文本（args 为空时用，或 CDP 直接给的 text）。 */
    val text: String = "",
    /** 调用栈（callFrames 拼成的可读串）。 */
    val stackTrace: String? = null,
    val url: String? = null,
    val lineNumber: Int? = null,
    val columnNumber: Int? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/** 异常条目（来自 Runtime.exceptionThrown）。 */
data class ExceptionEntry(
    val text: String,
    val stackTrace: String? = null,
    val url: String? = null,
    val lineNumber: Int? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/** Network 请求聚合（requestWillBeSent + responseReceived + loadingFinished）。 */
data class NetworkRequest(
    val requestId: String,
    var url: String = "",
    var method: String = "",
    var status: Int = 0,
    var mimeType: String = "",
    var type: String = "",
    var finished: Boolean = false,
    var failed: Boolean = false,
    var errorText: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
