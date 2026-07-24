package com.devtools.cdp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devtools.cdp.data.NetworkRequest

private enum class NetFilter(val label: String) {
    ALL("全部"), XHR("XHR"), DOC("Doc"), JS("JS"), CSS("CSS"), IMG("Img"), OTHER("其他")
}

private fun NetworkRequest.matchType(f: NetFilter): Boolean = when (f) {
    NetFilter.ALL -> true
    NetFilter.XHR -> type == "XHR"
    NetFilter.DOC -> type == "Document"
    NetFilter.JS -> type == "Script"
    NetFilter.CSS -> type == "Stylesheet"
    NetFilter.IMG -> type == "Image"
    NetFilter.OTHER -> type !in listOf("XHR", "Document", "Script", "Stylesheet", "Image", "")
}

private enum class DetailTab(val label: String) {
    HEADERS("Headers"), RESPONSE("Response"), PAYLOAD("Payload"), TIMING("Timing")
}

/**
 * Network 页：对齐 Chrome DevTools Network。
 *
 * 功能：
 *  - 请求列表：方法/状态徽章 + 类型 + URL 单行
 *  - 点击请求展开详情面板（Tab：Headers/Response/Payload/Timing）
 *  - Headers：通用（URL/Method/Status/远端 IP）/ 响应头 / 请求头
 *  - Response：getResponseBody 拉响应体（支持 base64 标注）
 *  - Payload：POST 请求体
 *  - Timing：DNS/Connect/Send/Wait/Receive
 *  - 复制按钮：复制 URL / 复制响应 / 复制 cURL
 *  - 长按请求复制 URL
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NetworkScreen(viewModel: CdpViewModel, state: UiState) {
    var filter by remember { mutableStateOf(NetFilter.ALL) }
    var expandedReqId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().padding(6.dp)) {
        if (!state.cdpConnected) {
            Text(
                "请先在 Targets 页连接一个 page target。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp)
            )
            return@Column
        }

        // 过滤 chip + 清空
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            NetFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f.label, style = MaterialTheme.typography.labelSmall) }
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { viewModel.clearNetwork(); expandedReqId = null }) {
                Icon(Icons.Filled.Delete, contentDescription = "清空")
            }
        }

        val filtered = remember(state.network, filter) {
            state.network.filter { it.matchType(filter) }
        }
        Text(
            "请求 ${filtered.size} / 共 ${state.network.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filtered, key = { it.requestId }) { req ->
                val isExpanded = expandedReqId == req.requestId
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .combinedClickable(
                            onClick = {
                                expandedReqId = if (isExpanded) null else req.requestId
                                // 展开时自动拉一次响应体
                                if (!isExpanded && req.finished && !req.bodyFetched && !req.failed) {
                                    viewModel.fetchNetworkBody(req)
                                }
                            },
                            onLongClick = { copyToClipboard(context, req.url) }
                        ),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isExpanded)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        // 第一行：徽章
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MethodBadge(req.method)
                            StatusBadge(req.status, req.failed)
                            if (req.type.isNotBlank()) {
                                Text(" ${req.type}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (req.finished) Text(" ✓",
                                color = MaterialTheme.colorScheme.secondary,
                                style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.weight(1f))
                            // 复制 URL 按钮
                            IconButton(
                                onClick = { copyToClipboard(context, req.url) },
                                modifier = Modifier.padding(0.dp)
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "复制 URL",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(0.dp))
                            }
                            Icon(
                                if (isExpanded) Icons.Filled.ExpandMore else Icons.Filled.KeyboardArrowDown,
                                contentDescription = if (isExpanded) "折叠" else "展开",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // URL
                        Text(req.url, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = if (isExpanded) 2 else 1,
                            overflow = TextOverflow.Ellipsis)
                        if (req.failed) {
                            Text("✗ ${req.errorText ?: "failed"}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall)
                        }
                        // 展开详情
                        if (isExpanded) {
                            Spacer(Modifier.height(6.dp))
                            NetworkDetailPanel(req, viewModel, context)
                        }
                    }
                }
            }
        }
    }
}

/** 详情面板：Tab 切换 Headers/Response/Payload/Timing。 */
@Composable
private fun NetworkDetailPanel(
    req: NetworkRequest,
    viewModel: CdpViewModel,
    context: Context
) {
    var tab by remember { mutableStateOf(DetailTab.HEADERS) }
    val currentReq = state(req, viewModel)

    Column(modifier = Modifier.fillMaxWidth()) {
        TabRow(
            selectedTabIndex = tab.ordinal,
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ) {
            DetailTab.entries.forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = { tab = t },
                    text = { Text(t.label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        when (tab) {
            DetailTab.HEADERS -> HeadersTab(currentReq, context)
            DetailTab.RESPONSE -> ResponseTab(currentReq, context)
            DetailTab.PAYLOAD -> PayloadTab(currentReq, context)
            DetailTab.TIMING -> TimingTab(currentReq)
        }
    }
}

/** 取最新请求（避免本地 req 是旧引用，body 拉到后会更新）。 */
@Composable
private fun state(req: NetworkRequest, viewModel: CdpViewModel): NetworkRequest {
    val ui = viewModel.ui.collectAsState().value
    return ui.network.firstOrNull { it.requestId == req.requestId } ?: req
}

@Composable
private fun HeadersTab(req: NetworkRequest, context: Context) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        // 通用
        SectionTitle("General")
        KV("URL", req.url, copyable = true, context = context)
        KV("Method", req.method)
        KV("Status Code", "${req.status} ${req.statusText}")
        if (req.remoteIP.isNotBlank()) KV("Remote Address", "${req.remoteIP}:${req.remotePort}")
        if (req.protocol.isNotBlank()) KV("Protocol", req.protocol)
        if (req.encodedDataLength > 0) KV("Encoded", "${req.encodedDataLength} bytes")

        if (req.responseHeaders.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            SectionTitle("Response Headers")
            req.responseHeaders.forEach { (k, v) -> KV(k, v, copyable = true, context = context) }
        }
        if (req.requestHeaders.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            SectionTitle("Request Headers")
            req.requestHeaders.forEach { (k, v) -> KV(k, v, copyable = true, context = context) }
        }
    }
}

@Composable
private fun ResponseTab(req: NetworkRequest, context: Context) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Response Body")
            Spacer(Modifier.weight(1f))
            if (req.bodyFetched && req.body.isNotEmpty()) {
                IconButton(onClick = { copyToClipboard(context, req.body) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "复制响应",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        when {
            req.failed -> Text("请求失败，无响应体", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            !req.finished -> Text("请求未完成…", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            !req.bodyFetched -> Text("正在拉取响应体…", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            req.bodyBase64Encoded -> Text("(base64 编码的 ${req.body.length} 字节二进制数据)",
                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            req.body.isEmpty() -> Text("(空响应体)", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Text(req.body.take(8000),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().padding(2.dp))
        }
    }
}

@Composable
private fun PayloadTab(req: NetworkRequest, context: Context) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        SectionTitle("Request Payload")
        if (!req.hasPostData || req.postData.isNullOrBlank()) {
            Text("（无 POST 请求体）", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(req.postData.take(8000),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = { copyToClipboard(context, req.postData) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "复制 Payload",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TimingTab(req: NetworkRequest) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        SectionTitle("Timing")
        if (req.timing.isEmpty()) {
            Text("（无 timing 数据）", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            req.timing.forEach { (k, v) ->
                KV(k, "${v} ms")
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun KV(key: String, value: String, copyable: Boolean = false, context: Context? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text("$key: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp))
        Text(value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .weight(1f)
                .then(if (copyable && context != null) Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = { copyToClipboard(context, value) }
                ) else Modifier),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MethodBadge(method: String) {
    val color = when (method) {
        "GET" -> Color(0xFF1E8E3E)
        "POST" -> Color(0xFF1A73E8)
        "PUT", "PATCH" -> Color(0xFFE37400)
        "DELETE" -> Color(0xFFD93025)
        else -> Color(0xFF5F6368)
    }
    Badge(text = method.ifBlank { "—" }, bg = color)
}

@Composable
private fun StatusBadge(status: Int, failed: Boolean) {
    val color = when {
        failed -> Color(0xFFD93025)
        status in 200..299 -> Color(0xFF1E8E3E)
        status in 300..399 -> Color(0xFFE37400)
        status in 400..599 -> Color(0xFFD93025)
        else -> Color(0xFF5F6368)
    }
    Badge(text = if (status == 0) "—" else status.toString(), bg = color)
}

@Composable
private fun Badge(text: String, bg: Color) {
    Box(
        modifier = Modifier
            .padding(end = 4.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(bg)
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("network", text))
}
