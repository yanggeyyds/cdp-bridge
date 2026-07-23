package com.devtools.cdp.data

import com.google.gson.annotations.SerializedName

/**
 * CDP target（/json/list 返回的单项）。
 *
 * 研究：/json/list 返回数组，每项含 id/type/title/url/webSocketDebuggerUrl 等。
 * 来源: https://chromedevtools.github.io/devtools-protocol/
 * 来源: https://learn.microsoft.com/en-za/microsoft-edge/devtools-protocol/#jsonversion
 */
data class TargetInfo(
    @SerializedName("id") val id: String?,
    @SerializedName("type") val type: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("url") val url: String?,
    @SerializedName("webSocketDebuggerUrl") val webSocketDebuggerUrl: String?,
    @SerializedName("devtoolsFrontendUrl") val devtoolsFrontendUrl: String?,
    @SerializedName("faviconUrl") val faviconUrl: String?,
    @SerializedName("description") val description: String?
) {
    /** 显示用：标题优先，无则 url，再无则 id。 */
    fun displayTitle(): String =
        title?.takeIf { it.isNotBlank() } ?: url?.takeIf { it.isNotBlank() } ?: id ?: "(unknown)"
}

/**
 * CDP 版本信息（/json/version 返回）。
 */
data class VersionInfo(
    @SerializedName("Browser") val browser: String?,
    @SerializedName("Protocol-Version") val protocolVersion: String?,
    @SerializedName("User-Agent") val userAgent: String?,
    @SerializedName("V8-Version") val v8Version: String?,
    @SerializedName("WebKit-Version") val webKitVersion: String?,
    @SerializedName("webSocketDebuggerUrl") val webSocketDebuggerUrl: String?
)
