package com.devtools.cdp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private enum class NetFilter(val label: String) {
    ALL("全部"), XHR("XHR"), DOC("Doc"), JS("JS"), CSS("CSS"), IMG("Img"), OTHER("其他")
}

private fun com.devtools.cdp.data.NetworkRequest.matchType(f: NetFilter): Boolean = when (f) {
    NetFilter.ALL -> true
    NetFilter.XHR -> type == "XHR"
    NetFilter.DOC -> type == "Document"
    NetFilter.JS -> type == "Script"
    NetFilter.CSS -> type == "Stylesheet"
    NetFilter.IMG -> type == "Image"
    NetFilter.OTHER -> type !in listOf("XHR", "Document", "Script", "Stylesheet", "Image", "", null)
}

/**
 * Network 页：状态码/方法徽章 + 类型过滤 + 计数 + 清空。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(viewModel: CdpViewModel, state: UiState) {
    var filter by remember { mutableStateOf(NetFilter.ALL) }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        if (!state.cdpConnected) {
            Text(
                "请先在 Targets 页连接一个 page target。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp)
            )
        } else {
            // 过滤 chip 行
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                NetFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(f.label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { viewModel.clearNetwork() }) {
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
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MethodBadge(req.method)
                                StatusBadge(req.status, req.failed)
                                if (req.type.isNotBlank()) {
                                    Text("  ${req.type}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (req.finished) Text(" ✓",
                                    color = MaterialTheme.colorScheme.secondary,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            Text(req.url, fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (req.failed) {
                                Text("✗ ${req.errorText ?: "failed"}",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
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
            .padding(end = 6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(bg)
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White
        )
    }
}
